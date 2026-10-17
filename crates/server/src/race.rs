//! One race room's task: it owns a sim::room::RaceRoom, ticks it at 60 Hz with the players'
//! inputs and sends everyone a snapshot 30 times a second.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use proto::{encode, Lobby, LobbyPlayer, ServerMsg, Visibility};
use sim::events::Event;
use sim::maps::MapDef;
use sim::room::{Phase, RaceRoom, Settings, TICK_RATE};
use tokio::sync::{mpsc, oneshot};
use tokio::time::MissedTickBehavior;

use crate::hub::{Hub, RaceHandle, RaceStatus};
use crate::inputs::InputQueue;

/// a player's outgoing messages (the connection's writer)
pub type Out = mpsc::Sender<Vec<u8>>;

/// a snapshot every this many ticks (30 Hz)
const SNAPSHOT_EVERY: u64 = 2;
/// rooms close after this long, whatever is going on (nobody races for two hours)
const MAX_AGE: Duration = Duration::from_secs(2 * 3600);

pub enum RaceCmd {
    Join { car: u8, out: Out, reply: oneshot::Sender<Option<u8>> },
    Leave { slot: u8 },
    Ready { slot: u8, ready: bool },
    Settings { slot: u8, settings: Settings },
    Rematch { slot: u8 },
    Inputs { slot: u8, last: u32, inputs: Vec<[u8; 2]> },
}

struct Member {
    out: Out,
    inputs: InputQueue,
}

struct Task {
    hub: Arc<Hub>,
    code: String,
    visibility: Visibility,
    room: RaceRoom,
    members: BTreeMap<u8, Member>,
    status: Arc<Mutex<RaceStatus>>,
    events: Vec<Event>,
    lobby_changed: bool,
    guests: u32,
}

/// opens a race room on `map` and starts its task
pub fn spawn(hub: &Arc<Hub>, map: &'static MapDef, visibility: Visibility) -> Option<(String, RaceHandle)> {
    let (tx, rx) = mpsc::channel(256);
    let status = Arc::new(Mutex::new(RaceStatus { visibility, map: map.id, players: 0, joinable: true }));
    let (code, handle) = hub.add_race(|_| RaceHandle { tx, status: status.clone() })?;
    let room = RaceRoom::new(map, rand::random());
    let task = Task {
        hub: hub.clone(),
        code: code.clone(),
        visibility,
        room,
        members: BTreeMap::new(),
        status,
        events: Vec::new(),
        lobby_changed: false,
        guests: 0,
    };
    tokio::spawn(task.run(rx));
    Some((code, handle))
}

impl Task {
    async fn run(mut self, mut rx: mpsc::Receiver<RaceCmd>) {
        let mut interval = tokio::time::interval(Duration::from_secs_f64(1.0 / TICK_RATE as f64));
        interval.set_missed_tick_behavior(MissedTickBehavior::Burst);
        let born = tokio::time::Instant::now();
        tracing::info!(code = %self.code, map = self.room.map.id, "race room opened");
        loop {
            tokio::select! {
                cmd = rx.recv() => match cmd {
                    Some(cmd) => self.handle(cmd),
                    None => break,
                },
                _ = interval.tick() => {
                    self.tick();
                    if self.members.is_empty() || born.elapsed() > MAX_AGE {
                        break;
                    }
                }
            }
        }
        self.hub.remove_race(&self.code);
        tracing::info!(code = %self.code, "race room closed");
    }

    fn handle(&mut self, cmd: RaceCmd) {
        self.apply(cmd);
        self.update_status();
    }

    fn apply(&mut self, cmd: RaceCmd) {
        match cmd {
            RaceCmd::Join { car, out, reply } => {
                self.guests += 1;
                let name = format!("Player {}", self.guests);
                let car = car.clamp(1, sim::car::car_count());
                let slot = self.room.add_player(&name, car);
                if let Some(slot) = slot {
                    let joined = ServerMsg::Joined {
                        code: self.code.clone(),
                        visibility: self.visibility,
                        slot,
                        map: self.room.map.id.to_string(),
                    };
                    let _ = out.try_send(encode(&joined));
                    self.members.insert(slot, Member { out, inputs: InputQueue::default() });
                    self.lobby_changed = true;
                }
                let _ = reply.send(slot);
            }
            RaceCmd::Leave { slot } => {
                if self.members.remove(&slot).is_some() {
                    self.room.remove_player(slot);
                    self.lobby_changed = true;
                }
            }
            RaceCmd::Ready { slot, ready } => {
                if self.members.contains_key(&slot) {
                    self.room.set_ready(slot, ready);
                    self.lobby_changed = true;
                }
            }
            RaceCmd::Settings { slot, settings } => {
                if self.room.host() == Some(slot) {
                    self.room.set_settings(settings);
                    self.lobby_changed = true;
                }
            }
            RaceCmd::Rematch { slot } => {
                if self.room.host() == Some(slot) && self.room.phase == Phase::Over {
                    self.room.rematch();
                    for m in self.members.values_mut() {
                        m.inputs.clear();
                    }
                    self.events.clear();
                    self.broadcast(&ServerMsg::Rematch);
                    self.lobby_changed = true;
                }
            }
            RaceCmd::Inputs { slot, last, inputs } => {
                if let Some(m) = self.members.get_mut(&slot) {
                    m.inputs.receive(last, &inputs);
                }
            }
        }
    }

    fn tick(&mut self) {
        for (&slot, m) in self.members.iter_mut() {
            self.room.set_input(slot, m.inputs.next());
        }
        let phase = self.room.phase;
        self.room.step();
        self.events.extend(self.room.take_events());
        if phase == Phase::Lobby && self.room.phase != Phase::Lobby {
            // the race is starting: everyone's controls start fresh
            for m in self.members.values_mut() {
                m.inputs.clear();
            }
            self.lobby_changed = true;
        }
        if self.lobby_changed {
            self.lobby_changed = false;
            self.send_lobby();
        }
        if self.room.phase != Phase::Lobby && self.room.tick.is_multiple_of(SNAPSHOT_EVERY) {
            self.send_snapshot();
        }
        self.update_status();
    }

    /// what the hub shows of this room (the public room list)
    fn update_status(&self) {
        let mut s = self.status.lock().unwrap();
        s.players = self.members.len() as u8;
        s.joinable = self.room.joinable();
    }

    fn send_lobby(&self) {
        let lobby = Lobby {
            players: self
                .room
                .humans()
                .map(|(slot, r)| LobbyPlayer { slot, name: r.name.clone(), car: r.car_number, ready: r.ready })
                .collect(),
            host: self.room.host(),
            settings: self.room.settings,
        };
        self.broadcast(&ServerMsg::Lobby(lobby));
    }

    fn send_snapshot(&mut self) {
        let snapshot = Box::new(self.room.snapshot());
        let events = std::mem::take(&mut self.events);
        for m in self.members.values() {
            let msg = ServerMsg::Race { snapshot: snapshot.clone(), events: events.clone(), ack: m.inputs.applied };
            // a player whose connection can't keep up misses a snapshot; the next one has it all
            let _ = m.out.try_send(encode(&msg));
        }
    }

    fn broadcast(&self, msg: &ServerMsg) {
        let bytes = encode(msg);
        for m in self.members.values() {
            let _ = m.out.try_send(bytes.clone());
        }
    }
}
