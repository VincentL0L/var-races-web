//! One player's connection: reads their messages, signs them in, and puts them in a race or
//! soccer room. Everything the rooms send goes out through a bounded queue; a player whose
//! connection can't keep up is disconnected rather than buffering without limit.

use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::extract::ws::{Message, WebSocket};
use futures_util::{SinkExt, StreamExt};
use proto::{encode, ClientMsg, ServerMsg, Visibility};
use sim::maps;
use tokio::sync::{mpsc, oneshot};

use crate::accounts::{self, Account};
use crate::hub::{Hub, RaceHandle, SoccerHandle};
use crate::race::{self, RaceCmd};
use crate::soccer::{self, SoccerCmd};

/// messages a client may send per second (inputs come 60 times a second)
const MAX_MESSAGES_PER_SECOND: u32 = 150;
const OUTBOX: usize = 256;
const PING_EVERY: Duration = Duration::from_secs(20);

enum Place {
    Lobby,
    Race { room: RaceHandle, slot: u8 },
    Soccer { room: SoccerHandle, slot: u8 },
}

struct Session {
    hub: Arc<Hub>,
    out: mpsc::Sender<Vec<u8>>,
    account: Option<Account>,
    place: Place,
}

pub async fn serve(socket: WebSocket, hub: Arc<Hub>) {
    let (mut sink, mut stream) = socket.split();
    let (out, mut outbox) = mpsc::channel::<Vec<u8>>(OUTBOX);
    let writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(PING_EVERY);
        loop {
            tokio::select! {
                msg = outbox.recv() => match msg {
                    Some(bytes) => if sink.send(Message::Binary(bytes.into())).await.is_err() { break },
                    None => break,
                },
                _ = ping.tick() => if sink.send(Message::Ping(Default::default())).await.is_err() { break },
            }
        }
        let _ = sink.close().await;
    });

    let mut session = Session { hub, out, account: None, place: Place::Lobby };
    let mut window = Instant::now();
    let mut count = 0;
    while let Some(Ok(msg)) = stream.next().await {
        let bytes = match msg {
            Message::Binary(b) => b,
            Message::Close(_) => break,
            _ => continue,
        };
        if window.elapsed() >= Duration::from_secs(1) {
            window = Instant::now();
            count = 0;
        }
        count += 1;
        if count > MAX_MESSAGES_PER_SECOND {
            tracing::warn!("disconnecting a client sending too fast");
            break;
        }
        let Some(msg) = proto::decode_client(&bytes) else {
            session.send(&ServerMsg::Error("Bad request".into()));
            continue;
        };
        if !session.handle(msg).await {
            break;
        }
    }
    session.leave().await;
    drop(session);
    let _ = writer.await;
}

impl Session {
    /// queues a message; false if this player's connection is hopelessly behind
    fn send(&self, msg: &ServerMsg) -> bool {
        self.out.try_send(encode(msg)).is_ok()
    }

    /// handles one message; false to hang up
    async fn handle(&mut self, msg: ClientMsg) -> bool {
        match msg {
            ClientMsg::Hello { version } => {
                if version != proto::VERSION {
                    self.send(&ServerMsg::Error("The game was updated. Please reload the page.".into()));
                    return false;
                }
                self.send(&ServerMsg::Welcome { version: proto::VERSION })
            }
            ClientMsg::Auth { token } => self.sign_in(&token).await,
            ClientMsg::SignOut => {
                self.account = None;
                true
            }
            ClientMsg::ListRooms => self.send(&ServerMsg::Rooms(self.hub.public_races())),
            ClientMsg::Create { visibility, map, car } => {
                let visibility = if visibility == Visibility::Public { Visibility::Public } else { Visibility::Private };
                self.leave().await;
                match race::spawn(&self.hub, maps::get(&map), visibility) {
                    Some((_, room)) => self.join_race(room, car).await,
                    None => self.send(&ServerMsg::Error("The server is busy. Try again in a minute.".into())),
                }
            }
            ClientMsg::Join { code, car } => {
                self.leave().await;
                match self.hub.race(code.trim()) {
                    Some(room) => self.join_race(room, car).await,
                    None => self.send(&ServerMsg::Error(format!("No race with code {}", code.trim().to_uppercase()))),
                }
            }
            ClientMsg::Ranked { car } => self.join_ranked(car).await,
            ClientMsg::Ready(ready) => self.to_race(|slot| RaceCmd::Ready { slot, ready }).await,
            ClientMsg::Settings(settings) => self.to_race(|slot| RaceCmd::Settings { slot, settings }).await,
            ClientMsg::Rematch => match &self.place {
                Place::Soccer { room, slot } => {
                    let _ = room.tx.send(SoccerCmd::Rematch { slot: *slot }).await;
                    true
                }
                _ => self.to_race(|slot| RaceCmd::Rematch { slot }).await,
            },
            ClientMsg::Leave => {
                self.leave().await;
                true
            }
            ClientMsg::Inputs { last, inputs } => {
                if inputs.len() > proto::INPUT_REDUNDANCY * 2 {
                    return true;
                }
                match &self.place {
                    Place::Race { room, slot } => {
                        let _ = room.tx.try_send(RaceCmd::Inputs { slot: *slot, last, inputs });
                    }
                    Place::Soccer { room, slot } => {
                        let _ = room.tx.try_send(SoccerCmd::Inputs { slot: *slot, last, inputs });
                    }
                    Place::Lobby => {}
                }
                true
            }
            ClientMsg::Leaderboard => {
                let top = if self.hub.accounts.ranked_on() {
                    self.hub.accounts.top().await.unwrap_or_else(|e| {
                        tracing::warn!("leaderboard: {e}");
                        Vec::new()
                    })
                } else {
                    Vec::new()
                };
                let top = top
                    .into_iter()
                    .map(|p| proto::TopEntry { tier: accounts::tier(p.rating).into(), name: p.name, rating: p.rating })
                    .collect();
                self.send(&ServerMsg::Top(top))
            }
            ClientMsg::SoccerList => self.send(&ServerMsg::SoccerRooms(self.hub.soccer_rooms())),
            ClientMsg::SoccerCreate { team_size } => {
                self.leave().await;
                match soccer::spawn(&self.hub, team_size) {
                    Some((_, room)) => self.join_soccer(room).await,
                    None => self.send(&ServerMsg::Error("The server is busy. Try again in a minute.".into())),
                }
            }
            ClientMsg::SoccerJoin { code } => {
                self.leave().await;
                match self.hub.soccer(code.trim()) {
                    Some(room) => self.join_soccer(room).await,
                    None => self.send(&ServerMsg::Error(format!("No room with code {}", code.trim().to_uppercase()))),
                }
            }
        }
    }

    async fn sign_in(&mut self, token: &str) -> bool {
        let accounts = &self.hub.accounts;
        let Some(account) = accounts.verify(token).await else {
            return self.send(&ServerMsg::AuthFailed);
        };
        let mut profile = None;
        if accounts.ranked_on() {
            let cached = accounts.profiles.lock().unwrap().get(&account.uid).cloned();
            profile = match cached {
                Some(p) => Some(p),
                None => match accounts.load(&account).await {
                    Ok(p) => {
                        accounts.profiles.lock().unwrap().insert(account.uid.clone(), p.clone());
                        Some(p)
                    }
                    Err(e) => {
                        tracing::warn!("loading a profile: {e}");
                        None
                    }
                },
            };
        }
        let p = profile.clone().unwrap_or_else(|| accounts::Profile::new(&account.name));
        self.account = Some(account.clone());
        self.send(&ServerMsg::Profile(proto::Profile {
            name: account.name,
            rating: p.rating,
            races: p.races,
            wins: p.wins,
            tier: accounts::tier(p.rating).into(),
            ranked_on: profile.is_some(),
        }))
    }

    async fn join_race(&mut self, room: RaceHandle, car: u8) -> bool {
        let (reply, slot) = oneshot::channel();
        let name = self.account.as_ref().map(|a| a.name.clone());
        let uid = self.account.as_ref().map(|a| a.uid.clone());
        let cmd = RaceCmd::Join { name, uid, car, out: self.out.clone(), reply };
        match room.tx.send(cmd).await.ok().and(slot.await.ok()).flatten() {
            Some(slot) => {
                self.place = Place::Race { room, slot };
                true
            }
            None => self.send(&ServerMsg::Error("That race is full or already started".into())),
        }
    }

    /// into the ranked queue: the open ranked room if there is one, or a new one
    async fn join_ranked(&mut self, car: u8) -> bool {
        let Some(account) = self.account.clone() else {
            return self.send(&ServerMsg::Error("Sign in to race ranked".into()));
        };
        let has_profile = self.hub.accounts.profiles.lock().unwrap().contains_key(&account.uid);
        if !self.hub.accounts.ranked_on() || !has_profile {
            return self.send(&ServerMsg::Error("Ranked is offline right now".into()));
        }
        self.leave().await;
        if let Some(room) = self.hub.open_ranked() {
            let (reply, slot) = oneshot::channel();
            let cmd = RaceCmd::Join {
                name: Some(account.name),
                uid: Some(account.uid),
                car,
                out: self.out.clone(),
                reply,
            };
            if let Some(slot) = room.tx.send(cmd).await.ok().and(slot.await.ok()).flatten() {
                self.place = Place::Race { room, slot };
                return true;
            }
        }
        match race::spawn(&self.hub, race::ranked_map(), Visibility::Ranked) {
            Some((_, room)) => self.join_race(room, car).await,
            None => self.send(&ServerMsg::Error("The server is busy. Try again in a minute.".into())),
        }
    }

    async fn join_soccer(&mut self, room: SoccerHandle) -> bool {
        let (reply, result) = oneshot::channel();
        if room.tx.send(SoccerCmd::Join { out: self.out.clone(), reply }).await.is_err() {
            return self.send(&ServerMsg::Error("That room just closed".into()));
        }
        match result.await {
            Ok(Ok(slot)) => {
                self.place = Place::Soccer { room, slot };
                true
            }
            Ok(Err(e)) => self.send(&ServerMsg::Error(e.into())),
            Err(_) => self.send(&ServerMsg::Error("That room just closed".into())),
        }
    }

    async fn to_race(&self, cmd: impl FnOnce(u8) -> RaceCmd) -> bool {
        if let Place::Race { room, slot } = &self.place {
            let _ = room.tx.send(cmd(*slot)).await;
        }
        true
    }

    async fn leave(&mut self) {
        match std::mem::replace(&mut self.place, Place::Lobby) {
            Place::Race { room, slot } => {
                let _ = room.tx.send(RaceCmd::Leave { slot }).await;
            }
            Place::Soccer { room, slot } => {
                let _ = room.tx.send(SoccerCmd::Leave { slot }).await;
            }
            Place::Lobby => {}
        }
    }
}
