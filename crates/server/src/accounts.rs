//! Player accounts for ranked races, kept in Firebase:
//!  - players sign in (Google or email) in the game, which sends its Firebase ID token; the
//!    token is checked here against Google's public keys, so nobody can pretend to be someone
//!    else
//!  - ratings live in Cloud Firestore (users/{uid}); only this server writes them, using the
//!    service account in the FIREBASE_SERVICE_ACCOUNT environment variable
//!
//! Without that variable, sign-in still works but ranked is off.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header, Validation};
use serde::Deserialize;
use serde_json::{json, Value};

pub const PROJECT: &str = "var-races";
pub const START_RATING: i32 = 1000;

const JWKS: &str = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const KEYS_MAX_AGE: Duration = Duration::from_secs(3600);
const TOP_MAX_AGE: Duration = Duration::from_secs(30);

/// a signed-in player
#[derive(Clone, Debug, PartialEq)]
pub struct Account {
    pub uid: String,
    /// the name everyone sees
    pub name: String,
}

/// what's stored for each player
#[derive(Clone, Debug, PartialEq)]
pub struct Profile {
    pub name: String,
    pub rating: i32,
    pub races: u32,
    pub wins: u32,
}

impl Profile {
    pub fn new(name: &str) -> Profile {
        Profile { name: name.to_string(), rating: START_RATING, races: 0, wins: 0 }
    }
}

struct ServiceAccount {
    email: String,
    key: EncodingKey,
}

#[derive(Default)]
struct Cache {
    keys: HashMap<String, DecodingKey>,
    keys_fetched: Option<Instant>,
    access_token: Option<(String, Instant)>,
    top: Option<(Vec<Profile>, Instant)>,
}

pub struct Accounts {
    http: reqwest::Client,
    service: Option<ServiceAccount>,
    cache: tokio::sync::Mutex<Cache>,
    /// signed-in players' profiles, by uid (loaded at sign-in, updated after ranked races)
    pub profiles: Mutex<HashMap<String, Profile>>,
}

#[derive(Deserialize)]
struct Jwk {
    kid: String,
    n: String,
    e: String,
}

#[derive(Deserialize)]
struct Claims {
    sub: String,
    iat: u64,
    name: Option<String>,
    firebase: Option<FirebaseClaim>,
}

#[derive(Deserialize)]
struct FirebaseClaim {
    sign_in_provider: Option<String>,
}

impl Accounts {
    pub fn from_env() -> Accounts {
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(15))
            .build()
            .expect("http client");
        let service = std::env::var("FIREBASE_SERVICE_ACCOUNT").ok().filter(|s| !s.trim().is_empty()).and_then(|json| {
            match parse_service_account(&json) {
                Ok(sa) => {
                    tracing::info!("ranked on, as {}", sa.email);
                    Some(sa)
                }
                Err(e) => {
                    tracing::warn!("ranked off: couldn't read FIREBASE_SERVICE_ACCOUNT ({e})");
                    None
                }
            }
        });
        if service.is_none() {
            tracing::info!("ranked off: FIREBASE_SERVICE_ACCOUNT isn't set");
        }
        Accounts { http, service, cache: Default::default(), profiles: Default::default() }
    }

    /// true if ratings can be read and saved
    pub fn ranked_on(&self) -> bool {
        self.service.is_some()
    }

    /// who a Firebase ID token belongs to, or None if it isn't a genuine, current token
    pub async fn verify(&self, token: &str) -> Option<Account> {
        let header = jsonwebtoken::decode_header(token).ok()?;
        if header.alg != Algorithm::RS256 {
            return None;
        }
        let key = self.public_key(&header.kid?).await?;
        let mut validation = Validation::new(Algorithm::RS256);
        validation.set_audience(&[PROJECT]);
        validation.set_issuer(&[format!("https://securetoken.google.com/{PROJECT}")]);
        let claims = jsonwebtoken::decode::<Claims>(token, &key, &validation).ok()?.claims;
        if claims.sub.is_empty() || claims.iat > now() + 300 {
            return None;
        }
        let email = claims.firebase.and_then(|f| f.sign_in_provider).as_deref() == Some("password");
        let full = claims.name.unwrap_or_else(|| "Racer".into());
        let name = if email { username(&full) } else { short_name(&full) };
        Some(Account { uid: claims.sub, name })
    }

    async fn public_key(&self, kid: &str) -> Option<DecodingKey> {
        let mut cache = self.cache.lock().await;
        let stale = cache.keys_fetched.is_none_or(|t| t.elapsed() > KEYS_MAX_AGE);
        if stale || !cache.keys.contains_key(kid) {
            #[derive(Deserialize)]
            struct Set {
                keys: Vec<Jwk>,
            }
            let set: Set = self.http.get(JWKS).send().await.ok()?.json().await.ok()?;
            cache.keys = set
                .keys
                .into_iter()
                .filter_map(|k| Some((k.kid, DecodingKey::from_rsa_components(&k.n, &k.e).ok()?)))
                .collect();
            cache.keys_fetched = Some(Instant::now());
        }
        cache.keys.get(kid).cloned()
    }

    fn firestore(&self) -> String {
        format!("https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents")
    }

    /// an OAuth access token for the service account (cached for most of its hour)
    async fn access_token(&self) -> Result<String, String> {
        let sa = self.service.as_ref().ok_or("ranked is off")?;
        let mut cache = self.cache.lock().await;
        if let Some((token, expires)) = &cache.access_token {
            if Instant::now() + Duration::from_secs(300) < *expires {
                return Ok(token.clone());
            }
        }
        let iat = now();
        let claims = json!({
            "iss": sa.email,
            "scope": "https://www.googleapis.com/auth/datastore",
            "aud": TOKEN_URL,
            "iat": iat,
            "exp": iat + 3600,
        });
        let jwt = jsonwebtoken::encode(&Header::new(Algorithm::RS256), &claims, &sa.key).map_err(|e| e.to_string())?;
        let res: Value = self
            .http
            .post(TOKEN_URL)
            .form(&[("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"), ("assertion", &jwt)])
            .send()
            .await
            .map_err(|e| e.to_string())?
            .json()
            .await
            .map_err(|e| e.to_string())?;
        let token = res["access_token"].as_str().ok_or_else(|| format!("token: {res}"))?.to_string();
        let lifetime = res["expires_in"].as_u64().unwrap_or(3600);
        cache.access_token = Some((token.clone(), Instant::now() + Duration::from_secs(lifetime)));
        Ok(token)
    }

    /// the player's stored profile (a new one if they've never raced ranked)
    pub async fn load(&self, account: &Account) -> Result<Profile, String> {
        let token = self.access_token().await?;
        let url = format!("{}/users/{}", self.firestore(), account.uid);
        let res = self.http.get(url).bearer_auth(token).send().await.map_err(|e| e.to_string())?;
        let mut profile = Profile::new(&account.name);
        match res.status().as_u16() {
            200 => {
                let doc: Value = res.json().await.map_err(|e| e.to_string())?;
                let f = &doc["fields"];
                profile.rating = int_field(f, "rating", START_RATING as i64) as i32;
                profile.races = int_field(f, "races", 0) as u32;
                profile.wins = int_field(f, "wins", 0) as u32;
            }
            404 => {}
            code => return Err(format!("Firestore {code}: {}", res.text().await.unwrap_or_default())),
        }
        Ok(profile)
    }

    pub async fn save(&self, uid: &str, p: &Profile) -> Result<(), String> {
        let token = self.access_token().await?;
        let url = format!("{}/users/{uid}", self.firestore());
        let updated = humantime_now();
        let body = json!({ "fields": {
            "name": { "stringValue": p.name },
            "rating": { "integerValue": p.rating.to_string() },
            "races": { "integerValue": p.races.to_string() },
            "wins": { "integerValue": p.wins.to_string() },
            "updated": { "timestampValue": updated },
        }});
        let res = self.http.patch(url).bearer_auth(token).json(&body).send().await.map_err(|e| e.to_string())?;
        if !res.status().is_success() {
            return Err(format!("Firestore {}: {}", res.status(), res.text().await.unwrap_or_default()));
        }
        self.cache.lock().await.top = None;
        Ok(())
    }

    /// the best players, best first (cached for half a minute)
    pub async fn top(&self) -> Result<Vec<Profile>, String> {
        if let Some((top, at)) = &self.cache.lock().await.top {
            if at.elapsed() < TOP_MAX_AGE {
                return Ok(top.clone());
            }
        }
        let token = self.access_token().await?;
        let query = json!({ "structuredQuery": {
            "from": [{ "collectionId": "users" }],
            "orderBy": [{ "field": { "fieldPath": "rating" }, "direction": "DESCENDING" }],
            "limit": 20,
        }});
        let url = format!("{}:runQuery", self.firestore());
        let res = self.http.post(url).bearer_auth(token).json(&query).send().await.map_err(|e| e.to_string())?;
        if !res.status().is_success() {
            return Err(format!("Firestore {}", res.status()));
        }
        let rows: Vec<Value> = res.json().await.map_err(|e| e.to_string())?;
        let top: Vec<Profile> = rows
            .iter()
            .filter_map(|row| row.get("document"))
            .map(|doc| {
                let f = &doc["fields"];
                Profile {
                    name: f["name"]["stringValue"].as_str().unwrap_or("Racer").to_string(),
                    rating: int_field(f, "rating", START_RATING as i64) as i32,
                    races: int_field(f, "races", 0) as u32,
                    wins: int_field(f, "wins", 0) as u32,
                }
            })
            .collect();
        self.cache.lock().await.top = Some((top.clone(), Instant::now()));
        Ok(top)
    }
}

fn parse_service_account(json: &str) -> Result<ServiceAccount, String> {
    let v: Value = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let email = v["client_email"].as_str().ok_or("no client_email")?.to_string();
    let pem = v["private_key"].as_str().ok_or("no private_key")?.replace("\\n", "\n");
    let key = EncodingKey::from_rsa_pem(pem.as_bytes()).map_err(|e| e.to_string())?;
    Ok(ServiceAccount { email, key })
}

fn int_field(fields: &Value, name: &str, fallback: i64) -> i64 {
    fields[name]["integerValue"].as_str().and_then(|s| s.parse().ok()).unwrap_or(fallback)
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// the current time as RFC 3339 (UTC), for Firestore
fn humantime_now() -> String {
    let secs = now() as i64;
    let (days, rem) = (secs.div_euclid(86400), secs.rem_euclid(86400));
    // civil date from days since 1970-01-01 (Howard Hinnant's algorithm)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + (month <= 2) as i64;
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

/// "Vincent Lo" -> "Vincent L" (first name and last initial, nothing more)
pub fn short_name(full: &str) -> String {
    let clean: String = full.chars().filter(|c| c.is_alphanumeric() || " '-".contains(*c)).collect();
    let words: Vec<&str> = clean.split_whitespace().collect();
    let Some(first) = words.first() else { return "Racer".into() };
    let first: String = first.chars().take(12).collect();
    match words.last().filter(|_| words.len() > 1).and_then(|w| w.chars().next()) {
        Some(initial) => format!("{first} {}", initial.to_uppercase()),
        None => first,
    }
}

/// a chosen username, tidied: letters, numbers, spaces, _ and -, at most 14 characters
pub fn username(name: &str) -> String {
    let clean: String = name.chars().filter(|c| c.is_alphanumeric() || " _-".contains(*c)).collect();
    let clean = clean.split_whitespace().collect::<Vec<_>>().join(" ");
    let clean: String = clean.chars().take(14).collect::<String>().trim().to_string();
    if clean.chars().count() < 2 {
        "Racer".into()
    } else {
        clean
    }
}

/// Elo for a race of several players: everyone is compared with everyone else (finishing ahead
/// is a win against them), and the change is averaged so a big race doesn't swing ratings more
/// than a head-to-head. `ratings` are in finishing order; returns the new ratings.
pub fn new_ratings(ratings: &[i32]) -> Vec<i32> {
    let n = ratings.len();
    if n < 2 {
        return ratings.to_vec();
    }
    const K: f64 = 48.0;
    (0..n)
        .map(|i| {
            let change: f64 = (0..n)
                .filter(|&j| j != i)
                .map(|j| {
                    let expected = 1.0 / (1.0 + 10f64.powf((ratings[j] - ratings[i]) as f64 / 400.0));
                    let actual = if i < j { 1.0 } else { 0.0 };
                    K * (actual - expected)
                })
                .sum();
            ((ratings[i] as f64 + change / (n - 1) as f64).round() as i32).max(100)
        })
        .collect()
}

/// the rank name for a rating
pub fn tier(rating: i32) -> &'static str {
    match rating {
        r if r >= 1700 => "CHAMPION",
        r if r >= 1550 => "DIAMOND",
        r if r >= 1400 => "PLATINUM",
        r if r >= 1250 => "GOLD",
        r if r >= 1100 => "SILVER",
        _ => "BRONZE",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_tidied() {
        assert_eq!(short_name("Vincent Lo"), "Vincent L");
        assert_eq!(short_name("  "), "Racer");
        assert_eq!(short_name("Cher"), "Cher");
        assert_eq!(username("  speed<demon>  2000!!  "), "speeddemon 200");
        assert_eq!(username("x"), "Racer");
    }

    #[test]
    fn elo_rewards_the_winner_and_is_zero_sum_for_equals() {
        let r = new_ratings(&[1000, 1000]);
        assert_eq!(r, vec![1024, 976]);
        let r = new_ratings(&[1000, 1000, 1000]);
        assert!(r[0] > 1000 && r[1] == 1000 && r[2] < 1000);
        assert_eq!(new_ratings(&[150, 120]), vec![172, 100]);
    }

    #[test]
    fn tiers() {
        assert_eq!(tier(1000), "BRONZE");
        assert_eq!(tier(1100), "SILVER");
        assert_eq!(tier(1700), "CHAMPION");
    }

    #[test]
    fn timestamps_are_rfc3339() {
        let t = humantime_now();
        assert_eq!(t.len(), 20);
        assert!(t.starts_with("20") && t.ends_with('Z'));
    }
}
