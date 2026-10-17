//! The registry of open rooms: room codes, the public race list and ranked matchmaking.
//! Each room runs in its own task; the hub only keeps a handle and a little status for each.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use proto::Visibility;
use rand::Rng;
use tokio::sync::mpsc;

use crate::accounts::Accounts;
use crate::race::RaceCmd;
use crate::soccer::SoccerCmd;

/// letters that can't be mistaken for each other (no I or O)
const CODE_LETTERS: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ";
pub const MAX_RACE_ROOMS: usize = 500;
pub const MAX_SOCCER_ROOMS: usize = 128;

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

#[derive(Clone, Debug)]
pub struct SoccerStatus {
    pub team_size: u8,
    pub players: u8,
    pub waiting: bool,
}

#[derive(Clone)]
pub struct SoccerHandle {
    pub tx: mpsc::Sender<SoccerCmd>,
    pub status: Arc<Mutex<SoccerStatus>>,
}

pub struct Hub {
    pub accounts: Accounts,
    races: Mutex<HashMap<String, RaceHandle>>,
    soccer: Mutex<HashMap<String, SoccerHandle>>,
}

impl Hub {
    pub fn new(accounts: Accounts) -> Arc<Hub> {
        Arc::new(Hub { accounts, races: Default::default(), soccer: Default::default() })
    }

    pub fn race(&self, code: &str) -> Option<RaceHandle> {
        self.races.lock().unwrap().get(&code.to_ascii_uppercase()).cloned()
    }

    pub fn soccer(&self, code: &str) -> Option<SoccerHandle> {
        self.soccer.lock().unwrap().get(&code.to_ascii_uppercase()).cloned()
    }

    /// registers a new race room under a fresh 4-letter code (None when the server is full)
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

    pub fn add_soccer(&self, handle: impl FnOnce(String) -> SoccerHandle) -> Option<(String, SoccerHandle)> {
        let mut rooms = self.soccer.lock().unwrap();
        if rooms.len() >= MAX_SOCCER_ROOMS {
            return None;
        }
        let code = fresh_code(5, |c| rooms.contains_key(c));
        let h = handle(code.clone());
        rooms.insert(code.clone(), h.clone());
        Some((code, h))
    }

    pub fn remove_race(&self, code: &str) {
        self.races.lock().unwrap().remove(code);
    }

    pub fn remove_soccer(&self, code: &str) {
        self.soccer.lock().unwrap().remove(code);
    }

    /// public races that can still be joined
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

    /// a ranked room still taking players, the fullest first
    pub fn open_ranked(&self) -> Option<RaceHandle> {
        let rooms = self.races.lock().unwrap();
        rooms
            .values()
            .filter(|h| {
                let s = h.status.lock().unwrap();
                s.visibility == Visibility::Ranked && s.joinable
            })
            .max_by_key(|h| h.status.lock().unwrap().players)
            .cloned()
    }

    pub fn soccer_rooms(&self) -> Vec<proto::SoccerRoomInfo> {
        let rooms = self.soccer.lock().unwrap();
        let mut list: Vec<proto::SoccerRoomInfo> = rooms
            .iter()
            .filter_map(|(code, h)| {
                let s = h.status.lock().unwrap();
                s.waiting.then(|| proto::SoccerRoomInfo { code: code.clone(), team_size: s.team_size, players: s.players })
            })
            .collect();
        list.sort_by(|a, b| a.code.cmp(&b.code));
        list
    }

    pub fn counts(&self) -> (usize, usize) {
        (self.races.lock().unwrap().len(), self.soccer.lock().unwrap().len())
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
