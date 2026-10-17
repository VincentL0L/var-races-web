//! The items, kart-race style: rows of item boxes on the long straights, and seven items.
//!
//!  - Nitro: a big burst of speed
//!  - Bottle Rocket: flies ahead, homes in on the nearest car in front, spins it out
//!  - Oil Slick: dropped behind; whoever drives over it spins out
//!  - Bubble Shield: soaks up the next hit
//!  - Static Pulse: slows down everyone ahead of you for a few seconds
//!  - Frost / Fire Blaster: five quick shots; frost freezes, fire burns
//!
//! The item system decides who gets hit; the room applies the effects to the cars.

use serde::{Deserialize, Serialize};

use crate::events::{Effect, Event, Item};
use crate::maps::{MapDef, MapKind};
use crate::math::{angle_diff, clamp, deg, Vec2};
use crate::rng::Rng;

const BOX_RADIUS: f32 = 15.0;
const BOX_RESPAWN: f32 = 4.0;
const ROCKET_SPEED: f32 = 720.0;
const ROCKET_LIFE: f32 = 2.2;
const HIT_RADIUS: f32 = 14.0;
const SLICK_RADIUS: f32 = 13.0;
const SLICK_LIFE: f32 = 25.0;
const SHIELD_TIME: f32 = 8.0;
pub const SLOW_TIME: f32 = 2.5;
pub const FREEZE_TIME: f32 = 1.3;
pub const BURN_TIME: f32 = 2.0;
/// blaster shots per pickup, how fast they fly, how long, and the gap between them
pub const BLASTER_SHOTS: u8 = 5;
const SHOT_SPEED: f32 = 620.0;
const SHOT_LIFE: f32 = 1.1;
const SHOT_GAP: f32 = 0.22;
/// how hard a rocket turns toward its target (radians a second), and how far it looks
const HOMING_TURN: f32 = 170.0 * core::f32::consts::PI / 180.0;
const HOMING_RANGE: f32 = 520.0;

// battle damage from each item
pub const ROCKET_DAMAGE: f32 = 30.0;
pub const OIL_DAMAGE: f32 = 14.0;
pub const PULSE_DAMAGE: f32 = 10.0;
pub const FROST_DAMAGE: f32 = 7.0;
pub const FIRE_DAMAGE: f32 = 11.0;

/// where a car is this tick, for the items
#[derive(Clone, Copy, Debug)]
pub struct Racer {
    pub id: u8,
    pub pos: Vec2,
    pub heading: f32,
    pub cpu: bool,
    /// out of a battle: rockets ignore wrecks
    pub out: bool,
}

/// an item effect to apply to a car (the room does it)
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Hit {
    pub car: u8,
    pub effect: Effect,
    pub by: Option<u8>,
    pub damage: f32,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ItemBox {
    pub pos: Vec2,
    pub respawn: f32,
}

impl ItemBox {
    pub fn active(&self) -> bool {
        self.respawn <= 0.0
    }
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Projectile {
    pub pos: Vec2,
    pub heading: f32,
    pub age: f32,
    pub owner: u8,
    /// blaster shots: fire (true) or frost
    pub fire: bool,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Slick {
    pub pos: Vec2,
    pub age: f32,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq)]
pub struct Held {
    pub item: Item,
    pub ammo: u8,
    /// a CPU uses it once this runs out
    cpu_wait: f32,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Items {
    pub boxes: Vec<ItemBox>,
    pub rockets: Vec<Projectile>,
    pub shots: Vec<Projectile>,
    pub slicks: Vec<Slick>,
    /// per car slot: what it holds, its shield time left, its last blaster shot
    pub held: Vec<Option<Held>>,
    pub shields: Vec<f32>,
    last_shot: Vec<f32>,
    clock: f32,
}

impl Items {
    pub fn new(map: &MapDef, slots: usize) -> Items {
        let boxes = box_rows(map)
            .into_iter()
            .flatten()
            .map(|pos| ItemBox { pos, respawn: 0.0 })
            .collect();
        Items {
            boxes,
            rockets: Vec::new(),
            shots: Vec::new(),
            slicks: Vec::new(),
            held: vec![None; slots],
            shields: vec![0.0; slots],
            last_shot: vec![-100.0; slots],
            clock: 0.0,
        }
    }

    pub fn held(&self, car: u8) -> Option<Held> {
        self.held.get(car as usize).copied().flatten()
    }

    pub fn shielded(&self, car: u8) -> bool {
        self.shields.get(car as usize).is_some_and(|&s| s > 0.0)
    }

    /// forgets what a car had (it left the room)
    pub fn clear(&mut self, car: u8) {
        self.held[car as usize] = None;
        self.shields[car as usize] = 0.0;
    }

    /// Moves rockets and shots, ages oil, hands out items and lets CPUs use theirs.
    /// `order` is car ids, leader first. Hits go to `hits`, announcements to `events`.
    pub fn update(
        &mut self,
        dt: f32,
        racers: &[Racer],
        order: &[u8],
        rng: &mut Rng,
        hits: &mut Vec<Hit>,
        events: &mut Vec<Event>,
    ) {
        self.clock += dt;
        for b in &mut self.boxes {
            b.respawn -= dt;
        }
        for s in &mut self.shields {
            *s = (*s - dt).max(0.0);
        }

        // driving through a box gives an item (if you aren't holding one)
        for r in racers.iter().filter(|r| !r.out) {
            for b in &mut self.boxes {
                if b.active() && r.pos.dist(b.pos) < BOX_RADIUS && self.held[r.id as usize].is_none() {
                    b.respawn = BOX_RESPAWN;
                    let place = order.iter().position(|&id| id == r.id).map_or(order.len(), |p| p + 1);
                    let item = roll(place, order.len(), rng);
                    let ammo = if matches!(item, Item::Frost | Item::Fire) { BLASTER_SHOTS } else { 1 };
                    let cpu_wait = if r.cpu { rng.range(0.4, 2.5) } else { 0.0 };
                    self.held[r.id as usize] = Some(Held { item, ammo, cpu_wait });
                    events.push(Event::Got { car: r.id, item, ammo });
                }
            }
        }

        // rockets: after a moment they lock on to the nearest car ahead and turn toward it
        let mut i = 0;
        while i < self.rockets.len() {
            let rocket = &mut self.rockets[i];
            rocket.age += dt;
            if rocket.age > 0.12 {
                let target = racers
                    .iter()
                    .filter(|r| r.id != rocket.owner && !r.out)
                    .filter(|r| {
                        let to = r.pos - rocket.pos;
                        to.len() < HOMING_RANGE && angle_diff(to.angle(), rocket.heading).abs() < deg(75.0)
                    })
                    .min_by(|a, b| a.pos.dist2(rocket.pos).total_cmp(&b.pos.dist2(rocket.pos)));
                if let Some(t) = target {
                    let want = (t.pos - rocket.pos).angle();
                    rocket.heading += clamp(angle_diff(want, rocket.heading), -HOMING_TURN * dt, HOMING_TURN * dt);
                }
            }
            rocket.pos += Vec2::from_angle(rocket.heading) * (ROCKET_SPEED * dt);
            let mut gone = rocket.age > ROCKET_LIFE;
            let (owner, age, pos) = (rocket.owner, rocket.age, rocket.pos);
            if !gone {
                if let Some(r) =
                    racers.iter().find(|r| (r.id != owner || age > 0.4) && r.pos.dist(pos) < HIT_RADIUS)
                {
                    self.hit(r.id, Effect::Spin, Some(owner), ROCKET_DAMAGE, hits, events);
                    gone = true;
                }
            }
            if gone {
                self.rockets.remove(i);
            } else {
                i += 1;
            }
        }

        // blaster shots: quick and straight
        let mut i = 0;
        while i < self.shots.len() {
            let shot = &mut self.shots[i];
            shot.age += dt;
            shot.pos += Vec2::from_angle(shot.heading) * (SHOT_SPEED * dt);
            let mut gone = shot.age > SHOT_LIFE;
            let (owner, pos, fire) = (shot.owner, shot.pos, shot.fire);
            if !gone {
                if let Some(r) = racers.iter().find(|r| r.id != owner && r.pos.dist(pos) < HIT_RADIUS) {
                    let (effect, damage) = if fire { (Effect::Burn, FIRE_DAMAGE) } else { (Effect::Freeze, FROST_DAMAGE) };
                    self.hit(r.id, effect, Some(owner), damage, hits, events);
                    gone = true;
                }
            }
            if gone {
                self.shots.remove(i);
            } else {
                i += 1;
            }
        }

        // oil slicks
        let mut i = 0;
        while i < self.slicks.len() {
            let slick = &mut self.slicks[i];
            slick.age += dt;
            let mut gone = slick.age > SLICK_LIFE;
            let (age, pos) = (slick.age, slick.pos);
            if !gone && age > 0.5 {
                if let Some(r) = racers.iter().find(|r| r.pos.dist(pos) < SLICK_RADIUS) {
                    self.hit(r.id, Effect::Spin, None, OIL_DAMAGE, hits, events);
                    gone = true;
                }
            }
            if gone {
                self.slicks.remove(i);
            } else {
                i += 1;
            }
        }

        // CPUs use their items after a moment (aimed ones wait for someone in front)
        for r in racers.iter().filter(|r| r.cpu && !r.out) {
            let Some(mut held) = self.held[r.id as usize] else { continue };
            held.cpu_wait -= dt;
            self.held[r.id as usize] = Some(held);
            let aimed = matches!(held.item, Item::Rocket | Item::Frost | Item::Fire);
            if held.cpu_wait <= 0.0 && (!aimed || car_ahead(r, racers) || held.cpu_wait < -6.0) {
                self.use_item(r, racers, order, hits, events);
                // blasters: the next shot follows quickly
                if let Some(h) = self.held[r.id as usize].as_mut() {
                    h.cpu_wait = rng.range(0.3, 0.7);
                }
            }
        }
    }

    /// uses whatever this racer is holding
    pub fn use_item(&mut self, me: &Racer, racers: &[Racer], order: &[u8], hits: &mut Vec<Hit>, events: &mut Vec<Event>) {
        let slot = me.id as usize;
        let Some(held) = self.held[slot] else { return };
        let f = Vec2::from_angle(me.heading);
        if matches!(held.item, Item::Frost | Item::Fire) {
            // a blaster: one shot each use, a short gap between them, until it's empty
            if self.clock - self.last_shot[slot] < SHOT_GAP {
                return;
            }
            self.last_shot[slot] = self.clock;
            let fire = held.item == Item::Fire;
            self.shots.push(Projectile { pos: me.pos + f * 16.0, heading: me.heading, age: 0.0, owner: me.id, fire });
            let left = held.ammo.saturating_sub(1);
            events.push(Event::Shot { car: me.id, fire, left });
            self.held[slot] = if left == 0 { None } else { Some(Held { ammo: left, ..held }) };
            return;
        }
        self.held[slot] = None;
        match held.item {
            Item::Nitro => events.push(Event::Nitro { car: me.id }),
            Item::Rocket => self.rockets.push(Projectile {
                pos: me.pos + f * 18.0,
                heading: me.heading,
                age: 0.0,
                owner: me.id,
                fire: false,
            }),
            Item::Oil => self.slicks.push(Slick { pos: me.pos - f * 22.0, age: 0.0 }),
            Item::Bubble => self.shields[slot] = SHIELD_TIME,
            Item::Pulse => {
                let place = |id: u8| order.iter().position(|&o| o == id);
                let mine = place(me.id);
                for r in racers.iter().filter(|r| r.id != me.id) {
                    if let Some(p) = place(r.id) {
                        if mine.is_none_or(|m| p < m) {
                            self.hit(r.id, Effect::Slow, Some(me.id), PULSE_DAMAGE, hits, events);
                        }
                    }
                }
            }
            Item::Frost | Item::Fire => unreachable!(),
        }
    }

    /// an item effect lands, unless a bubble shield takes the hit
    fn hit(&mut self, car: u8, effect: Effect, by: Option<u8>, damage: f32, hits: &mut Vec<Hit>, events: &mut Vec<Event>) {
        if self.shields[car as usize] > 0.0 {
            self.shields[car as usize] = 0.0;
            events.push(Event::Blocked { car });
            return;
        }
        hits.push(Hit { car, effect, by, damage });
        events.push(Event::Hit { car, effect });
    }
}

/// someone a CPU could hit with an aimed item: just in front, in its lane
fn car_ahead(me: &Racer, racers: &[Racer]) -> bool {
    let f = Vec2::from_angle(me.heading);
    racers.iter().any(|o| {
        let r = o.pos - me.pos;
        let along = r.dot(f);
        o.id != me.id && !o.out && along > 20.0 && along < 320.0 && r.cross(f).abs() < 18.0
    })
}

/// a random item: cars at the back get the better ones (place 1 = leading)
fn roll(place: usize, count: usize, rng: &mut Rng) -> Item {
    let r = if count <= 1 { 0.5 } else { (place.max(1) - 1) as f32 / (count - 1) as f32 };
    let weights = [
        1.0 + 3.0 * r,                                     // Nitro
        2.0,                                               // Rocket
        2.6 - 2.0 * r,                                     // Oil
        2.0 - 1.2 * r,                                     // Bubble
        if place <= 1 { 0.0 } else { 0.3 + 2.4 * r },      // Pulse
        1.6,                                               // Frost
        1.6,                                               // Fire
    ];
    let mut pick = rng.range(0.0, weights.iter().sum());
    for (i, w) in weights.iter().enumerate() {
        pick -= w;
        if pick <= 0.0 {
            return Item::ALL[i];
        }
    }
    Item::Nitro
}

/// Rows of three boxes across the middle of every long straight (the same on every machine).
pub fn box_rows(map: &MapDef) -> Vec<[Vec2; 3]> {
    let w = map.waypoint_list();
    let n = w.len();
    let stretches = if map.kind == MapKind::Sprint { n - 2 } else { n };
    let mut rows = Vec::new();
    for i in 0..stretches {
        let (a, b) = (w[i], w[(i + 1) % n]);
        let len = a.dist(b);
        if len < 420.0 {
            continue;
        }
        let d = (b - a) * (1.0 / len);
        // one row in the middle; a long straight gets one every 900 or so
        let count = ((len / 900.0) as usize).max(1);
        for c in 0..count {
            let center = a.lerp(b, (c as f32 + 1.0) / (count as f32 + 1.0));
            let across = d.perp() * 30.0;
            rows.push([center - across, center, center + across]);
        }
    }
    rows
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::maps;

    fn racer(id: u8, x: f32, y: f32, heading: f32) -> Racer {
        Racer { id, pos: Vec2::new(x, y), heading, cpu: false, out: false }
    }

    #[test]
    fn leader_never_gets_a_pulse() {
        let mut rng = Rng::new(1);
        for _ in 0..2000 {
            assert_ne!(roll(1, 4, &mut rng), Item::Pulse);
        }
    }

    #[test]
    fn rocket_homes_in_and_spins_out() {
        let map = maps::get("classic");
        let mut items = Items::new(map, 4);
        items.held[0] = Some(Held { item: Item::Rocket, ammo: 1, cpu_wait: 0.0 });
        let shooter = racer(0, 500.0, 500.0, 0.0);
        // ahead and a little to the side
        let target = racer(1, 800.0, 540.0, 0.0);
        let racers = [shooter, target];
        let (mut hits, mut events) = (Vec::new(), Vec::new());
        items.use_item(&shooter, &racers, &[1, 0], &mut hits, &mut events);
        let mut rng = Rng::new(1);
        for _ in 0..120 {
            items.update(1.0 / 60.0, &racers, &[1, 0], &mut rng, &mut hits, &mut events);
        }
        assert_eq!(hits, vec![Hit { car: 1, effect: Effect::Spin, by: Some(0), damage: ROCKET_DAMAGE }]);
    }

    #[test]
    fn bubble_blocks_one_hit() {
        let map = maps::get("classic");
        let mut items = Items::new(map, 2);
        items.shields[1] = SHIELD_TIME;
        let (mut hits, mut events) = (Vec::new(), Vec::new());
        items.hit(1, Effect::Spin, None, 0.0, &mut hits, &mut events);
        items.hit(1, Effect::Spin, None, 0.0, &mut hits, &mut events);
        assert_eq!(events[0], Event::Blocked { car: 1 });
        assert_eq!(hits.len(), 1);
    }

    #[test]
    fn blasters_fire_five_spaced_shots() {
        let map = maps::get("classic");
        let mut items = Items::new(map, 2);
        items.held[0] = Some(Held { item: Item::Frost, ammo: BLASTER_SHOTS, cpu_wait: 0.0 });
        let me = racer(0, 500.0, 500.0, 0.0);
        let (mut hits, mut events) = (Vec::new(), Vec::new());
        let mut rng = Rng::new(1);
        for _ in 0..120 {
            items.use_item(&me, &[me], &[0], &mut hits, &mut events);
            items.update(1.0 / 60.0, &[me], &[0], &mut rng, &mut hits, &mut events);
        }
        let shots = events.iter().filter(|e| matches!(e, Event::Shot { .. })).count();
        assert_eq!(shots, BLASTER_SHOTS as usize);
        assert!(items.held(0).is_none());
    }

    #[test]
    fn every_course_has_item_boxes() {
        for map in maps::ALL.iter() {
            assert!(!box_rows(map).is_empty(), "{}", map.id);
        }
    }
}
