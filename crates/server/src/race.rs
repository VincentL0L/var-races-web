//! One race room's task: it owns a sim::room::RaceRoom, ticks it at 60 Hz with the players'
//! inputs, sends everyone a snapshot 30 times a second, runs the ranked queue and rates
//! ranked results.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use proto::{encode, Lobby, LobbyPlayer, RankResult, ServerMsg, Visibility};
use sim::events::Event;
use sim::maps::{self, MapDef, MapKind};
use sim::room::{Phase, RaceRoom, Settings, MAX_PLAYERS, TICK_RATE};
use tokio::sync::{mpsc, oneshot};
use tokio::time::MissedTickBehavior;

use crate::accounts::{self, Profile};
use crate::hub::{Hub, RaceHandle, RaceStatus};
use crate::inputs::InputQueue;

/// a player's outgoing messages (the connection's writer)
pub type Out = mpsc::Sender<Vec<u8>>;

/// ranked: seconds to wait once two players are in, and how long one player waits alone
const RANKED_WAIT: f32 = 20.0;
const SOLO_WAIT: f32 = 45.0;
const FULL_WAIT: f32 = 3.0;
/// a snapshot every this many ticks (30 Hz)
const SNAPSHOT_EVERY: u64 = 2;
/// rooms close after this long, whatever is going on (nobody races for two hours)
const MAX_AGE: Duration = Duration::from_secs(2 * 3600);

pub enum RaceCmd {
    Join { name: Option<String>, uid: Option<String>, car: u8, out: Out, reply: oneshot::Sender<Option<u8>> },
    Leave { slot: u8 },
    Ready { slot: u8, ready: bool },
    Settings { slot: u8, settings: Settings },
    Rematch { slot: u8 },
    Inputs { slot: u8, last: u32, inputs: Vec<[u8; 2]> },
}

struct Member {
    out: Out,
    uid: Option<String>,
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
    // ranked queue
    queue_time: f32,
    full_time: f32,
    last_queue_second: Option<u8>,
    rated: bool,
}

/// opens a race room on `map` and starts its task
pub fn spawn(hub: &Arc<Hub>, map: &'static MapDef, visibility: Visibility) -> Option<(String, RaceHandle)> {
    let (tx, rx) = mpsc::channel(256);
    let status = Arc::new(Mutex::new(RaceStatus { visibility, map: map.id, players: 0, joinable: true }));
    let (code, handle) = hub.add_race(|_| RaceHandle { tx, status: status.clone() })?;
    let mut room = RaceRoom::new(map, rand::random());
    if visibility == Visibility::Ranked {
        // ranked races start by themselves, with fixed settings
        room.auto_start = false;
        room.set_settings(Settings { laps: if map.has_laps() { 2 } else { 1 }, difficulty: 1 });
    }
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
        queue_time: 0.0,
        full_time: 0.0,
        last_queue_second: None,
        rated: false,
    };
    tokio::spawn(task.run(rx));
    Some((code, handle))
}

/// a random map for a ranked race (any race, no battle arenas)
pub fn ranked_map() -> &'static MapDef {
    let races: Vec<&'static MapDef> = maps::ALL.iter().filter(|m| m.kind != MapKind::Battle).collect();
    races[rand::random_range(0..races.len())]
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
            RaceCmd::Join { name, uid, car, out, reply } => {
                let name = name.unwrap_or_else(|| {
                    self.guests += 1;
                    format!("Player {}", self.guests)
                });
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
                    self.members.insert(slot, Member { out, uid, inputs: InputQueue::default() });
                    self.lobby_changed = true;
                    // a new ranked player resets the "everyone's in" wait
                    self.last_queue_second = None;
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
                if self.visibility != Visibility::Ranked && self.room.host() == Some(slot) {
                    self.room.set_settings(settings);
                    self.lobby_changed = true;
                }
            }
            RaceCmd::Rematch { slot } => {
                if self.visibility != Visibility::Ranked && self.room.host() == Some(slot) && self.room.phase == Phase::Over {
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
        let dt = sim::room::DT;
        for (&slot, m) in self.members.iter_mut() {
            self.room.set_input(slot, m.inputs.next());
        }
        if self.visibility == Visibility::Ranked {
            self.tick_queue(dt);
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
        if self.room.phase == Phase::Over && self.visibility == Visibility::Ranked && !self.rated {
            self.rated = true;
            self.rate();
        }
        self.update_status();
    }

    /// what the hub shows of this room (the room list, ranked matchmaking)
    fn update_status(&self) {
        let mut s = self.status.lock().unwrap();
        s.players = self.members.len() as u8;
        s.joinable = self.room.joinable();
    }

    /// the ranked queue: counts down and starts the race by itself
    fn tick_queue(&mut self, dt: f32) {
        let players = self.members.len();
        if self.room.phase != Phase::Lobby || players == 0 {
            return;
        }
        self.queue_time += dt;
        self.full_time = if players >= 2 { self.full_time + dt } else { 0.0 };
        let mut left = if players >= 2 { RANKED_WAIT - self.full_time } else { SOLO_WAIT - self.queue_time };
        if players >= MAX_PLAYERS {
            left = left.min(FULL_WAIT);
        }
        let second = left.max(0.0).ceil() as u8;
        if self.last_queue_second != Some(second) {
            self.last_queue_second = Some(second);
            self.broadcast(&ServerMsg::Queue { players: players as u8, seconds: second });
        }
        if left <= 0.0 {
            self.room.start();
        }
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

    /// a finished ranked race: new ratings for the signed-in players, saved and sent to them
    fn rate(&self) {
        let order = self.room.order();
        let finishers: Vec<(String, String, Out)> = order
            .iter()
            .filter_map(|slot| {
                let m = self.members.get(slot)?;
                let uid = m.uid.clone()?;
                Some((uid, self.room.racer(*slot)?.name.clone(), m.out.clone()))
            })
            .collect();
        let hub = self.hub.clone();
        tokio::spawn(async move { rate(hub, finishers).await });
    }
}

async fn rate(hub: Arc<Hub>, finishers: Vec<(String, String, Out)>) {
    if finishers.len() < 2 {
        let msg = encode(&ServerMsg::RankResult(RankResult { old: 0, new: 0, tier: String::new(), place: 1, rated: false }));
        for (_, _, out) in &finishers {
            let _ = out.send(msg.clone()).await;
        }
        return;
    }
    let profiles: Vec<Profile> = {
        let cache = hub.accounts.profiles.lock().unwrap();
        finishers.iter().map(|(uid, name, _)| cache.get(uid).cloned().unwrap_or_else(|| Profile::new(name))).collect()
    };
    let old: Vec<i32> = profiles.iter().map(|p| p.rating).collect();
    let new = accounts::new_ratings(&old);
    for (i, ((uid, _, out), mut p)) in finishers.into_iter().zip(profiles).enumerate() {
        p.rating = new[i];
        p.races += 1;
        if i == 0 {
            p.wins += 1;
        }
        hub.accounts.profiles.lock().unwrap().insert(uid.clone(), p.clone());
        if let Err(e) = hub.accounts.save(&uid, &p).await {
            tracing::warn!("saving a rating: {e}");
        }
        let tier = accounts::tier(p.rating).to_string();
        let result = RankResult { old: old[i], new: p.rating, tier: tier.clone(), place: i as u8 + 1, rated: true };
        let _ = out.send(encode(&ServerMsg::RankResult(result))).await;
        let profile =
            proto::Profile { name: p.name.clone(), rating: p.rating, races: p.races, wins: p.wins, tier, ranked_on: true };
        let _ = out.send(encode(&ServerMsg::Profile(profile))).await;
    }
}
