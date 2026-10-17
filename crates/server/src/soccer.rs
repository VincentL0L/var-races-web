//! One Race Car Soccer room's task: it owns a sim::soccer::Match, ticks it at 60 Hz with the
//! players' inputs and sends everyone the match 30 times a second.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use proto::{encode, ServerMsg};
use sim::room::{DT, TICK_RATE};
use sim::soccer::{SoccerMatch, SoccerPhase};
use tokio::sync::{mpsc, oneshot};
use tokio::time::MissedTickBehavior;

use crate::hub::{Hub, SoccerHandle, SoccerStatus};
use crate::inputs::InputQueue;
use crate::race::Out;

const SNAPSHOT_EVERY: u64 = 2;
const MAX_AGE: Duration = Duration::from_secs(2 * 3600);

pub enum SoccerCmd {
    Join { out: Out, reply: oneshot::Sender<Result<u8, &'static str>> },
    Leave { slot: u8 },
    Rematch { slot: u8 },
    Inputs { slot: u8, last: u32, inputs: Vec<[u8; 2]> },
}

struct Member {
    out: Out,
    inputs: InputQueue,
    /// join order: the longest-standing player hosts (starts rematches)
    joined: u32,
}

struct Task {
    hub: Arc<Hub>,
    code: String,
    game: SoccerMatch,
    members: BTreeMap<u8, Member>,
    status: Arc<Mutex<SoccerStatus>>,
    next_join: u32,
    tick: u64,
}

/// opens a 1v1 or 2v2 room and starts its task
pub fn spawn(hub: &Arc<Hub>, team_size: u8) -> Option<(String, SoccerHandle)> {
    let team_size = team_size.clamp(1, 2);
    let (tx, rx) = mpsc::channel(256);
    let status = Arc::new(Mutex::new(SoccerStatus { team_size, players: 0, waiting: true }));
    let (code, handle) = hub.add_soccer(|_| SoccerHandle { tx, status: status.clone() })?;
    let task = Task {
        hub: hub.clone(),
        code: code.clone(),
        game: SoccerMatch::new(team_size),
        members: BTreeMap::new(),
        status,
        next_join: 0,
        tick: 0,
    };
    tokio::spawn(task.run(rx));
    Some((code, handle))
}

impl Task {
    async fn run(mut self, mut rx: mpsc::Receiver<SoccerCmd>) {
        let mut interval = tokio::time::interval(Duration::from_secs_f64(1.0 / TICK_RATE as f64));
        interval.set_missed_tick_behavior(MissedTickBehavior::Burst);
        let born = tokio::time::Instant::now();
        tracing::info!(code = %self.code, size = self.game.team_size, "soccer room opened");
        loop {
            tokio::select! {
                cmd = rx.recv() => match cmd {
                    Some(cmd) => self.handle(cmd),
                    None => break,
                },
                _ = interval.tick() => {
                    self.step();
                    if self.members.is_empty() || born.elapsed() > MAX_AGE {
                        break;
                    }
                }
            }
        }
        self.hub.remove_soccer(&self.code);
        tracing::info!(code = %self.code, "soccer room closed");
    }

    fn host(&self) -> Option<u8> {
        self.members.iter().min_by_key(|(_, m)| m.joined).map(|(&slot, _)| slot)
    }

    fn handle(&mut self, cmd: SoccerCmd) {
        self.apply(cmd);
        self.update_status();
    }

    fn apply(&mut self, cmd: SoccerCmd) {
        match cmd {
            SoccerCmd::Join { out, reply } => {
                let Some(slot) = self.game.free_slot() else {
                    let _ = reply.send(Err("Room is full"));
                    return;
                };
                self.game.add(slot);
                let joined = ServerMsg::SoccerJoined { code: self.code.clone(), slot };
                let _ = out.try_send(encode(&joined));
                self.members.insert(slot, Member { out, inputs: InputQueue::default(), joined: self.next_join });
                self.next_join += 1;
                let _ = reply.send(Ok(slot));
            }
            SoccerCmd::Leave { slot } => {
                if self.members.remove(&slot).is_some() {
                    self.game.remove(slot);
                }
            }
            SoccerCmd::Rematch { slot } => {
                if self.host() == Some(slot) && self.game.phase == SoccerPhase::Finished {
                    self.game.restart();
                }
            }
            SoccerCmd::Inputs { slot, last, inputs } => {
                if let Some(m) = self.members.get_mut(&slot) {
                    m.inputs.receive(last, &inputs);
                }
            }
        }
    }

    fn step(&mut self) {
        self.tick += 1;
        for (&slot, m) in self.members.iter_mut() {
            let input = m.inputs.next();
            self.game.input(slot, &input);
        }
        self.game.step(DT);
        self.update_status();
        if self.tick.is_multiple_of(SNAPSHOT_EVERY) {
            let state = Box::new(self.game.clone());
            let host = self.host();
            for m in self.members.values() {
                let msg = ServerMsg::Soccer { state: state.clone(), host, ack: m.inputs.applied };
                let _ = m.out.try_send(encode(&msg));
            }
        }
    }

    fn update_status(&self) {
        let mut s = self.status.lock().unwrap();
        s.players = self.members.len() as u8;
        s.waiting = self.game.phase == SoccerPhase::Waiting && !self.game.full();
    }
}
