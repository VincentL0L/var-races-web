//! One player's connection: reads their messages and puts them in a race or
//! race room. Everything the rooms send goes out through a bounded queue; a player whose
//! connection can't keep up is disconnected rather than buffering without limit.

use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::extract::ws::{Message, WebSocket};
use futures_util::{SinkExt, StreamExt};
use proto::{encode, ClientMsg, ServerMsg};
use sim::maps;
use tokio::sync::{mpsc, oneshot};

use crate::hub::{Hub, RaceHandle};
use crate::race::{self, RaceCmd};

/// messages a client may send per second (inputs come 60 times a second)
const MAX_MESSAGES_PER_SECOND: u32 = 150;
const OUTBOX: usize = 256;
const PING_EVERY: Duration = Duration::from_secs(20);

enum Place {
    Lobby,
    Race { room: RaceHandle, slot: u8 },
}

struct Session {
    hub: Arc<Hub>,
    out: mpsc::Sender<Vec<u8>>,
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

    let mut session = Session { hub, out, place: Place::Lobby };
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
            ClientMsg::ListRooms => self.send(&ServerMsg::Rooms(self.hub.public_races())),
            ClientMsg::Create { visibility, map, car } => {
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
            ClientMsg::Ready(ready) => self.to_race(|slot| RaceCmd::Ready { slot, ready }).await,
            ClientMsg::Settings(settings) => self.to_race(|slot| RaceCmd::Settings { slot, settings }).await,
            ClientMsg::Rematch => self.to_race(|slot| RaceCmd::Rematch { slot }).await,
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
                    Place::Lobby => {}
                }
                true
            }

        }
    }

    async fn join_race(&mut self, room: RaceHandle, car: u8) -> bool {
        let (reply, slot) = oneshot::channel();
        let cmd = RaceCmd::Join { car, out: self.out.clone(), reply };
        match room.tx.send(cmd).await.ok().and(slot.await.ok()).flatten() {
            Some(slot) => {
                self.place = Place::Race { room, slot };
                true
            }
            None => self.send(&ServerMsg::Error("That race is full or already started".into())),
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
            Place::Lobby => {}
        }
    }
}
