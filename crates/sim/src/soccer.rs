//! Race SoccerCar Soccer: 1v1 or 2v2 on a walled pitch with a bouncing ball, boost pads and
//! power-ups. The server runs the match; players send inputs and draw the snapshots.
//!
//! Slots alternate teams (even = blue, odd = orange). The match starts when every slot is
//! taken. First to five goals wins, or the leader after three minutes; a tie goes to sudden
//! death. A player leaving sends the match back to waiting for a replacement.

use serde::{Deserialize, Serialize};

use crate::car::Input;
use crate::math::{clamp, cos, deg, exp, sin, sqrt, wrap_angle};

pub const WIDTH: f32 = 1400.0;
pub const HEIGHT: f32 = 900.0;
pub const GOAL_WIDTH: f32 = 280.0;
pub const GOAL_HEIGHT: f32 = 105.0;
pub const GOAL_DEPTH: f32 = 80.0;
pub const BALL_RADIUS: f32 = 26.0;
pub const CAR_RADIUS: f32 = 15.0;
pub const WIN_SCORE: u8 = 5;
pub const MATCH_TIME: f32 = 180.0;
pub const MAGNET_RANGE: f32 = 240.0;
const GRAVITY: f32 = 230.0;
const CEILING: f32 = 300.0;
/// a lost connection or backgrounded tab can't keep holding the accelerator
const INPUT_TIMEOUT: f32 = 0.35;

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum SoccerPhase {
    Waiting,
    Kickoff,
    Playing,
    Goal,
    Finished,
}

/// the big line of text across the screen
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Announce {
    WaitingForPlayers,
    PlayerLeft,
    Kickoff,
    Go,
    Overtime,
    BlueScores,
    OrangeScores,
    BlueWins,
    OrangeWins,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum PickupKind {
    /// +25 boost
    BoostPad,
    /// a full tank
    FullBoost,
    /// six seconds of extra speed without spending boost
    Nitro,
    /// the next ball hit within eight seconds is much stronger
    PowerShot,
    /// pulls a nearby ball toward the front bumper for six seconds
    Magnet,
}

impl PickupKind {
    pub fn respawn(self) -> f32 {
        match self {
            PickupKind::BoostPad => 4.0,
            PickupKind::FullBoost => 10.0,
            _ => 12.0,
        }
    }

    pub fn radius(self) -> f32 {
        match self {
            PickupKind::BoostPad => 14.0,
            PickupKind::FullBoost => 24.0,
            _ => 20.0,
        }
    }
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SoccerPickup {
    pub x: f32,
    pub y: f32,
    pub kind: PickupKind,
    pub cooldown: f32,
}

/// every pickup's place on the pitch (the same for everyone)
pub fn pickups() -> Vec<SoccerPickup> {
    use PickupKind::*;
    [
        (440.0, 450.0, BoostPad),
        (960.0, 450.0, BoostPad),
        (350.0, 220.0, BoostPad),
        (700.0, 220.0, BoostPad),
        (1050.0, 220.0, BoostPad),
        (350.0, 680.0, BoostPad),
        (700.0, 680.0, BoostPad),
        (1050.0, 680.0, BoostPad),
        (140.0, 130.0, FullBoost),
        (1260.0, 130.0, FullBoost),
        (140.0, 770.0, FullBoost),
        (1260.0, 770.0, FullBoost),
        (430.0, 160.0, Nitro),
        (970.0, 740.0, Nitro),
        (510.0, 450.0, PowerShot),
        (890.0, 450.0, PowerShot),
        (700.0, 110.0, Magnet),
        (700.0, 790.0, Magnet),
    ]
    .into_iter()
    .map(|(x, y, kind)| SoccerPickup { x, y, kind, cooldown: 0.0 })
    .collect()
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SoccerCar {
    pub slot: u8,
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub vx: f32,
    pub vy: f32,
    pub vz: f32,
    /// radians, 0 = toward the orange goal
    pub heading: f32,
    pub boost: f32,
    pub throttle: f32,
    pub steer: f32,
    pub boosting: bool,
    jump_queued: bool,
    jump_held: bool,
    pub nitro_time: f32,
    pub shot_time: f32,
    pub magnet_time: f32,
    /// boost or nitro firing this tick (for the flame)
    pub boost_active: bool,
    /// counts pickups collected, so every client can show a toast once per pickup
    pub pickup_serial: u32,
    pub last_pickup: Option<PickupKind>,
    input_age: f32,
}

impl SoccerCar {
    fn new(slot: u8) -> SoccerCar {
        SoccerCar {
            slot,
            x: 0.0,
            y: 0.0,
            z: 0.0,
            vx: 0.0,
            vy: 0.0,
            vz: 0.0,
            heading: 0.0,
            boost: 100.0,
            throttle: 0.0,
            steer: 0.0,
            boosting: false,
            jump_queued: false,
            jump_held: false,
            nitro_time: 0.0,
            shot_time: 0.0,
            magnet_time: 0.0,
            boost_active: false,
            pickup_serial: 0,
            last_pickup: None,
            input_age: 0.0,
        }
    }

    /// 0 = blue, 1 = orange
    pub fn team(&self) -> u8 {
        self.slot % 2
    }

    pub fn apply_input(&mut self, input: &Input) {
        self.throttle = clamp(finite(input.gas as u8 as f32 - input.brake as u8 as f32), -1.0, 1.0);
        self.steer = clamp(finite(input.steer), -1.0, 1.0);
        self.boosting = input.boost;
        self.jump_queued |= input.jump && !self.jump_held;
        self.jump_held = input.jump;
        self.input_age = 0.0;
    }

    /// Drives this car alone for one tick, the way the match would (no ball, no other cars):
    /// a player's game uses it to predict their own car between server updates.
    pub fn predict(&mut self, input: &Input, delta: f32) {
        self.apply_input(input);
        let steps = (crate::math::ceil(delta * 120.0) as u32).max(1);
        for _ in 0..steps {
            drive(self, delta / steps as f32);
            constrain(self);
        }
    }
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SoccerBall {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub vx: f32,
    pub vy: f32,
    pub vz: f32,
}

impl Default for SoccerBall {
    fn default() -> SoccerBall {
        SoccerBall { x: WIDTH / 2.0, y: HEIGHT / 2.0, z: BALL_RADIUS, vx: 0.0, vy: 0.0, vz: 0.0 }
    }
}

/// The whole match. It is also the snapshot the server sends every tick.
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SoccerMatch {
    /// players per team: 1 or 2
    pub team_size: u8,
    pub cars: Vec<Option<SoccerCar>>,
    pub pickups: Vec<SoccerPickup>,
    pub ball: SoccerBall,
    pub blue: u8,
    pub orange: u8,
    pub phase: SoccerPhase,
    /// seconds left on the match clock
    pub remaining: f32,
    /// seconds left of a kickoff countdown or goal celebration
    pub phase_time: f32,
    pub overtime: bool,
    pub announce: Announce,
    pub tick: u64,
}

impl SoccerMatch {
    pub fn new(team_size: u8) -> SoccerMatch {
        let team_size = team_size.clamp(1, 2);
        SoccerMatch {
            team_size,
            cars: vec![None; team_size as usize * 2],
            pickups: pickups(),
            ball: SoccerBall::default(),
            blue: 0,
            orange: 0,
            phase: SoccerPhase::Waiting,
            remaining: MATCH_TIME,
            phase_time: 0.0,
            overtime: false,
            announce: Announce::WaitingForPlayers,
            tick: 0,
        }
    }

    pub fn count(&self) -> usize {
        self.cars.iter().flatten().count()
    }

    pub fn full(&self) -> bool {
        self.count() == self.cars.len()
    }

    /// the lowest free slot, if any
    pub fn free_slot(&self) -> Option<u8> {
        self.cars.iter().position(Option::is_none).map(|s| s as u8)
    }

    /// puts a player in a slot; a full match kicks off
    pub fn add(&mut self, slot: u8) -> bool {
        match self.cars.get_mut(slot as usize) {
            Some(c @ None) => *c = Some(SoccerCar::new(slot)),
            _ => return false,
        }
        self.reset_positions();
        if self.full() {
            self.restart();
        }
        true
    }

    pub fn remove(&mut self, slot: u8) {
        if let Some(c) = self.cars.get_mut(slot as usize) {
            *c = None;
            self.phase = SoccerPhase::Waiting;
            self.announce = Announce::PlayerLeft;
            self.reset_positions();
        }
    }

    /// a new match with the same players
    pub fn restart(&mut self) {
        if !self.full() {
            return;
        }
        self.blue = 0;
        self.orange = 0;
        self.remaining = MATCH_TIME;
        self.overtime = false;
        self.kickoff();
    }

    fn kickoff(&mut self) {
        self.reset_positions();
        self.phase = SoccerPhase::Kickoff;
        self.phase_time = 3.0;
        self.announce = Announce::Kickoff;
    }

    fn reset_positions(&mut self) {
        for p in &mut self.pickups {
            p.cooldown = 0.0;
        }
        self.ball = SoccerBall::default();
        let two = self.team_size == 2;
        for car in self.cars.iter_mut().flatten() {
            let serial = car.pickup_serial;
            let last = car.last_pickup;
            *car = SoccerCar::new(car.slot);
            car.pickup_serial = serial;
            car.last_pickup = last;
            let blue = car.team() == 0;
            car.x = if blue { 260.0 } else { WIDTH - 260.0 };
            car.y = HEIGHT / 2.0 + if !two { 0.0 } else if car.slot / 2 == 0 { -145.0 } else { 145.0 };
            car.heading = if blue { 0.0 } else { core::f32::consts::PI };
        }
    }

    /// a player's controls: gas forward, brake back, steering, boost and jump
    pub fn input(&mut self, slot: u8, input: &Input) {
        if let Some(car) = self.cars.get_mut(slot as usize).and_then(|c| c.as_mut()) {
            car.apply_input(input);
        }
    }

    pub fn step(&mut self, delta: f32) {
        if !delta.is_finite() || delta <= 0.0 {
            return;
        }
        let delta = delta.min(0.1);
        self.tick += 1;
        match self.phase {
            SoccerPhase::Waiting | SoccerPhase::Finished => return,
            SoccerPhase::Kickoff | SoccerPhase::Goal => {
                self.phase_time -= delta;
                if self.phase_time <= 0.0 {
                    if self.phase == SoccerPhase::Goal {
                        self.kickoff();
                    } else {
                        self.phase = SoccerPhase::Playing;
                        self.announce = if self.overtime { Announce::Overtime } else { Announce::Go };
                    }
                }
                return;
            }
            SoccerPhase::Playing => {}
        }
        self.remaining = (self.remaining - delta).max(0.0);
        let steps = (crate::math::ceil(delta * 120.0) as u32).max(1);
        let dt = delta / steps as f32;
        for _ in 0..steps {
            if self.phase != SoccerPhase::Playing {
                break;
            }
            self.physics(dt);
        }
        if self.phase == SoccerPhase::Playing && self.remaining <= 0.0 {
            if self.blue != self.orange {
                self.finish();
            } else {
                self.overtime = true;
                self.announce = Announce::Overtime;
            }
        }
    }

    fn physics(&mut self, dt: f32) {
        for p in &mut self.pickups {
            p.cooldown = (p.cooldown - dt).max(0.0);
        }
        for i in 0..self.cars.len() {
            let Some(car) = self.cars[i].as_mut() else { continue };
            drive(car, dt);
            constrain(car);
            collect(car, &mut self.pickups);
        }
        for i in 0..self.cars.len() {
            for j in i + 1..self.cars.len() {
                let (left, right) = self.cars.split_at_mut(j);
                if let (Some(a), Some(b)) = (left[i].as_mut(), right[0].as_mut()) {
                    collide_cars(a, b);
                }
            }
        }
        for car in self.cars.iter().flatten().filter(|c| c.magnet_time > 0.0) {
            attract(&mut self.ball, car, dt);
        }
        let ball = &mut self.ball;
        ball.vz -= GRAVITY * dt;
        ball.x += ball.vx * dt;
        ball.y += ball.vy * dt;
        ball.z += ball.vz * dt;
        let drag = exp(-(if ball.z <= BALL_RADIUS + 1.0 { 0.24 } else { 0.025 }) * dt);
        ball.vx *= drag;
        ball.vy *= drag;
        if ball.z < BALL_RADIUS {
            ball.z = BALL_RADIUS;
            ball.vz = if ball.vz.abs() > 12.0 { ball.vz.abs() * 0.62 } else { 0.0 };
        }
        if ball.z > CEILING - BALL_RADIUS {
            ball.z = CEILING - BALL_RADIUS;
            ball.vz = -ball.vz.abs() * 0.8;
        }
        for car in self.cars.iter_mut().flatten() {
            hit_ball(&mut self.ball, car);
        }
        let ball = &mut self.ball;
        if ball.y < BALL_RADIUS {
            ball.y = BALL_RADIUS;
            ball.vy = ball.vy.abs() * 0.82;
        }
        if ball.y > HEIGHT - BALL_RADIUS {
            ball.y = HEIGHT - BALL_RADIUS;
            ball.vy = -ball.vy.abs() * 0.82;
        }
        let mouth = (ball.y - HEIGHT / 2.0).abs() < GOAL_WIDTH / 2.0 - BALL_RADIUS && ball.z + BALL_RADIUS < GOAL_HEIGHT;
        if mouth && ball.x < -BALL_RADIUS {
            self.score(1);
        } else if mouth && ball.x > WIDTH + BALL_RADIUS {
            self.score(0);
        } else if !mouth && ball.x < BALL_RADIUS {
            ball.x = BALL_RADIUS;
            ball.vx = ball.vx.abs() * 0.82;
        } else if !mouth && ball.x > WIDTH - BALL_RADIUS {
            ball.x = WIDTH - BALL_RADIUS;
            ball.vx = -ball.vx.abs() * 0.82;
        }
    }

    fn score(&mut self, team: u8) {
        if team == 0 {
            self.blue += 1;
        } else {
            self.orange += 1;
        }
        self.announce = if team == 0 { Announce::BlueScores } else { Announce::OrangeScores };
        if self.overtime || self.blue >= WIN_SCORE || self.orange >= WIN_SCORE {
            self.finish();
        } else {
            self.phase = SoccerPhase::Goal;
            self.phase_time = 2.5;
        }
    }

    fn finish(&mut self) {
        self.phase = SoccerPhase::Finished;
        self.announce = if self.blue > self.orange { Announce::BlueWins } else { Announce::OrangeWins };
        for c in self.cars.iter_mut().flatten() {
            c.throttle = 0.0;
            c.steer = 0.0;
            c.boosting = false;
            c.boost_active = false;
        }
    }
}

fn drive(car: &mut SoccerCar, dt: f32) {
    car.nitro_time = (car.nitro_time - dt).max(0.0);
    car.shot_time = (car.shot_time - dt).max(0.0);
    car.magnet_time = (car.magnet_time - dt).max(0.0);
    car.input_age += dt;
    if car.input_age > INPUT_TIMEOUT {
        car.throttle = 0.0;
        car.steer = 0.0;
        car.boosting = false;
        car.jump_queued = false;
    }
    let (fx, fy) = (cos(car.heading), sin(car.heading));
    let mut forward = car.vx * fx + car.vy * fy;
    let mut sideways = -car.vx * fy + car.vy * fx;
    let boost = car.boosting && car.boost > 0.0 && car.throttle > 0.0;
    let nitro = car.nitro_time > 0.0 && car.throttle > 0.0;
    car.boost_active = boost || nitro;
    if car.throttle != 0.0 {
        let acceleration = if car.throttle * forward < -5.0 {
            650.0
        } else if nitro {
            680.0
        } else if boost {
            480.0
        } else {
            300.0
        };
        forward += car.throttle * acceleration * dt;
    } else {
        forward *= exp(-1.5 * dt);
    }
    forward = clamp(forward, -150.0, if nitro { 760.0 } else if boost { 560.0 } else { 380.0 });
    sideways *= exp(-12.0 * dt);
    let refill = if boost && !nitro { -30.0 } else { 14.0 };
    car.boost = clamp(car.boost + refill * dt, 0.0, 100.0);
    let reverse = if forward < 0.0 { -1.0 } else { 1.0 };
    car.heading = wrap_angle(car.heading + car.steer * deg(125.0) * (forward.abs() / 110.0).min(1.0) * reverse * dt);
    let (fx, fy) = (cos(car.heading), sin(car.heading));
    car.vx = fx * forward - fy * sideways;
    car.vy = fy * forward + fx * sideways;
    if car.jump_queued && car.z < 0.1 {
        car.vz = 100.0;
    }
    car.jump_queued = false;
    car.vz -= GRAVITY * dt;
    car.z = (car.z + car.vz * dt).max(0.0);
    if car.z == 0.0 {
        car.vz = 0.0;
    }
    car.x += car.vx * dt;
    car.y += car.vy * dt;
}

fn collect(car: &mut SoccerCar, pickups: &mut [SoccerPickup]) {
    if car.z > 12.0 {
        return;
    }
    for p in pickups.iter_mut().filter(|p| p.cooldown <= 0.0) {
        let (dx, dy) = (car.x - p.x, car.y - p.y);
        let r = CAR_RADIUS + p.kind.radius();
        if dx * dx + dy * dy > r * r {
            continue;
        }
        match p.kind {
            PickupKind::BoostPad | PickupKind::FullBoost if car.boost >= 99.9 => continue,
            PickupKind::BoostPad => car.boost = (car.boost + 25.0).min(100.0),
            PickupKind::FullBoost => car.boost = 100.0,
            PickupKind::Nitro => car.nitro_time = 6.0,
            PickupKind::PowerShot => car.shot_time = 8.0,
            PickupKind::Magnet => car.magnet_time = 6.0,
        }
        p.cooldown = p.kind.respawn();
        car.pickup_serial += 1;
        car.last_pickup = Some(p.kind);
    }
}

/// Pulls the ball toward a point in front of the bumper, not through the car. Several magnets
/// add up; none of them teleports the ball or claims it.
fn attract(ball: &mut SoccerBall, car: &SoccerCar, dt: f32) {
    let (bx, by) = (ball.x - car.x, ball.y - car.y);
    if bx * bx + by * by > MAGNET_RANGE * MAGNET_RANGE || (ball.z - car.z).abs() > 150.0 {
        return;
    }
    let dx = car.x + cos(car.heading) * 65.0 - ball.x;
    let dy = car.y + sin(car.heading) * 65.0 - ball.y;
    let dz = car.z + 32.0 - ball.z;
    let length = sqrt(dx * dx + dy * dy + dz * dz);
    if length < 1.0 {
        return;
    }
    let force = (length * 5.0).min(720.0) * dt / length;
    ball.vx += dx * force;
    ball.vy += dy * force;
    ball.vz += dz * force;
}

/// keeps a car on the pitch (or inside a goal mouth)
fn constrain(c: &mut SoccerCar) {
    let (mut low, mut high) = (CAR_RADIUS, WIDTH - CAR_RADIUS);
    if (c.y - HEIGHT / 2.0).abs() < GOAL_WIDTH / 2.0 - CAR_RADIUS {
        low -= GOAL_DEPTH;
        high += GOAL_DEPTH;
    }
    let x = clamp(c.x, low, high);
    let y = clamp(c.y, CAR_RADIUS, HEIGHT - CAR_RADIUS);
    if x != c.x {
        c.vx *= -0.2;
    }
    if y != c.y {
        c.vy *= -0.2;
    }
    c.x = x;
    c.y = y;
}

fn collide_cars(a: &mut SoccerCar, b: &mut SoccerCar) {
    let (mut dx, mut dy) = (b.x - a.x, b.y - a.y);
    let mut dist = sqrt(dx * dx + dy * dy);
    if dist >= 2.0 * CAR_RADIUS || (a.z - b.z).abs() > 20.0 {
        return;
    }
    if dist < 0.001 {
        (dx, dy, dist) = (1.0, 0.0, 1.0);
    }
    let (nx, ny) = (dx / dist, dy / dist);
    let push = (2.0 * CAR_RADIUS - dist) / 2.0;
    a.x -= nx * push;
    a.y -= ny * push;
    b.x += nx * push;
    b.y += ny * push;
    let closing = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
    if closing < 0.0 {
        let impulse = -closing * 0.65;
        a.vx -= nx * impulse;
        a.vy -= ny * impulse;
        b.vx += nx * impulse;
        b.vy += ny * impulse;
    }
    constrain(a);
    constrain(b);
}

fn hit_ball(ball: &mut SoccerBall, car: &mut SoccerCar) {
    let (mut dx, mut dy, mut dz) = (ball.x - car.x, ball.y - car.y, ball.z - (car.z + 8.0));
    let mut dist = sqrt(dx * dx + dy * dy + dz * dz);
    let radius = BALL_RADIUS + CAR_RADIUS;
    if dist >= radius {
        return;
    }
    if dist < 0.001 {
        (dx, dy, dz, dist) = (1.0, 0.0, 0.0, 1.0);
    }
    let (nx, ny, nz) = (dx / dist, dy / dist, dz / dist);
    ball.x += nx * (radius - dist);
    ball.y += ny * (radius - dist);
    ball.z += nz * (radius - dist);
    let closing = (ball.vx - car.vx) * nx + (ball.vy - car.vy) * ny + (ball.vz - car.vz) * nz;
    if closing < 0.0 {
        let impulse = -closing * 1.65;
        ball.vx += nx * impulse;
        ball.vy += ny * impulse;
        ball.vz += nz * impulse;
        car.vx -= nx * impulse * 0.12;
        car.vy -= ny * impulse * 0.12;
        if car.shot_time > 0.0 && closing < -25.0 {
            let flat = sqrt(nx * nx + ny * ny);
            if flat > 0.1 {
                ball.vx += nx / flat * 260.0;
                ball.vy += ny / flat * 260.0;
                ball.vz = (ball.vz + 18.0).min(100.0);
                car.shot_time = 0.0;
            }
        }
    }
}

fn finite(v: f32) -> f32 {
    if v.is_finite() {
        v
    } else {
        0.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GAS: Input = Input {
        gas: true,
        brake: false,
        boost: false,
        steer: 0.0,
        item: false,
        automatic: false,
        backwards: false,
        jump: false,
    };

    fn started(size: u8) -> SoccerMatch {
        let mut m = SoccerMatch::new(size);
        for i in 0..size * 2 {
            m.add(i);
        }
        for _ in 0..70 {
            m.step(0.05);
        }
        assert_eq!(m.phase, SoccerPhase::Playing);
        m
    }

    fn car(m: &mut SoccerMatch, slot: usize) -> &mut SoccerCar {
        m.cars[slot].as_mut().unwrap()
    }

    #[test]
    fn one_versus_one_waits_for_opponent_then_starts_countdown() {
        let mut m = SoccerMatch::new(1);
        m.add(0);
        m.step(0.05);
        assert_eq!(m.phase, SoccerPhase::Waiting);
        m.add(1);
        assert_eq!(m.phase, SoccerPhase::Kickoff);
        assert_eq!((car(&mut m, 0).team(), car(&mut m, 1).team()), (0, 1));
    }

    #[test]
    fn car_can_strike_ball_and_score_for_blue() {
        let mut m = started(1);
        car(&mut m, 1).y = 700.0;
        let mut hit = false;
        for _ in 0..240 {
            if m.blue > 0 {
                break;
            }
            m.input(0, &Input { boost: true, ..GAS });
            m.step(0.05);
            hit |= m.ball.vx > 50.0;
        }
        assert!(hit, "car transfers momentum to ball");
        assert_eq!((m.blue, m.orange), (1, 0));
    }

    #[test]
    fn walls_bounce_ball_but_goals_only_count_inside_the_mouth() {
        let mut m = started(1);
        m.ball.x = WIDTH - 27.0;
        m.ball.y = 100.0;
        m.ball.vx = 200.0;
        m.step(0.05);
        assert!(m.ball.vx < 0.0);
        assert_eq!(m.blue, 0);
        m.ball = SoccerBall { x: WIDTH + 27.0, ..SoccerBall::default() };
        m.step(0.05);
        assert_eq!(m.blue, 1);
        assert_eq!(m.phase, SoccerPhase::Goal);
        for _ in 0..30 {
            m.step(0.05);
        }
        assert_eq!(m.blue, 1, "a goal only counts once");
    }

    #[test]
    fn high_shots_hit_the_back_wall_above_the_crossbar() {
        let mut m = started(1);
        m.ball = SoccerBall { x: WIDTH - 27.0, z: 150.0, vx: 200.0, ..SoccerBall::default() };
        m.step(0.05);
        assert!(m.ball.vx < 0.0);
        assert_eq!(m.blue, 0);
    }

    #[test]
    fn goal_resets_both_cars_and_ball_for_another_kickoff() {
        let mut m = started(1);
        m.ball.x = -27.0;
        m.step(0.05);
        for _ in 0..52 {
            m.step(0.05);
        }
        assert_eq!(m.orange, 1);
        assert_eq!(m.phase, SoccerPhase::Kickoff);
        assert_eq!(m.ball.x, 700.0);
        assert_eq!(car(&mut m, 0).x, 260.0);
    }

    #[test]
    fn jump_boost_and_input_timeout_work_without_leaving_arena() {
        let mut m = started(1);
        for i in 0..10 {
            m.input(0, &Input { boost: true, jump: i == 0, ..GAS });
            m.step(0.05);
        }
        assert!(car(&mut m, 0).z > 0.0);
        assert!(car(&mut m, 0).boost < 100.0);
        for _ in 0..100 {
            m.step(0.05);
        }
        let c = car(&mut m, 0);
        assert_eq!((c.throttle, c.z), (0.0, 0.0));
        assert!(c.x >= -GOAL_DEPTH);
    }

    #[test]
    fn timer_finishes_winner_and_ties_go_to_sudden_death() {
        let mut m = started(1);
        m.blue = 2;
        m.remaining = 0.02;
        m.step(0.05);
        assert_eq!(m.phase, SoccerPhase::Finished);
        m.restart();
        for _ in 0..65 {
            m.step(0.05);
        }
        m.remaining = 0.02;
        m.step(0.05);
        assert!(m.overtime);
        m.ball.x = WIDTH + 27.0;
        m.step(0.05);
        assert_eq!(m.phase, SoccerPhase::Finished);
    }

    #[test]
    fn invalid_inputs_cannot_poison_authoritative_state() {
        let mut m = started(1);
        m.input(0, &Input { steer: f32::INFINITY, ..GAS });
        m.input(1, &Input { steer: f32::NAN, ..GAS });
        m.step(0.05);
        for c in m.cars.iter().flatten() {
            assert!(c.x.is_finite() && c.heading.is_finite());
        }
    }

    #[test]
    fn two_versus_two_balances_teams_and_waits_for_all_four() {
        let mut m = SoccerMatch::new(2);
        m.add(0);
        m.add(1);
        m.add(2);
        assert_eq!(m.phase, SoccerPhase::Waiting);
        m.add(3);
        assert_eq!(m.phase, SoccerPhase::Kickoff);
        assert_eq!(car(&mut m, 0).team(), car(&mut m, 2).team());
        assert_eq!(car(&mut m, 1).team(), car(&mut m, 3).team());
        assert!((car(&mut m, 0).y - car(&mut m, 2).y).abs() > 1.0);
        m.remove(2);
        assert_eq!(m.phase, SoccerPhase::Waiting);
        m.add(2);
        assert_eq!(m.phase, SoccerPhase::Kickoff);
    }

    fn collect_kind(m: &mut SoccerMatch, kind: PickupKind) -> usize {
        let i = m.pickups.iter().position(|p| p.kind == kind).unwrap();
        let (x, y) = (m.pickups[i].x, m.pickups[i].y);
        let c = car(m, 0);
        (c.x, c.y, c.vx, c.vy) = (x, y, 0.0, 0.0);
        m.step(0.05);
        assert!(m.pickups[i].cooldown > 0.0, "pickup enters cooldown");
        i
    }

    #[test]
    fn boost_pads_refill_once_then_respawn() {
        let mut m = started(1);
        car(&mut m, 0).boost = 0.0;
        let i = collect_kind(&mut m, PickupKind::BoostPad);
        assert!((car(&mut m, 0).boost - 25.0).abs() < 1.0);
        let serial = car(&mut m, 0).pickup_serial;
        for _ in 0..20 {
            m.step(0.05);
        }
        assert_eq!(car(&mut m, 0).pickup_serial, serial, "unavailable pad cannot be collected twice");
        car(&mut m, 0).x = 250.0;
        for _ in 0..65 {
            m.step(0.05);
        }
        assert_eq!(m.pickups[i].cooldown, 0.0);
        let x = m.pickups[i].x;
        let c = car(&mut m, 0);
        (c.boost, c.x) = (0.0, x);
        m.step(0.05);
        assert_eq!(car(&mut m, 0).pickup_serial, serial + 1);
    }

    #[test]
    fn full_boost_cannot_be_wasted_at_full_fuel() {
        let mut m = started(1);
        let i = m.pickups.iter().position(|p| p.kind == PickupKind::FullBoost).unwrap();
        let (x, y) = (m.pickups[i].x, m.pickups[i].y);
        let c = car(&mut m, 0);
        (c.x, c.y) = (x, y);
        m.step(0.05);
        assert_eq!(m.pickups[i].cooldown, 0.0);
        car(&mut m, 0).boost = 10.0;
        m.step(0.05);
        assert_eq!(car(&mut m, 0).boost, 100.0);
        assert!(m.pickups[i].cooldown > 9.0);
    }

    #[test]
    fn simultaneous_pickup_has_only_one_winner() {
        let mut m = started(1);
        let (x, y) = (m.pickups[0].x, m.pickups[0].y);
        for c in m.cars.iter_mut().flatten() {
            (c.x, c.y, c.boost) = (x, y, 0.0);
        }
        m.step(0.05);
        assert_eq!(car(&mut m, 0).pickup_serial + car(&mut m, 1).pickup_serial, 1);
    }

    #[test]
    fn cars_must_be_near_the_ground_to_collect() {
        let mut m = started(1);
        let (x, y) = (m.pickups[0].x, m.pickups[0].y);
        let c = car(&mut m, 0);
        (c.x, c.y, c.z, c.boost) = (x, y, 30.0, 0.0);
        m.step(0.05);
        assert_eq!(m.pickups[0].cooldown, 0.0);
        assert_eq!(car(&mut m, 0).pickup_serial, 0);
    }

    #[test]
    fn nitro_raises_speed_without_draining_boost_and_expires() {
        let mut m = started(1);
        collect_kind(&mut m, PickupKind::Nitro);
        let c = car(&mut m, 0);
        (c.boost, c.heading) = (50.0, 0.0);
        for _ in 0..20 {
            m.input(0, &Input { boost: true, ..GAS });
            m.step(0.05);
        }
        assert!(car(&mut m, 0).vx > 560.0);
        assert!(car(&mut m, 0).boost >= 50.0);
        for _ in 0..110 {
            m.step(0.05);
        }
        assert_eq!(car(&mut m, 0).nitro_time, 0.0);
    }

    #[test]
    fn power_shot_strengthens_one_ball_hit_then_is_consumed() {
        let mut powered = started(1);
        collect_kind(&mut powered, PickupKind::PowerShot);
        let mut normal = started(1);
        for m in [&mut powered, &mut normal] {
            let c = car(m, 0);
            (c.x, c.y, c.vx) = (800.0, 450.0, 200.0);
            m.ball.x = 830.0;
            m.ball.y = 450.0;
            m.input(0, &GAS);
            m.step(0.05);
        }
        assert!(powered.ball.vx > normal.ball.vx + 200.0);
        assert_eq!(car(&mut powered, 0).shot_time, 0.0);
    }

    #[test]
    fn magnet_applies_force_only_within_range() {
        let mut m = started(1);
        collect_kind(&mut m, PickupKind::Magnet);
        let c = car(&mut m, 0);
        (c.x, c.y, c.heading) = (500.0, 100.0, 0.0);
        m.ball.x = 650.0;
        m.ball.y = 100.0;
        m.step(0.05);
        assert!(m.ball.vx < -5.0, "ball accelerates toward the bumper");
        m.ball = SoccerBall { x: 1000.0, y: 100.0, ..SoccerBall::default() };
        m.step(0.05);
        assert_eq!(m.ball.vx, 0.0, "out-of-range ball is unaffected");
    }

    #[test]
    fn pickups_and_effects_reset_on_rematch() {
        let mut m = started(1);
        collect_kind(&mut m, PickupKind::Nitro);
        assert!(car(&mut m, 0).nitro_time > 0.0);
        m.restart();
        assert!(m.pickups.iter().all(|p| p.cooldown == 0.0));
        let c = car(&mut m, 0);
        assert_eq!((c.nitro_time, c.shot_time, c.magnet_time), (0.0, 0.0, 0.0));
    }

    #[test]
    fn waiting_rooms_cannot_collect_pickups() {
        let mut m = SoccerMatch::new(1);
        m.add(0);
        let (x, y) = (m.pickups[0].x, m.pickups[0].y);
        let c = car(&mut m, 0);
        (c.x, c.y, c.boost) = (x, y, 0.0);
        m.step(0.1);
        assert_eq!(car(&mut m, 0).pickup_serial, 0);
        assert_eq!(m.pickups[0].cooldown, 0.0);
    }
}
