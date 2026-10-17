//! One race: its players, optional CPU opponents, the lobby, the countdown, the race and the result.
//! The server runs one of these per online room; single player runs exactly the same thing in
//! the browser; the title screen runs an endless one with only CPUs.
//!
//! The room ticks at a fixed 60 Hz. Cars are driven only by their inputs (humans) or their
//! drivers (CPUs), so everything that happens follows from the seed and the inputs.

use serde::{Deserialize, Serialize};

use crate::ai::{Driver, Other};
use crate::battle::{Battle, Body};
use crate::car::{self, Car, Input, Surface};
use crate::events::{Effect, Event};
use crate::items::{self, Hit, Items, Racer as ItemRacer};
use crate::maps::{self, MapDef, MapKind};
use crate::race::{self, Course, Standing};
use crate::rng::Rng;
use crate::track::Track;

pub const TICK_RATE: u32 = 60;
pub const DT: f32 = 1.0 / TICK_RATE as f32;
pub const CPUS: usize = 3; // reserved CPU slots; actual count is a setting
pub const MAX_PLAYERS: usize = 6;
pub const SLOTS: usize = CPUS + MAX_PLAYERS;
/// seconds the red VAR RACES flag waves after everyone is ready, before the 3 2 1 countdown
pub const FLAG_TIME: f32 = 2.15;
pub const COUNTDOWN_TIME: f32 = 3.0;
/// the race ends this long after the first player finishes, even if others haven't
pub const FINISH_GRACE: f32 = 60.0;
/// collision passes per tick, so chains of touching cars settle
const COLLISION_PASSES: usize = 4;

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Phase {
    /// waiting for everyone to be ready
    Lobby,
    /// the flag waves
    Flag,
    /// 3, 2, 1
    Countdown,
    Racing,
    /// everyone finished (or the battle is over)
    Over,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    pub laps: u8,
    /// 0 easy, 1 normal, 2 hard
    pub difficulty: u8,
    pub cpu_count: u8,
}

impl Default for Settings {
    fn default() -> Settings {
        Settings { laps: 1, difficulty: 1, cpu_count: 0 }
    }
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Racer {
    pub name: String,
    /// the garage car number (CPUs: 0, they use the map's CPU sprite)
    pub car_number: u8,
    pub cpu: Option<Driver>,
    pub ready: bool,
    pub car: Car,
    pub input: Input,
    /// the last tick's item button, so holding it uses one item per press
    item_down: bool,
    pub standing: Standing,
    /// when this player joined, to pick their grid spot and the host
    joined: u32,
}

impl Racer {
    pub fn is_cpu(&self) -> bool {
        self.cpu.is_some()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoomMode { Solo, Multiplayer, Demo }

#[derive(Clone, Debug)]
pub struct RaceRoom {
    pub map: &'static MapDef,
    track: Track,
    course: Course,
    rng: Rng,
    pub racers: Vec<Option<Racer>>,
    pub items: Items,
    pub battle: Option<Battle>,
    pub phase: Phase,
    /// seconds into the current phase
    pub phase_time: f32,
    /// seconds since GO
    pub race_time: f32,
    pub tick: u64,
    pub settings: Settings,
    /// title-screen demo: CPUs only, racing forever
    demo: bool,
    mode: RoomMode,
    first_finish: Option<f32>,
    next_join: u32,
    events: Vec<Event>,
    #[cfg(test)]
    contacts: u128,
}

impl RaceRoom {
    pub fn new(map: &'static MapDef, seed: u64) -> RaceRoom {
        let room = RaceRoom {
            map,
            track: maps::track(map),
            course: Course::new(map),
            rng: Rng::new(seed),
            racers: (0..SLOTS).map(|_| None).collect(),
            items: Items::new(map, SLOTS),
            battle: (map.kind == MapKind::Battle).then(|| Battle::new(SLOTS)),
            phase: Phase::Lobby,
            phase_time: 0.0,
            race_time: 0.0,
            tick: 0,
            settings: Settings { laps: 1, difficulty: 1, cpu_count: 0 },
            demo: false,
            mode: RoomMode::Multiplayer,
            first_finish: None,
            next_join: 0,
            events: Vec::new(),
            #[cfg(test)]
            contacts: 0,
        };
        room
    }

    /// Solo time trial: no opponents, item boxes or battle rules.
    pub fn solo(map: &'static MapDef, seed: u64) -> RaceRoom {
        let map = if map.kind == MapKind::Battle { maps::get("classic") } else { map };
        let mut room = Self::new(map, seed);
        room.mode = RoomMode::Solo;
        room.items.boxes.clear();
        room
    }

    /// the title screen's race: `cars` CPUs (1 to 9), already going, lapping forever
    pub fn demo(map: &'static MapDef, seed: u64, cars: usize) -> RaceRoom {
        let mut room = RaceRoom::new(map, seed);
        room.demo = true;
        room.mode = RoomMode::Demo;
        room.items.boxes.clear();
        for i in 0..cars.clamp(1, SLOTS) {
            room.add_cpu(i);
        }
        for r in room.racers.iter_mut().flatten() {
            if let Some(d) = r.cpu.as_mut() {
                d.set_endless(true);
            }
        }
        room.phase = Phase::Racing;
        room
    }

    pub fn track(&self) -> &Track {
        &self.track
    }

    pub fn course(&self) -> &Course {
        &self.course
    }

    /// a CPU in slot `i`, on starting spot `i`
    fn add_cpu(&mut self, i: usize) {
        let driver = Driver::new(self.map, &mut self.rng);
        let spawn = maps::cpu_spawn(self.map, i);
        self.racers[i] = Some(Racer {
            name: self.map.cpu_display_name(i + 1),
            car_number: 0,
            car: Car::new(spawn.pos, spawn.heading, driver.spec()),
            cpu: Some(driver),
            ready: true,
            input: Input::default(),
            item_down: false,
            standing: Standing::default(),
            joined: 0,
        });
        if let Some(b) = self.battle.as_mut() {
            b.join(i as u8);
        }
    }

    pub fn humans(&self) -> impl Iterator<Item = (u8, &Racer)> {
        self.racers.iter().enumerate().filter_map(|(i, r)| r.as_ref().filter(|r| !r.is_cpu()).map(|r| (i as u8, r)))
    }

    pub fn human_count(&self) -> usize {
        self.humans().count()
    }

    pub fn racer(&self, slot: u8) -> Option<&Racer> {
        self.racers.get(slot as usize).and_then(|r| r.as_ref())
    }

    /// true while new players can still join
    pub fn joinable(&self) -> bool {
        self.phase == Phase::Lobby && self.human_count() < if self.mode == RoomMode::Solo { 1 } else { MAX_PLAYERS }
    }

    /// Adds a player with a name nobody else in the room has ("Ana", then "Ana 2"...).
    /// Returns their slot, or None if the room is full or already racing.
    pub fn add_player(&mut self, name: &str, car_number: u8) -> Option<u8> {
        if !self.joinable() {
            return None;
        }
        let slot = (CPUS..SLOTS).find(|&s| self.racers[s].is_none())?;
        let mut unique = name.to_string();
        let mut n = 2;
        while self.racers.iter().flatten().any(|r| r.name == unique) {
            unique = format!("{name} {n}");
            n += 1;
        }
        let model = car::model_of(car_number);
        self.racers[slot] = Some(Racer {
            name: unique,
            car_number,
            cpu: None,
            ready: false,
            car: Car::new(Default::default(), 0.0, model.spec),
            input: Input::default(),
            item_down: false,
            standing: Standing::default(),
            joined: self.next_join,
        });
        self.next_join += 1;
        if let Some(b) = self.battle.as_mut() {
            b.join(slot as u8);
        }
        self.place_on_grid();
        Some(slot as u8)
    }

    /// a player left; mid-race their car simply disappears
    pub fn remove_player(&mut self, slot: u8) {
        if self.racer(slot).is_some_and(|r| !r.is_cpu()) {
            self.racers[slot as usize] = None;
            self.items.clear(slot);
            if let Some(b) = self.battle.as_mut() {
                b.leave(slot);
            }
            if self.phase == Phase::Lobby {
                self.place_on_grid();
                self.check_all_ready();
            }
        }
    }

    /// the player who has been in the room longest picks the settings
    pub fn host(&self) -> Option<u8> {
        self.humans().min_by_key(|(_, r)| r.joined).map(|(slot, _)| slot)
    }

    pub fn set_ready(&mut self, slot: u8, ready: bool) {
        if self.phase != Phase::Lobby {
            return;
        }
        if let Some(r) = self.racers.get_mut(slot as usize).and_then(|r| r.as_mut()) {
            r.ready = ready;
        }
        self.check_all_ready();
    }

    /// the host changes laps or CPU difficulty (lobby only)
    pub fn set_settings(&mut self, settings: Settings) {
        if self.phase != Phase::Lobby { return; }
        let settings = Settings {
            laps: if self.map.has_laps() { settings.laps.clamp(1, 10) } else { 1 },
            difficulty: settings.difficulty.min(3),
            // single player races CPUs too (or, with none, a time trial against a ghost)
            cpu_count: if self.mode != RoomMode::Demo { settings.cpu_count.min(CPUS as u8) } else { 0 },
        };
        if self.settings == settings { return; }
        for i in 0..CPUS {
            if i < settings.cpu_count as usize {
                if self.racers[i].is_none() { self.add_cpu(i); }
            } else {
                self.racers[i] = None;
                self.items.clear(i as u8);
                if let Some(b) = self.battle.as_mut() { b.leave(i as u8); }
            }
        }
        // a solo time trial has no item boxes; racing CPUs, it has them like any race
        if self.mode == RoomMode::Solo {
            if settings.cpu_count > 0 && self.items.boxes.is_empty() {
                self.items = Items::new(self.map, SLOTS);
            } else if settings.cpu_count == 0 {
                self.items.boxes.clear();
            }
        }
        self.settings = settings;
        for racer in self.racers.iter_mut().flatten() {
            if !racer.is_cpu() { racer.ready = false; }
        }
        self.place_on_grid();
    }

    pub fn set_input(&mut self, slot: u8, input: Input) {
        if let Some(r) = self.racers.get_mut(slot as usize).and_then(|r| r.as_mut()) {
            if !r.is_cpu() {
                r.input = input;
            }
        }
    }

    fn check_all_ready(&mut self) {
        if self.human_count() > 0 && self.humans().all(|(_, r)| r.ready) {
            self.start();
        }
    }

    /// Locks in the settings and waves the flag; the countdown follows.
    pub fn start(&mut self) {
        if self.phase != Phase::Lobby || (self.battle.is_some() && self.racers.iter().flatten().count() < 2) {
            return;
        }
        let Settings { laps, difficulty, .. } = self.settings;
        self.course.set_laps(laps);
        for r in self.racers.iter_mut().flatten() {
            if let Some(d) = r.cpu.as_mut() {
                d.set_laps(laps);
                d.set_difficulty(difficulty);
                r.car.spec = d.spec();
            }
        }
        self.place_on_grid();
        self.set_phase(Phase::Flag);
        self.events.push(Event::Flag);
    }

    /// Back to the lobby on the same map for another go: fresh CPUs, nobody ready.
    pub fn rematch(&mut self) {
        let players: Vec<(usize, Racer)> = self
            .racers
            .iter_mut()
            .enumerate()
            .filter(|(_, r)| r.as_ref().is_some_and(|r| !r.is_cpu()))
            .map(|(i, r)| (i, r.take().unwrap()))
            .collect();
        let seed = self.rng.next_u32() as u64;
        let settings = self.settings;
        let mode = self.mode;
        *self = if mode == RoomMode::Solo { RaceRoom::solo(self.map, seed) } else { RaceRoom::new(self.map, seed) };
        self.set_settings(settings);
        for (slot, mut r) in players {
            r.ready = false;
            r.input = Input::default();
            r.standing = Standing::default();
            r.car = Car::new(Default::default(), 0.0, car::model_of(r.car_number).spec);
            self.next_join = self.next_join.max(r.joined + 1);
            self.racers[slot] = Some(r);
            if let Some(b) = self.battle.as_mut() {
                b.join(slot as u8);
            }
        }
        self.place_on_grid();
    }

    /// everyone to their starting spot, standing still
    fn place_on_grid(&mut self) {
        let mut humans: Vec<(u32, usize)> = self
            .racers
            .iter()
            .enumerate()
            .filter_map(|(i, r)| r.as_ref().filter(|r| !r.is_cpu()).map(|r| (r.joined, i)))
            .collect();
        humans.sort();
        for (i, r) in self.racers.iter_mut().enumerate() {
            let Some(r) = r.as_mut() else { continue };
            let spawn = if r.is_cpu() {
                maps::cpu_spawn(self.map, i)
            } else {
                let index = humans.iter().position(|&(_, s)| s == i).unwrap_or(0);
                maps::player_spawn(self.map, index)
            };
            r.car = Car::new(spawn.pos, spawn.heading, r.car.spec);
            r.standing = Standing::default();
        }
    }

    fn set_phase(&mut self, phase: Phase) {
        self.phase = phase;
        self.phase_time = 0.0;
    }

    /// what happened since the last call
    pub fn take_events(&mut self) -> Vec<Event> {
        core::mem::take(&mut self.events)
    }

    /// One 60 Hz tick.
    pub fn step(&mut self) {
        self.tick += 1;
        self.phase_time += DT;
        match self.phase {
            Phase::Lobby | Phase::Over => {}
            Phase::Flag => {
                if self.phase_time >= FLAG_TIME {
                    self.set_phase(Phase::Countdown);
                    self.events.push(Event::Countdown(3));
                }
            }
            Phase::Countdown => {
                if self.phase_time >= COUNTDOWN_TIME {
                    self.set_phase(Phase::Racing);
                    self.events.push(Event::Go);
                } else {
                    // 3 shows as the countdown starts, then 2 and 1 a second apart
                    let (before, now) = ((self.phase_time - DT) as u8, self.phase_time as u8);
                    if now != before {
                        self.events.push(Event::Countdown(3 - now));
                    }
                }
            }
            Phase::Racing => self.race_time += DT,
        }
        self.drive_cars();
        if matches!(self.phase, Phase::Countdown | Phase::Racing | Phase::Over) && self.battle.is_none() {
            self.update_standings();
        }
        if self.phase == Phase::Racing {
            if self.mode == RoomMode::Multiplayer || self.settings.cpu_count > 0 { self.update_items(); }
            self.update_battle();
            self.check_over();
        }
    }

    fn drive_cars(&mut self) {
        #[cfg(test)] { self.contacts = 0; }
        let racing = self.phase == Phase::Racing;
        let surface = Surface { grip: self.map.grip, traction: self.map.traction };
        let others: Vec<Other> = self
            .racers
            .iter()
            .enumerate()
            .filter_map(|(i, r)| {
                let r = r.as_ref()?;
                let out = self.battle.as_ref().is_some_and(|b| b.is_out(i as u8));
                let stopped = r.cpu.as_ref().is_some_and(|d| d.stopped);
                Some(Other { id: i as u8, pos: r.car.pos, heading: r.car.heading, vel: r.car.vel, active: !out && !stopped })
            })
            .collect();
        let track = &self.track;
        for (i, slot) in self.racers.iter_mut().enumerate() {
            let Some(r) = slot.as_mut() else { continue };
            let out = self.battle.as_ref().is_some_and(|b| b.is_out(i as u8));
            let input = if !racing || out {
                Input::default()
            } else if let Some(driver) = r.cpu.as_mut() {
                let others: Vec<Other> = others.iter().copied().filter(|o| o.id != i as u8).collect();
                driver.drive(DT, &mut r.car, &others, self.map, track, &mut self.rng)
            } else {
                r.input
            };
            if out {
                r.car.wreck();
            }
            r.car.tick(DT, &input, track, surface);
        }
        // bumps between every pair of cars
        for _ in 0..COLLISION_PASSES {
            let mut any = false;
            for a in 0..SLOTS {
                for b in a + 1..SLOTS {
                    let (left, right) = self.racers.split_at_mut(b);
                    let (Some(ra), Some(rb)) = (left[a].as_mut(), right[0].as_mut()) else { continue };
                    if car::separation(ra.car.pos, ra.car.heading, rb.car.pos, rb.car.heading).is_some() {
                        #[cfg(test)] { self.contacts |= 1 << (a * SLOTS + b); }
                        any = true;
                        car::collide(&mut ra.car, &mut rb.car, track);
                    }
                }
            }
            if !any {
                break;
            }
        }
        for (i, r) in self.racers.iter().enumerate() {
            if r.as_ref().is_some_and(|r| r.car.crashed) {
                self.events.push(Event::Crash { car: i as u8 });
            }
        }
    }

    fn update_standings(&mut self) {
        let course = &self.course;
        let race_time = self.race_time;
        let mut finished = self.racers.iter().flatten().filter(|r| r.standing.finished()).count() as u8;
        for (i, r) in self.racers.iter_mut().enumerate() {
            let Some(r) = r.as_mut() else { continue };
            let before = r.standing.laps;
            let was_finished = r.standing.finished();
            course.update(&mut r.standing, r.car.pos, race_time);
            if r.standing.finished() && !was_finished {
                finished += 1;
                self.events.push(Event::Finish { car: i as u8, place: finished });
            } else if r.standing.laps > before && !self.demo {
                self.events.push(Event::Lap { car: i as u8, lap: r.standing.laps + 1 });
            }
        }
    }

    fn racers_for_items(&self) -> Vec<ItemRacer> {
        self.racers
            .iter()
            .enumerate()
            .filter_map(|(i, r)| {
                let r = r.as_ref()?;
                Some(ItemRacer {
                    id: i as u8,
                    pos: r.car.pos,
                    heading: r.car.heading,
                    cpu: r.is_cpu(),
                    out: self.battle.as_ref().is_some_and(|b| b.is_out(i as u8)),
                })
            })
            .collect()
    }

    fn update_items(&mut self) {
        let racers = self.racers_for_items();
        let order = self.order();
        let mut hits = Vec::new();
        // players use items on a press of the button
        for r in &racers {
            let Some(racer) = self.racers[r.id as usize].as_mut() else { continue };
            let pressed = racer.input.item && !racer.item_down;
            racer.item_down = racer.input.item;
            if pressed && !r.cpu && !r.out {
                self.items.use_item(r, &racers, &order, &mut hits, &mut self.events);
            }
        }
        self.items.update(DT, &racers, &order, &mut self.rng, &mut hits, &mut self.events);
        // nitros take effect right away
        for e in &self.events {
            if let Event::Nitro { car } = *e {
                if let Some(r) = self.racers[car as usize].as_mut() {
                    if r.car.nitro <= 0.0 {
                        r.car.use_nitro();
                    }
                }
            }
        }
        for hit in hits {
            self.apply_hit(hit);
        }
    }

    fn apply_hit(&mut self, hit: Hit) {
        if let Some(b) = self.battle.as_mut() {
            b.damage(hit.car, hit.damage, hit.by, &mut self.events);
        }
        let Some(r) = self.racers[hit.car as usize].as_mut() else { return };
        match hit.effect {
            Effect::Spin => r.car.spin_out(),
            Effect::Slow => r.car.slow_down(items::SLOW_TIME),
            Effect::Freeze => r.car.frost(items::FREEZE_TIME),
            Effect::Burn => r.car.ignite(items::BURN_TIME),
        }
    }

    fn update_battle(&mut self) {
        let Some(battle) = self.battle.as_mut() else { return };
        let bodies: Vec<Body> = self
            .racers
            .iter()
            .enumerate()
            .filter_map(|(i, r)| {
                let r = r.as_ref()?;
                Some(Body { id: i as u8, pos: r.car.pos, vel: r.car.vel, mass: r.car.spec.mass })
            })
            .collect();
        battle.update(DT, &bodies, &mut self.events);
        for (i, r) in self.racers.iter_mut().enumerate() {
            if let Some(r) = r.as_mut() {
                if battle.is_out(i as u8) {
                    if let Some(d) = r.cpu.as_mut() {
                        d.stopped = true;
                    }
                }
            }
        }
    }

    fn check_over(&mut self) {
        if self.demo {
            return;
        }
        let over = if let Some(b) = &self.battle {
            b.over
        } else {
            let any_finished = self.humans().any(|(_, r)| r.standing.finished());
            let all_finished = self.humans().all(|(_, r)| r.standing.finished());
            if any_finished && self.first_finish.is_none() {
                self.first_finish = Some(self.race_time);
            }
            all_finished || self.first_finish.is_some_and(|t| self.race_time - t > FINISH_GRACE)
        };
        if over {
            self.set_phase(Phase::Over);
        }
    }

    /// car slots in race order, leader first
    pub fn order(&self) -> Vec<u8> {
        if let Some(b) = &self.battle {
            return b.order();
        }
        let slots: Vec<u8> = (0..SLOTS as u8).filter(|&i| self.racers[i as usize].is_some()).collect();
        let standings: Vec<&Standing> = slots.iter().map(|&i| &self.racers[i as usize].as_ref().unwrap().standing).collect();
        race::order(&standings).into_iter().map(|i| slots[i]).collect()
    }

    /// the state every player needs to draw the race this tick
    pub fn snapshot(&self) -> Snapshot {
        Snapshot {
            tick: self.tick,
            phase: self.phase,
            phase_time: self.phase_time,
            race_time: self.race_time,
            laps: self.course().laps(),
            course_length: self.course().length,
            cars: self
                .racers
                .iter()
                .enumerate()
                .filter_map(|(i, r)| {
                    let r = r.as_ref()?;
                    let fighter = self.battle.as_ref().and_then(|b| b.fighters[i].clone());
                    Some(CarSnapshot {
                        slot: i as u8,
                        car: r.car.clone(),
                        laps: r.standing.laps,
                        progress: r.standing.progress,
                        finish_time: r.standing.finish_time,
                        hp: fighter.as_ref().map_or(0.0, |f| f.hp),
                        kills: fighter.as_ref().map_or(0, |f| f.kills),
                        out: fighter.as_ref().is_some_and(|f| f.out),
                        held: self.items.held(i as u8).map(|h| (h.item, h.ammo)),
                        shield: self.items.shielded(i as u8),
                        off_course: if self.battle.is_some() {
                            0.0
                        } else {
                            self.course.distance_from_course(&r.standing, r.car.pos)
                        },
                    })
                })
                .collect(),
            order: self.order(),
            boxes: self.items.boxes.iter().map(|b| b.active()).collect(),
            rockets: self.items.rockets.iter().map(|p| (p.pos.x, p.pos.y, p.heading)).collect(),
            shots: self.items.shots.iter().map(|p| (p.pos.x, p.pos.y, p.heading, p.fire)).collect(),
            slicks: self.items.slicks.iter().map(|s| (s.pos.x, s.pos.y)).collect(),
            battle_time: self.battle.as_ref().map_or(0.0, |b| b.time),
        }
    }
}

/// Everything needed to draw one tick of a race (also what the server sends).
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Snapshot {
    pub tick: u64,
    pub phase: Phase,
    pub phase_time: f32,
    pub race_time: f32,
    pub laps: u8,
    /// length of one lap (or the whole sprint), for showing progress
    pub course_length: f32,
    pub cars: Vec<CarSnapshot>,
    /// slots in race order, leader first
    pub order: Vec<u8>,
    /// which item boxes are there (the positions come from items::box_rows)
    pub boxes: Vec<bool>,
    /// x, y, heading
    pub rockets: Vec<(f32, f32, f32)>,
    /// x, y, heading, fire
    pub shots: Vec<(f32, f32, f32, bool)>,
    pub slicks: Vec<(f32, f32)>,
    pub battle_time: f32,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct CarSnapshot {
    pub slot: u8,
    pub car: Car,
    pub laps: u8,
    pub progress: f32,
    pub finish_time: Option<f32>,
    /// battle: health, knockouts scored, and whether it's a wreck
    pub hp: f32,
    pub kills: u8,
    pub out: bool,
    /// item held and its ammo
    pub held: Option<(crate::events::Item, u8)>,
    pub shield: bool,
    /// how far the car is from the stretch of course it should be on ("return to track")
    pub off_course: f32,
}

#[cfg(test)]
mod tests {
    use super::*;
    fn with_cpus(map: &'static MapDef, seed: u64) -> RaceRoom {
        let mut room = RaceRoom::new(map, seed);
        room.set_settings(Settings { cpu_count: 3, ..Settings::default() });
        room
    }

    fn run(room: &mut RaceRoom, seconds: f32) -> Vec<Event> {
        let mut events = Vec::new();
        for _ in 0..(seconds * TICK_RATE as f32) as usize {
            room.step();
            events.extend(room.take_events());
        }
        events
    }

    #[test]
    fn lobby_flag_countdown_go() {
        let mut room = with_cpus(maps::get("classic"), 1);
        let me = room.add_player("Ana", 4).unwrap();
        assert_eq!(room.host(), Some(me));
        room.set_ready(me, true);
        assert_eq!(room.phase, Phase::Flag);
        let events = run(&mut room, FLAG_TIME + COUNTDOWN_TIME + 0.1);
        let marks: Vec<&Event> = events
            .iter()
            .filter(|e| matches!(e, Event::Countdown(_) | Event::Go | Event::Flag))
            .collect();
        assert_eq!(marks, vec![&Event::Flag, &Event::Countdown(3), &Event::Countdown(2), &Event::Countdown(1), &Event::Go]);
        assert_eq!(room.phase, Phase::Racing);
    }

    #[test]
    fn names_are_unique_and_rooms_fill_up() {
        let mut room = with_cpus(maps::get("tokyo"), 1);
        let a = room.add_player("Ana", 1).unwrap();
        let b = room.add_player("Ana", 1).unwrap();
        assert_eq!(room.racer(b).unwrap().name, "Ana 2");
        assert_ne!(room.racer(a).unwrap().car.pos, room.racer(b).unwrap().car.pos);
        for _ in 2..MAX_PLAYERS {
            room.add_player("X", 1).unwrap();
        }
        assert!(room.add_player("late", 1).is_none());
    }

    #[test]
    fn a_held_gas_pedal_drives_the_car_and_cpus_race() {
        let mut room = with_cpus(maps::get("classic"), 3);
        let me = room.add_player("Ana", 4).unwrap();
        room.set_ready(me, true);
        run(&mut room, FLAG_TIME + COUNTDOWN_TIME);
        room.set_input(me, Input { gas: true, ..Input::default() });
        run(&mut room, 2.0);
        let snap = room.snapshot();
        assert!(snap.cars.iter().find(|c| c.slot == me).unwrap().car.speed() > 50.0);
        // A CPU may brake to preserve a gap, but every driver must leave the grid.
        for c in &snap.cars {
            let spawn = if c.slot == me { maps::player_spawn(room.map, 0) } else { maps::cpu_spawn(room.map, c.slot as usize) };
            assert!(c.car.pos.dist(spawn.pos) > 30.0);
        }
    }

    #[test]
    fn cpus_finish_a_race_with_traffic_and_items() {
        for map in ["classic", "tokyo", "summit"] {
            let mut room = with_cpus(maps::get(map), 11);
            room.add_player("Ana", 4).unwrap();
            room.set_settings(Settings { laps: 2, difficulty: 1, cpu_count: 3 });
            room.start();
            let events = run(&mut room, 300.0);
            let finishes = events.iter().filter(|e| matches!(e, Event::Finish { .. })).count();
            assert!(finishes >= CPUS, "{map}: only {finishes} CPUs finished: {:?}", room.racers.iter().flatten().map(|r| (r.car.pos, r.car.speed(), &r.standing)).collect::<Vec<_>>());
            assert!(events.iter().any(|e| matches!(e, Event::Got { .. })), "{map}: nobody got an item");
            // only players end a race, and this one never moved
            assert_eq!(room.phase, Phase::Racing);
        }
    }

    #[test]
    fn battle_ends_with_one_car_left_or_on_time() {
        let mut room = with_cpus(maps::get("junkyard"), 5);
        room.add_player("Ana", 13).unwrap();
        room.start();
        let events = run(&mut room, FLAG_TIME + COUNTDOWN_TIME + battle_limit());
        assert!(events.contains(&Event::BattleOver));
        assert_eq!(room.phase, Phase::Over);
        assert!(events.iter().any(|e| matches!(e, Event::Damage { .. })), "nobody got hurt");
    }

    fn battle_limit() -> f32 {
        crate::battle::TIME_LIMIT + 1.0
    }

    #[test]
    fn same_seed_same_race() {
        let play = || {
            let mut room = with_cpus(maps::get("london"), 99);
            let me = room.add_player("Ana", 7).unwrap();
            room.start();
            for t in 0..3000 {
                room.set_input(me, Input { gas: true, steer: if t % 200 < 100 { 0.3 } else { -0.3 }, item: t % 50 == 0, ..Input::default() });
                room.step();
            }
            room.snapshot()
        };
        assert_eq!(play(), play());
    }

    #[test]
    fn rematch_resets_to_the_lobby() {
        let mut room = with_cpus(maps::get("classic"), 1);
        let me = room.add_player("Ana", 4).unwrap();
        room.set_ready(me, true);
        run(&mut room, 10.0);
        room.rematch();
        assert_eq!(room.phase, Phase::Lobby);
        assert!(!room.racer(me).unwrap().ready);
        assert_eq!(room.racer(me).unwrap().car.pos, maps::player_spawn(room.map, 0).pos);
    }

    #[test]
    fn demo_race_keeps_going() {
        let mut room = RaceRoom::demo(maps::get("classic"), 2, 5);
        run(&mut room, 120.0);
        assert!(room.snapshot().cars.iter().all(|c| c.car.speed() > 10.0 || c.car.stunned()));
    }
    #[test]
    fn traffic_benchmark() {
        let mut impacts = 0;
        let mut contacts = 0;
        let mut times = Vec::new();
        let mut longest_stall = 0.0f32;
        for map in maps::ALL.iter().filter(|m| m.kind == MapKind::Circuit) {
            for seed in 0..5 {
                let mut room = RaceRoom::demo(map, seed, 5);
                let mut last_walls = 0u16;
                let mut last_contacts = 0u128;
                let mut stalled = [0.0f32; SLOTS];
                for _ in 0..(120 * TICK_RATE) {
                    room.step();
                    for (i, racer) in room.racers.iter().enumerate() {
                        stalled[i] = if room.race_time > 1.0 && racer.as_ref().is_some_and(|r| r.car.speed() < 10.0) { stalled[i] + DT } else { 0.0 };
                        longest_stall = longest_stall.max(stalled[i]);
                    }
                    let walls = room.racers.iter().enumerate().fold(0u16, |mask, (i, r)| mask | if r.as_ref().is_some_and(|r| r.car.crashed) { 1 << i } else { 0 });
                    impacts += (walls & !last_walls).count_ones();
                    contacts += (room.contacts & !last_contacts).count_ones();
                    last_walls = walls;
                    last_contacts = room.contacts;
                    room.take_events();
                }
                for r in room.racers.iter().flatten() {
                    assert!(r.standing.finished(), "{} seed {seed} failed to finish", map.id);
                    assert_eq!(r.cpu.as_ref().unwrap().rescues, 0, "{} seed {seed}: rescue", map.id);
                    times.push(r.standing.finish_time.unwrap());
                }
            }
        }
        times.sort_by(f32::total_cmp);
        // Previous driver, same item-free fixture: 65 wall + 193 contact episodes,
        // median 34.966305 s. Require >=75% fewer episodes and <=25% slowdown.
        assert!(longest_stall < 5.0, "stuck for {longest_stall}s");
        assert!(impacts + contacts <= 64, "{impacts} walls + {contacts} contacts");
        assert!(times[times.len()/2] <= 34.966305 * 1.25);
        println!("TRAFFIC: {impacts} wall episodes, {contacts} car contact episodes, median {}s, longest stall {longest_stall}s", times[times.len()/2]);
    }

    #[test]
    fn a_solo_time_trial_has_one_human_and_no_items_or_cpus() {
        let mut room = RaceRoom::solo(maps::get("classic"), 9);
        let slot = room.add_player("You", 4).unwrap();
        assert!(room.add_player("Second", 1).is_none());
        room.set_settings(Settings { laps: 2, difficulty: 2, cpu_count: 0 });
        assert_eq!(room.settings.cpu_count, 0);
        room.start();
        room.set_input(slot, Input { gas: true, item: true, ..Input::default() });
        let events = run(&mut room, 20.0);
        let snap = room.snapshot();
        assert_eq!(snap.cars.len(), 1);
        assert!(snap.boxes.is_empty() && snap.rockets.is_empty() && snap.shots.is_empty() && snap.slicks.is_empty());
        assert!(snap.cars[0].held.is_none());
        assert!(!events.iter().any(|e| matches!(e, Event::Got { .. })));
        room.rematch();
        assert_eq!(room.human_count(), 1);
        assert_eq!(room.racers.iter().flatten().count(), 1);
        assert!(room.items.boxes.is_empty());
        assert_eq!(room.settings.laps, 2);
    }

    #[test]
    fn a_solo_race_can_have_cpus_and_items() {
        let mut room = RaceRoom::solo(maps::get("classic"), 9);
        room.add_player("You", 4).unwrap();
        room.set_settings(Settings { laps: 1, difficulty: 3, cpu_count: 255 });
        assert_eq!(room.settings.cpu_count, CPUS as u8);
        assert_eq!(room.racers.iter().flatten().filter(|r| r.is_cpu()).count(), CPUS);
        assert!(!room.items.boxes.is_empty());
        room.rematch();
        assert_eq!(room.racers.iter().flatten().filter(|r| r.is_cpu()).count(), CPUS);
        assert_eq!(room.human_count(), 1);
        assert!(!room.items.boxes.is_empty());
        // back to a time trial: the CPUs and boxes go
        room.set_settings(Settings { laps: 1, difficulty: 1, cpu_count: 0 });
        assert_eq!(room.racers.iter().flatten().count(), 1);
        assert!(room.items.boxes.is_empty());
    }

    #[test]
    fn cpu_settings_preserve_human_slots_and_reset_readiness() {
        let mut room = RaceRoom::new(maps::get("classic"), 7);
        assert_eq!(room.settings.cpu_count, 0);
        let slots: Vec<_> = (0..MAX_PLAYERS).map(|i| room.add_player(&format!("P{i}"), 1).unwrap()).collect();
        for count in [1, 3, 2, 0, 255] {
            room.set_ready(slots[0], true);
            room.set_settings(Settings { cpu_count: count, ..Settings::default() });
            assert_eq!(room.racers.iter().flatten().filter(|r| r.is_cpu()).count(), count.min(3) as usize);
            assert_eq!(room.humans().map(|(s, _)| s).collect::<Vec<_>>(), slots);
            assert!(room.humans().all(|(_, r)| !r.ready));
        }
        room.start();
        room.set_settings(Settings::default());
        assert_eq!(room.settings.cpu_count, 3);
        room.rematch();
        assert_eq!(room.settings.cpu_count, 3);
        assert_eq!(room.humans().map(|(s, _)| s).collect::<Vec<_>>(), slots);
    }

    #[test]
    fn battles_need_two_participants_and_removed_cpus_leave_battle() {
        let mut room = RaceRoom::new(maps::get("junkyard"), 3);
        let me = room.add_player("You", 4).unwrap();
        room.set_ready(me, true);
        assert_eq!(room.phase, Phase::Lobby);
        room.set_settings(Settings { cpu_count: 2, ..Settings::default() });
        room.set_settings(Settings { cpu_count: 1, ..Settings::default() });
        assert!(room.battle.as_ref().unwrap().fighters[1].is_none());
        room.set_ready(me, true);
        assert_eq!(room.phase, Phase::Flag);
    }

}
