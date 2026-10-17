//! The messages between the game and the server: binary WebSocket frames, each one
//! postcard-encoded `ClientMsg` or `ServerMsg`. Race rooms share one connection.
//!
//! In a race the server is in charge of every car. Each client sends its controls once per
//! 60 Hz tick (with the previous few repeated, so a lost packet costs nothing), and the server
//! sends a snapshot of the race 30 times a second, acknowledging the last input it applied so
//! the client can predict its own car from there.

use serde::{Deserialize, Serialize};
use sim::events::Event;
use sim::room::{Settings, Snapshot};

/// bumped whenever a message changes shape; the server turns away other versions
pub const VERSION: u16 = 3;
/// inputs repeated in every Inputs message
pub const INPUT_REDUNDANCY: usize = 4;
/// largest message a client may send
pub const MAX_CLIENT_FRAME: usize = 4096;

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Visibility {
    Public,
    Private,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum ClientMsg {
    Hello { version: u16 },
    /// the public races waiting for players
    ListRooms,
    Create { visibility: Visibility, map: String, car: u8 },
    Join { code: String, car: u8 },
    Ready(bool),
    /// the host's laps and CPU difficulty
    Settings(Settings),
    /// back to the lobby for another race (host only, once the race is over)
    Rematch,
    Leave,
    /// controls for consecutive ticks, oldest first; `last` numbers the newest
    Inputs { last: u32, inputs: Vec<[u8; 2]> },
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct RoomInfo {
    pub code: String,
    pub map: String,
    pub players: u8,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct LobbyPlayer {
    pub slot: u8,
    pub name: String,
    pub car: u8,
    pub ready: bool,
}

/// who's in a race room before the start
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Lobby {
    pub players: Vec<LobbyPlayer>,
    pub host: Option<u8>,
    pub settings: Settings,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum ServerMsg {
    Welcome { version: u16 },
    Error(String),
    Rooms(Vec<RoomInfo>),
    Joined { code: String, visibility: Visibility, slot: u8, map: String },
    Lobby(Lobby),
    /// the host started another race in the same room: back to the lobby
    Rematch,
    /// one race tick: the state, what happened since the last one, and the last input applied
    Race { snapshot: Box<Snapshot>, events: Vec<Event>, ack: u32 },
}

pub fn encode<T: Serialize>(msg: &T) -> Vec<u8> {
    postcard::to_allocvec(msg).expect("messages always encode")
}

pub fn decode_client(bytes: &[u8]) -> Option<ClientMsg> {
    if bytes.len() > MAX_CLIENT_FRAME {
        return None;
    }
    postcard::from_bytes(bytes).ok()
}

pub fn decode_server(bytes: &[u8]) -> Option<ServerMsg> {
    postcard::from_bytes(bytes).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use sim::maps;
    use sim::room::RaceRoom;

    #[test]
    fn messages_round_trip() {
        let msg = ClientMsg::Inputs { last: 77, inputs: vec![[1, 2], [3, 4]] };
        assert_eq!(decode_client(&encode(&msg)), Some(msg));
        assert_eq!(decode_client(&[255, 255, 255]), None);
    }

    #[test]
    fn a_race_snapshot_is_small() {
        let mut room = RaceRoom::new(maps::get("classic"), 1);
        for i in 0..6 {
            room.add_player(&format!("Player {i}"), 1);
        }
        room.start();
        for _ in 0..600 {
            room.step();
        }
        let msg = ServerMsg::Race { snapshot: Box::new(room.snapshot()), events: room.take_events(), ack: 1000 };
        let bytes = encode(&msg);
        assert_eq!(decode_server(&bytes), Some(msg));
        // 9 cars: about 1 KB, 30 times a second
        assert!(bytes.len() < 1200, "{} bytes", bytes.len());
    }
}
