//! Battle mode, a demolition derby: everyone starts with 100 health, ramming another car at
//! speed hurts it (heavier cars hit harder and take less), items do damage too. A car at 0 is
//! knocked out. Last car driving wins; if time runs out, whoever has the most health left.

use serde::{Deserialize, Serialize};

use crate::car::LENGTH;
use crate::events::Event;
use crate::math::{round, Vec2};

pub const MAX_HP: f32 = 100.0;
/// seconds before the battle ends on health
pub const TIME_LIMIT: f32 = 150.0;
/// damage per px/s of ramming speed (a full-speed T-bone does about 40)
const RAM_DAMAGE: f32 = 0.085;
/// slower bumps than this don't hurt
const RAM_THRESHOLD: f32 = 70.0;
/// the same two cars can't hurt each other again for this long (one crash = one hit)
const PAIR_COOLDOWN: f32 = 1.0;
/// a knockout counts for whoever hit the car within this many seconds
const CREDIT_TIME: f32 = 3.0;

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Fighter {
    pub hp: f32,
    pub out: bool,
    /// battle time when it was knocked out
    pub out_time: f32,
    pub kills: u8,
    last_hit_by: Option<u8>,
    last_hit_at: f32,
}

impl Default for Fighter {
    fn default() -> Fighter {
        Fighter { hp: MAX_HP, out: false, out_time: 0.0, kills: 0, last_hit_by: None, last_hit_at: -100.0 }
    }
}

/// a car in the battle this tick
#[derive(Clone, Copy, Debug)]
pub struct Body {
    pub id: u8,
    pub pos: Vec2,
    pub vel: Vec2,
    pub mass: f32,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Battle {
    /// per car slot (None for empty slots)
    pub fighters: Vec<Option<Fighter>>,
    /// per pair of slots: seconds before they can hurt each other again
    cooldowns: Vec<f32>,
    pub time: f32,
    pub over: bool,
}

impl Battle {
    pub fn new(slots: usize) -> Battle {
        Battle { fighters: vec![None; slots], cooldowns: vec![0.0; slots * slots], time: 0.0, over: false }
    }

    pub fn join(&mut self, id: u8) {
        self.fighters[id as usize] = Some(Fighter::default());
    }

    pub fn leave(&mut self, id: u8) {
        self.fighters[id as usize] = None;
    }

    pub fn is_out(&self, id: u8) -> bool {
        self.fighters.get(id as usize).and_then(|f| f.as_ref()).is_some_and(|f| f.out)
    }

    /// Moves the battle on: finds crashes and hands out damage.
    pub fn update(&mut self, dt: f32, bodies: &[Body], events: &mut Vec<Event>) {
        if self.over {
            return;
        }
        self.time += dt;
        let slots = self.fighters.len();
        for c in &mut self.cooldowns {
            *c = (*c - dt).max(0.0);
        }
        // crashes: two cars touching, and closing on each other fast enough
        for (i, a) in bodies.iter().enumerate() {
            for b in &bodies[i + 1..] {
                if self.is_out(a.id) && self.is_out(b.id) {
                    continue;
                }
                let d = b.pos - a.pos;
                let dist = d.len();
                if !(1e-3..=LENGTH + 4.0).contains(&dist) {
                    continue;
                }
                let key = a.id as usize * slots + b.id as usize;
                if self.cooldowns[key] > 0.0 {
                    continue;
                }
                let n = d * (1.0 / dist);
                // how fast each car was driving into the other
                let a_into = a.vel.dot(n);
                let b_into = -b.vel.dot(n);
                let total = a.mass + b.mass;
                let mut to_b = (a_into - RAM_THRESHOLD).max(0.0) * RAM_DAMAGE * (2.0 * a.mass / total);
                let mut to_a = (b_into - RAM_THRESHOLD).max(0.0) * RAM_DAMAGE * (2.0 * b.mass / total);
                // the one doing the ramming feels it a little too
                to_a += to_b * 0.2;
                to_b += to_a * 0.2;
                if to_a + to_b < 2.0 {
                    continue;
                }
                self.cooldowns[key] = PAIR_COOLDOWN;
                if to_b >= 2.0 {
                    let by = (!self.is_out(a.id)).then_some(a.id);
                    self.hurt(b.id, to_b, by, events);
                }
                if to_a >= 2.0 {
                    let by = (!self.is_out(b.id)).then_some(b.id);
                    self.hurt(a.id, to_a, by, events);
                }
            }
        }
        let count = self.fighters.iter().flatten().count();
        let alive = self.fighters.iter().flatten().filter(|f| !f.out).count();
        if (alive <= 1 && count > 1) || self.time >= TIME_LIMIT {
            self.over = true;
            events.push(Event::BattleOver);
        }
    }

    /// damage from an item
    pub fn damage(&mut self, id: u8, amount: f32, by: Option<u8>, events: &mut Vec<Event>) {
        if !self.over {
            self.hurt(id, amount, by, events);
        }
    }

    fn hurt(&mut self, id: u8, amount: f32, by: Option<u8>, events: &mut Vec<Event>) {
        let time = self.time;
        let Some(f) = self.fighters[id as usize].as_mut() else { return };
        if f.out {
            return;
        }
        f.hp = (f.hp - amount).max(0.0);
        if let Some(by) = by.filter(|&b| b != id) {
            f.last_hit_by = Some(by);
            f.last_hit_at = time;
        }
        events.push(Event::Damage { car: id, amount: round(amount) as u8 });
        if f.hp <= 0.0 {
            f.out = true;
            f.out_time = time;
            let killer = f.last_hit_by.filter(|_| time - f.last_hit_at <= CREDIT_TIME);
            if let Some(k) = killer.and_then(|k| self.fighters[k as usize].as_mut()) {
                k.kills += 1;
            }
            events.push(Event::Out { car: id, by: killer });
        }
    }

    /// a car's score for the standings: still in (most health first), then how long it lasted
    pub fn score(&self, id: u8) -> f32 {
        match &self.fighters[id as usize] {
            Some(f) if f.out => f.out_time,
            Some(f) => 10_000.0 + f.hp,
            None => -1.0,
        }
    }

    /// car ids, best first
    pub fn order(&self) -> Vec<u8> {
        let mut ids: Vec<u8> = (0..self.fighters.len() as u8).filter(|&i| self.fighters[i as usize].is_some()).collect();
        ids.sort_by(|&a, &b| self.score(b).total_cmp(&self.score(a)));
        ids
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn body(id: u8, x: f32, vx: f32, mass: f32) -> Body {
        Body { id, pos: Vec2::new(x, 0.0), vel: Vec2::new(vx, 0.0), mass }
    }

    #[test]
    fn ramming_hurts_and_knocks_out() {
        let mut battle = Battle::new(3);
        for id in 0..3 {
            battle.join(id);
        }
        let mut events = Vec::new();
        // a full-speed T-bone, again and again (the pair cooldown spaces the hits)
        let mut hits = 0;
        while !battle.is_out(1) && hits < 20 {
            battle.update(1.1, &[body(0, 0.0, 470.0, 1.0), body(1, 15.0, 0.0, 1.0)], &mut events);
            hits += 1;
        }
        assert!(battle.is_out(1));
        assert_eq!(battle.fighters[0].as_ref().unwrap().kills, 1);
        assert!(events.contains(&Event::Out { car: 1, by: Some(0) }));
        // two of three still in: not over yet
        assert!(!battle.over);
        assert_eq!(battle.order()[2], 1);
    }

    #[test]
    fn gentle_bumps_do_nothing() {
        let mut battle = Battle::new(2);
        battle.join(0);
        battle.join(1);
        let mut events = Vec::new();
        battle.update(0.1, &[body(0, 0.0, 60.0, 1.0), body(1, 15.0, 0.0, 1.0)], &mut events);
        assert!(events.is_empty());
    }

    #[test]
    fn last_car_standing_ends_it() {
        let mut battle = Battle::new(2);
        battle.join(0);
        battle.join(1);
        let mut events = Vec::new();
        battle.damage(1, 200.0, Some(0), &mut events);
        battle.update(0.1, &[], &mut events);
        assert!(battle.over && events.contains(&Event::BattleOver));
    }
}
