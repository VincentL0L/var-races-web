//! The registry of open rooms: room codes, the public race list.
//! Each room runs in its own task; the hub only keeps a handle and a little status for each.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use proto::Visibility;
use rand::Rng;
use tokio::sync::mpsc;

use crate::race::RaceCmd;

/// letters that can't be mistaken for each other (no I or O)
const CODE_LETTERS: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ";
pub const MAX_RACE_ROOMS: usize = 500;

/// what the hub knows about a race room (kept up to date by the room)
#[derive(Clone, Debug)]
pub struct RaceStatus {
    pub visibility: Visibility,
    pub map: &'static str,
    pub players: u8,
    pub joinable: bool,
}

#[derive(Clone)]
pub struct RaceHandle {
    pub tx: mpsc::Sender<RaceCmd>,
    pub status: Arc<Mutex<RaceStatus>>,
}

pub struct Hub {
    races: Mutex<HashMap<String, RaceHandle>>,
}

impl Hub {
    pub fn new() -> Arc<Hub> {
        Arc::new(Hub { races: Default::default() })
    }

    pub fn race(&self, code: &str) -> Option<RaceHandle> {
        self.races.lock().unwrap().get(&code.to_ascii_uppercase()).cloned()
    }

    /// Registers a race under a fresh four-letter code, unless the server is full.
    pub fn add_race(&self, handle: impl FnOnce(String) -> RaceHandle) -> Option<(String, RaceHandle)> {
        let mut rooms = self.races.lock().unwrap();
        if rooms.len() >= MAX_RACE_ROOMS {
            return None;
        }
        let code = fresh_code(4, |c| rooms.contains_key(c));
        let h = handle(code.clone());
        rooms.insert(code.clone(), h.clone());
        Some((code, h))
    }

    pub fn remove_race(&self, code: &str) {
        self.races.lock().unwrap().remove(code);
    }

    /// Public races that can still be joined.
    pub fn public_races(&self) -> Vec<proto::RoomInfo> {
        let rooms = self.races.lock().unwrap();
        let mut list: Vec<proto::RoomInfo> = rooms
            .iter()
            .filter_map(|(code, h)| {
                let s = h.status.lock().unwrap();
                (s.visibility == Visibility::Public && s.joinable && s.players > 0).then(|| proto::RoomInfo {
                    code: code.clone(),
                    map: s.map.to_string(),
                    players: s.players,
                })
            })
            .collect();
        list.sort_by(|a, b| a.code.cmp(&b.code));
        list
    }

    pub fn counts(&self) -> usize {
        self.races.lock().unwrap().len()
    }
}

fn fresh_code(len: usize, taken: impl Fn(&str) -> bool) -> String {
    let mut rng = rand::rng();
    loop {
        let code: String = (0..len).map(|_| CODE_LETTERS[rng.random_range(0..CODE_LETTERS.len())] as char).collect();
        if !taken(&code) {
            return code;
        }
    }
}
