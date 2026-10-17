//! The simulation and the protocol for the browser. The game's TypeScript gets:
//!  - the maps and cars (`maps`, `cars`, `mapLayout`)
//!  - `LocalRace`: a whole race run in the browser (single player and the title screen), the
//!    very same room the server runs online
//!  - `RacePredictor` / `SoccerPredictor`: the player's own car run ahead of the server
//!  - `encode` / `decode`: the binary messages to and from the server
//!
//! Values cross into JavaScript as plain objects; their TypeScript types are generated from
//! the Rust types (see the `ts` feature of sim and proto).

use std::collections::VecDeque;

use serde::Serialize;
use sim::car::{self, Car, Input, Surface};
use sim::maps::{self, MapDef};
use sim::room::{RaceRoom, Settings, DT};
use sim::track::Track;
use wasm_bindgen::prelude::*;

fn to_js<T: Serialize + ?Sized>(value: &T) -> JsValue {
    let s = serde_wasm_bindgen::Serializer::new().serialize_maps_as_objects(true);
    value.serialize(&s).unwrap_or(JsValue::NULL)
}

fn input(packed: u16) -> Input {
    Input::unpack([packed as u8, (packed >> 8) as u8])
}

/// every map, in the order the picker shows them
#[wasm_bindgen]
pub fn maps() -> JsValue {
    to_js(&maps::ALL[..])
}

/// what the track of a map looks like: its walls, turn boards and item boxes
#[derive(Serialize)]
struct Layout {
    walls: Vec<sim::track::Wall>,
    signs: Vec<sim::track::Sign>,
    boxes: Vec<[sim::math::Vec2; 3]>,
}

#[wasm_bindgen(js_name = mapLayout)]
pub fn map_layout(id: &str) -> JsValue {
    let map = maps::get(id);
    let track = maps::track(map);
    to_js(&Layout { walls: track.walls(), signs: track.signs(map), boxes: sim::items::box_rows(map) })
}

/// a garage car, with its ratings out of 10 for the stat bars
#[derive(Serialize)]
struct CarInfo {
    id: &'static str,
    name: &'static str,
    tagline: &'static str,
    mass: f32,
    speed: u8,
    accel: u8,
    handling: u8,
    weight: u8,
}

fn rate(value: f32, low: f32, high: f32) -> u8 {
    (1.0 + 9.0 * (value - low) / (high - low)).round().clamp(1.0, 10.0) as u8
}

#[wasm_bindgen]
pub fn cars() -> JsValue {
    let list: Vec<CarInfo> = car::MODELS
        .iter()
        .map(|m| {
            let s = m.spec;
            CarInfo {
                id: m.id,
                name: m.name,
                tagline: m.tagline,
                mass: s.mass,
                // top speed: power and air drag balance at v = cbrt(power / drag)
                speed: rate(s.power.cbrt(), 0.94, 1.1),
                accel: rate(s.traction * 0.7 + s.power.cbrt() * 0.3, 0.78, 1.3),
                handling: rate(s.handling, 0.75, 1.2),
                weight: rate(s.mass, 0.7, 2.0),
            }
        })
        .collect();
    to_js(&list)
}

/// a ClientMsg (as a plain object) to send to the server
#[wasm_bindgen]
pub fn encode(msg: JsValue) -> Result<Vec<u8>, JsError> {
    let msg: proto::ClientMsg = serde_wasm_bindgen::from_value(msg)?;
    Ok(proto::encode(&msg))
}

/// a ServerMsg from the server, as a plain object (null if it can't be read)
#[wasm_bindgen]
pub fn decode(bytes: &[u8]) -> JsValue {
    proto::decode_server(bytes).map_or(JsValue::NULL, |m| to_js(&m))
}

#[wasm_bindgen(js_name = protocolVersion)]
pub fn protocol_version() -> u16 {
    proto::VERSION
}

/// A race run in the browser: single player, or the title screen's endless CPU race.
#[wasm_bindgen]
pub struct LocalRace {
    room: RaceRoom,
}

#[wasm_bindgen]
impl LocalRace {
    #[wasm_bindgen(constructor)]
    pub fn new(map: &str, seed: u32) -> LocalRace {
        LocalRace { room: RaceRoom::new(maps::get(map), seed as u64) }
    }

    /// the title screen's endless race of `cars` CPUs
    pub fn demo(map: &str, seed: u32, cars: usize) -> LocalRace {
        LocalRace { room: RaceRoom::demo(maps::get(map), seed as u64, cars) }
    }

    #[wasm_bindgen(js_name = addPlayer)]
    pub fn add_player(&mut self, name: &str, car: u8) -> Option<u8> {
        self.room.add_player(name, car)
    }

    #[wasm_bindgen(js_name = setSettings)]
    pub fn set_settings(&mut self, laps: u8, difficulty: u8) {
        self.room.set_settings(Settings { laps, difficulty });
    }

    pub fn start(&mut self) {
        self.room.start();
    }

    pub fn rematch(&mut self) {
        self.room.rematch();
    }

    /// one 60 Hz tick, with this player's packed controls
    pub fn step(&mut self, slot: u8, controls: u16) {
        self.room.set_input(slot, input(controls));
        self.room.step();
    }

    pub fn snapshot(&self) -> JsValue {
        to_js(&self.room.snapshot())
    }

    /// what happened since the last call
    #[wasm_bindgen(js_name = takeEvents)]
    pub fn take_events(&mut self) -> JsValue {
        to_js(&self.room.take_events())
    }

    /// the names of everyone in the race, by slot (null for empty slots)
    pub fn names(&self) -> JsValue {
        let names: Vec<Option<&str>> = self.room.racers.iter().map(|r| r.as_ref().map(|r| r.name.as_str())).collect();
        to_js(&names)
    }

    pub fn laps(&self) -> u8 {
        self.room.settings.laps
    }
}

/// The player's own race car, run ahead of the server: each tick it drives on the player's
/// controls straight away; when a snapshot arrives it starts again from the server's car and
/// replays the controls the server hasn't applied yet.
#[wasm_bindgen]
pub struct RacePredictor {
    map: &'static MapDef,
    track: Track,
    car: Option<Car>,
    pending: VecDeque<(u32, Input)>,
}

#[wasm_bindgen]
impl RacePredictor {
    #[wasm_bindgen(constructor)]
    pub fn new(map: &str) -> RacePredictor {
        let map = maps::get(map);
        RacePredictor { map, track: maps::track(map), car: None, pending: VecDeque::new() }
    }

    fn surface(&self) -> Surface {
        Surface { grip: self.map.grip, traction: self.map.traction }
    }

    /// the server's car after it applied input number `ack`
    pub fn reconcile(&mut self, car: JsValue, ack: u32) -> Result<(), JsError> {
        let mut car: Car = serde_wasm_bindgen::from_value(car)?;
        while self.pending.front().is_some_and(|&(seq, _)| seq <= ack) {
            self.pending.pop_front();
        }
        let surface = self.surface();
        for (_, i) in &self.pending {
            car.tick(DT, i, &self.track, surface);
        }
        self.car = Some(car);
        Ok(())
    }

    /// drives one tick on input number `seq` (only while racing)
    pub fn predict(&mut self, seq: u32, controls: u16) {
        let i = input(controls);
        self.pending.push_back((seq, i));
        while self.pending.len() > 120 {
            self.pending.pop_front();
        }
        let surface = self.surface();
        if let Some(car) = self.car.as_mut() {
            car.tick(DT, &i, &self.track, surface);
        }
    }

    pub fn car(&self) -> JsValue {
        self.car.as_ref().map_or(JsValue::NULL, to_js)
    }

    pub fn reset(&mut self) {
        self.car = None;
        self.pending.clear();
    }
}

/// the same for the player's own soccer car
#[wasm_bindgen]
pub struct SoccerPredictor {
    car: Option<sim::soccer::SoccerCar>,
    pending: VecDeque<(u32, Input)>,
}

#[wasm_bindgen]
impl SoccerPredictor {
    #[wasm_bindgen(constructor)]
    pub fn new() -> SoccerPredictor {
        SoccerPredictor { car: None, pending: VecDeque::new() }
    }

    pub fn reconcile(&mut self, car: JsValue, ack: u32) -> Result<(), JsError> {
        let mut car: sim::soccer::SoccerCar = serde_wasm_bindgen::from_value(car)?;
        while self.pending.front().is_some_and(|&(seq, _)| seq <= ack) {
            self.pending.pop_front();
        }
        for (_, i) in &self.pending {
            car.predict(i, DT);
        }
        self.car = Some(car);
        Ok(())
    }

    pub fn predict(&mut self, seq: u32, controls: u16) {
        let i = input(controls);
        self.pending.push_back((seq, i));
        while self.pending.len() > 120 {
            self.pending.pop_front();
        }
        if let Some(car) = self.car.as_mut() {
            car.predict(&i, DT);
        }
    }

    pub fn car(&self) -> JsValue {
        self.car.as_ref().map_or(JsValue::NULL, to_js)
    }

    pub fn reset(&mut self) {
        self.car = None;
        self.pending.clear();
    }
}

impl Default for SoccerPredictor {
    fn default() -> Self {
        Self::new()
    }
}

/// A soccer match against the CPU, run in the browser (single player): we drive slot 0
/// (blue), CPU drivers take every other seat.
#[wasm_bindgen]
pub struct LocalSoccer {
    m: sim::soccer::SoccerMatch,
}

#[wasm_bindgen]
impl LocalSoccer {
    #[wasm_bindgen(constructor)]
    pub fn new(team_size: u8) -> LocalSoccer {
        let mut m = sim::soccer::SoccerMatch::new(team_size);
        for slot in 0..m.cars.len() as u8 {
            m.add(slot);
        }
        LocalSoccer { m }
    }

    /// one tick: our controls, the CPUs' choices, then the match moves on
    pub fn step(&mut self, controls: u16) {
        self.m.input(0, &input(controls));
        for slot in 1..self.m.cars.len() as u8 {
            let i = sim::soccer::cpu_input(&self.m, slot);
            self.m.input(slot, &i);
        }
        self.m.step(DT);
    }

    pub fn rematch(&mut self) {
        self.m.restart();
    }

    /// the match, in the same shape the server sends
    pub fn state(&self) -> JsValue {
        to_js(&self.m)
    }
}
