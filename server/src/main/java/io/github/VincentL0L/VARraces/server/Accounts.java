package io.github.VincentL0L.VARraces.server;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * Player accounts for ranked races, kept in Firebase:
 *  - players sign in with Google in the game; the game sends the server its Firebase ID
 *    token, which is checked here against Google's public keys (so nobody can pretend to be
 *    someone else)
 *  - ratings live in Cloud Firestore (users/{uid}); only this server writes them, with the
 *    service account in the FIREBASE_SERVICE_ACCOUNT environment variable
 *
 * Without that variable, sign-in still works but ranked is switched off.
 * All calls here block, so the server runs them off its game loop.
 */
public class Accounts {
    public static final String PROJECT = "var-races";
    public static final int START_RATING = 1000;

    /** a signed-in player */
    public static class Account {
        public String uid;
        /** first name and last initial, as shown to everyone */
        public String name;
    }

    /** what's stored for each player */
    public static class Profile {
        public String name;
        public int rating = START_RATING, races, wins;
    }

    private static final String CERTS = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
    private static final String FIRESTORE = "https://firestore.googleapis.com/v1/projects/" + PROJECT + "/databases/(default)/documents";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, PublicKey> keys = new HashMap<>();
    private long keysFetched = 0;
    // the service account (null = ranked is off)
    private String clientEmail;
    private PrivateKey privateKey;
    private String accessToken;
    private long accessTokenExpires = 0;

    public Accounts() {
        String json = System.getenv("FIREBASE_SERVICE_ACCOUNT");
        if (json == null || json.isBlank()) {
            System.out.println("Ranked off: FIREBASE_SERVICE_ACCOUNT isn't set");
            return;
        }
        try {
            JsonValue sa = new JsonReader().parse(json);
            clientEmail = sa.getString("client_email");
            String pem = sa.getString("private_key").replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            System.out.println("Ranked on, as " + clientEmail);
        } catch (Exception e) {
            System.out.println("Ranked off: couldn't read FIREBASE_SERVICE_ACCOUNT (" + e.getMessage() + ")");
            clientEmail = null;
            privateKey = null;
        }
    }

    /**
     * @return true if ratings can be read and saved
     */
    public boolean rankedOn() {
        return privateKey != null;
    }

    // ---------------------------------------------------------------- sign-in tokens

    /**
     * @param idToken a Firebase ID token from the game
     * @return who it belongs to, or null if it isn't a genuine, current token for this project
     */
    public Account verify(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            JsonValue header = new JsonReader().parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            JsonValue claims = new JsonReader().parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (!"RS256".equals(header.getString("alg", ""))) {
                return null;
            }
            PublicKey key = publicKey(header.getString("kid", ""));
            if (key == null) {
                return null;
            }
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            long now = System.currentTimeMillis() / 1000;
            if (!PROJECT.equals(claims.getString("aud", "")) || !("https://securetoken.google.com/" + PROJECT).equals(claims.getString("iss", ""))
                    || claims.getLong("exp", 0) < now || claims.getLong("iat", Long.MAX_VALUE) > now + 300) {
                return null;
            }
            Account a = new Account();
            a.uid = claims.getString("sub", "");
            if (a.uid.isEmpty()) {
                return null;
            }
            // Google accounts show first name and last initial; email accounts chose a username
            JsonValue fb = claims.get("firebase");
            boolean email = fb != null && "password".equals(fb.getString("sign_in_provider", ""));
            a.name = email ? username(claims.getString("name", "Racer")) : shortName(claims.getString("name", "Racer"));
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /** "Vincent Lo" -> "Vincent L" (first name and last initial, nothing more) */
    static String shortName(String full) {
        String[] words = full.trim().replaceAll("[^\\p{L}\\p{N} '-]", "").split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "Racer";
        }
        String first = words[0].length() > 12 ? words[0].substring(0, 12) : words[0];
        return words.length > 1 ? first + " " + Character.toUpperCase(words[words.length - 1].charAt(0)) : first;
    }

    /** a chosen username, tidied: letters, numbers, spaces, _ and -, at most 14 characters */
    static String username(String name) {
        String clean = name.trim().replaceAll("[^\\p{L}\\p{N} _-]", "").replaceAll("\\s+", " ");
        if (clean.length() > 14) {
            clean = clean.substring(0, 14).trim();
        }
        return clean.length() < 2 ? "Racer" : clean;
    }

    private synchronized PublicKey publicKey(String kid) throws Exception {
        if (!keys.containsKey(kid) || System.currentTimeMillis() - keysFetched > 3600_000L) {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(CERTS)).GET().build(), HttpResponse.BodyHandlers.ofString());
            keys.clear();
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (JsonValue c = new JsonReader().parse(res.body()).child; c != null; c = c.next) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(c.asString().getBytes(StandardCharsets.US_ASCII)));
                keys.put(c.name, cert.getPublicKey());
            }
            keysFetched = System.currentTimeMillis();
        }
        return keys.get(kid);
    }

    // ---------------------------------------------------------------- Firestore

    /**
     * @return the player's stored profile (a new one if they've never played ranked)
     */
    public Profile load(Account a) throws Exception {
        Profile p = new Profile();
        p.name = a.name;
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(FIRESTORE + "/users/" + enc(a.uid))).GET());
        if (res.statusCode() == 200) {
            JsonValue f = new JsonReader().parse(res.body()).get("fields");
            if (f != null) {
                p.rating = intField(f, "rating", START_RATING);
                p.races = intField(f, "races", 0);
                p.wins = intField(f, "wins", 0);
            }
        } else if (res.statusCode() != 404) {
            throw new IllegalStateException("Firestore " + res.statusCode() + ": " + res.body());
        }
        return p;
    }

    /**
     * saves a player's profile
     */
    public void save(String uid, Profile p) throws Exception {
        String body = "{\"fields\":{"
            + "\"name\":{\"stringValue\":\"" + p.name.replace("\\", "").replace("\"", "") + "\"},"
            + "\"rating\":{\"integerValue\":\"" + p.rating + "\"},"
            + "\"races\":{\"integerValue\":\"" + p.races + "\"},"
            + "\"wins\":{\"integerValue\":\"" + p.wins + "\"},"
            + "\"updated\":{\"timestampValue\":\"" + java.time.Instant.now() + "\"}}}";
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(FIRESTORE + "/users/" + enc(uid)))
            .header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Firestore " + res.statusCode() + ": " + res.body());
        }
    }

    /**
     * @param count how many
     * @return the best players, best first
     */
    public List<Profile> top(int count) throws Exception {
        String query = "{\"structuredQuery\":{\"from\":[{\"collectionId\":\"users\"}],"
            + "\"orderBy\":[{\"field\":{\"fieldPath\":\"rating\"},\"direction\":\"DESCENDING\"}],\"limit\":" + count + "}}";
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(FIRESTORE + ":runQuery"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(query)));
        List<Profile> list = new ArrayList<>();
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Firestore " + res.statusCode() + ": " + res.body());
        }
        for (JsonValue row = new JsonReader().parse(res.body()).child; row != null; row = row.next) {
            JsonValue doc = row.get("document");
            if (doc == null) {
                continue;
            }
            JsonValue f = doc.get("fields");
            Profile p = new Profile();
            p.name = f.has("name") ? f.get("name").getString("stringValue", "Racer") : "Racer";
            p.rating = intField(f, "rating", START_RATING);
            p.races = intField(f, "races", 0);
            p.wins = intField(f, "wins", 0);
            list.add(p);
        }
        return list;
    }

    private static int intField(JsonValue fields, String name, int fallback) {
        JsonValue v = fields.get(name);
        return v == null ? fallback : Integer.parseInt(v.getString("integerValue", String.valueOf(fallback)));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        if (!rankedOn()) {
            throw new IllegalStateException("ranked is off");
        }
        return http.send(request.header("Authorization", "Bearer " + accessToken()).timeout(Duration.ofSeconds(15)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** an OAuth access token for the service account (cached for most of its hour) */
    private synchronized String accessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (accessToken != null && now < accessTokenExpires - 300) {
            return accessToken;
        }
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = b64("{\"iss\":\"" + clientEmail + "\",\"scope\":\"https://www.googleapis.com/auth/datastore\","
            + "\"aud\":\"https://oauth2.googleapis.com/token\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String jwt = header + "." + claims + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + jwt))
            .build(), HttpResponse.BodyHandlers.ofString());
        JsonValue body = new JsonReader().parse(res.body());
        if (!body.has("access_token")) {
            throw new IllegalStateException("token: " + res.body());
        }
        accessToken = body.getString("access_token");
        accessTokenExpires = now + body.getLong("expires_in", 3600);
        return accessToken;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- ratings

    /**
     * Elo for a race of several players: everyone is compared with everyone else (finishing
     * ahead is a win against them), and the change is averaged so a big race doesn't swing
     * ratings more than a head-to-head.
     * @param ratings the players' ratings, in finishing order (best first)
     * @return each player's new rating, same order
     */
    public static int[] newRatings(int[] ratings) {
        int n = ratings.length;
        int[] out = ratings.clone();
        if (n < 2) {
            return out;
        }
        double k = 48;
        for (int i = 0; i < n; i++) {
            double change = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double expected = 1.0 / (1.0 + Math.pow(10, (ratings[j] - ratings[i]) / 400.0));
                double actual = i < j ? 1 : 0;
                change += k * (actual - expected);
            }
            out[i] = Math.max(100, (int) Math.round(ratings[i] + change / (n - 1)));
        }
        return out;
    }

    /**
     * @return the rank name for a rating
     */
    public static String tier(int rating) {
        return rating >= 1700 ? "CHAMPION" : rating >= 1550 ? "DIAMOND" : rating >= 1400 ? "PLATINUM"
            : rating >= 1250 ? "GOLD" : rating >= 1100 ? "SILVER" : "BRONZE";
    }
}
