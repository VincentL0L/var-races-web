//! CPU drivers. A CPU drives the same car physics as a human player: each tick it looks at
//! the course and the traffic and decides what to press (gas, brake, steering).
//!
//! Every CPU gets its own random personality (top speed, cornering, preferred line, reaction
//! time) and picks a slightly different line through each corner every lap, so races play out
//! differently each time.

use serde::{Deserialize, Serialize};

use crate::car::{yaw_factor, Car, Input, Spec, LENGTH, MAX_YAW_RATE, WIDTH, WALL_RADIUS};
use crate::maps::{MapDef, MapKind};
use crate::math::{angle_diff, clamp, deg, lerp, sqrt, Vec2};
use crate::rng::Rng;
use crate::track::Track;

/// how far off the middle of the road a CPU may drive (the road is about 125 wide)
const MAX_LINE_OFFSET: f32 = 28.0;
/// seconds off the road before a CPU is put back on it
const RESCUE_TIME: f32 = 5.0;
/// how strongly a CPU steers toward where it wants to point (per second)
const STEER_GAIN: f32 = 5.0;

/// what a CPU can see of another car
#[derive(Clone, Copy, Debug)]
pub struct Other {
    pub id: u8,
    pub pos: Vec2,
    pub heading: f32,
    pub vel: Vec2,
    /// still racing (or still in the battle), so worth following, passing or hunting
    pub active: bool,
}

/// One CPU's personality: picked once, scaled by the difficulty.
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
struct Personality {
    top_speed: f32,
    braking: f32,
    /// degrees per second it is willing to turn at speed
    turn_rate: f32,
    /// how tight a corner it aims to drive at speed (smaller = braver)
    corner_radius: f32,
    /// -1 prefers the left side of the road, 1 the right
    line_bias: f32,
    /// seconds before it reacts to GO
    reaction: f32,
    acceleration: f32,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Driver {
    p: Personality,
    sprint: bool,
    battle: bool,
    /// true: keeps lapping forever (the title screen's demo race)
    endless: bool,
    laps: u8,
    waypoint: usize,
    lap_count: u8,
    stop_next: bool,
    /// done: pulled up past the finish, or wrecked
    pub stopped: bool,
    // the point it's aiming at: the waypoint moved sideways onto its chosen line, plus an extra
    // sideways shift while it's passing another car
    target: Vec2,
    base_point: Vec2,
    left_of_travel: Vec2,
    line_offset: f32,
    pass_shift: f32,
    pass_side: f32,
    pass_hold: f32,
    arrive_radius: f32,
    throttle_noise: f32,
    // small random wandering so CPUs don't drive perfectly straight (degrees per second)
    wobble: f32,
    wobble_target: f32,
    wobble_timer: f32,
    off_road_time: f32,
    // battle
    retarget: f32,
    back_up: f32,
    stuck: f32,
    hunting: Option<u8>,
    progress_anchor: Vec2,
    #[cfg(test)]
    pub(crate) rescues: u32,
}

impl Driver {
    pub fn new(map: &MapDef, rng: &mut Rng) -> Driver {
        let p = Personality {
            top_speed: rng.range(400.0, 455.0),
            acceleration: rng.range(85.0, 110.0),
            braking: rng.range(340.0, 420.0),
            turn_rate: rng.range(150.0, 200.0),
            corner_radius: rng.range(48.0, 60.0),
            line_bias: rng.range(-1.0, 1.0),
            reaction: rng.range(0.05, 0.45),
        };
        let mut d = Driver {
            p,
            sprint: map.kind == MapKind::Sprint,
            battle: map.kind == MapKind::Battle,
            endless: false,
            laps: 1,
            waypoint: 0,
            lap_count: 0,
            stop_next: false,
            stopped: false,
            target: Vec2::ZERO,
            base_point: Vec2::ZERO,
            left_of_travel: Vec2::ZERO,
            line_offset: 0.0,
            pass_shift: 0.0,
            pass_side: 0.0,
            pass_hold: 0.0,
            arrive_radius: 50.0,
            throttle_noise: 1.0,
            wobble: 0.0,
            wobble_target: 0.0,
            wobble_timer: 0.0,
            off_road_time: 0.0,
            retarget: 0.0,
            back_up: 0.0,
            stuck: 0.0,
            hunting: None,
            progress_anchor: Vec2::ZERO,
            #[cfg(test)]
            rescues: 0,
        };
        d.pick_target(map, rng);
        d
    }

    /// Makes this CPU weaker or stronger: 0 easy, 1 normal, 2 hard. Call once, before the race.
    pub fn set_difficulty(&mut self, difficulty: u8) {
        let p = &mut self.p;
        match difficulty {
            0 => {
                // slower, softer on the gas, brakes early and takes corners wide
                p.top_speed *= 0.84;
                p.acceleration *= 0.8;
                p.turn_rate *= 0.9;
                p.corner_radius *= 1.2;
                p.reaction += 0.3;
            }
            2 => {
                // a little faster than you, quick off the line and brave in the corners
                p.top_speed *= 1.08;
                p.acceleration *= 1.3;
                p.braking *= 1.1;
                p.turn_rate *= 1.12;
                p.corner_radius *= 0.88;
                p.reaction *= 0.4;
            }
            _ => {}
        }
    }

    pub fn set_laps(&mut self, laps: u8) {
        self.laps = laps.max(1);
    }

    pub fn set_endless(&mut self, endless: bool) {
        self.endless = endless;
    }

    /// The car this CPU drives: as quick off the line as its personality, with just enough
    /// engine to reach its top speed (it lifts off before the limit).
    pub fn spec(&self) -> Spec {
        let standard_top = Spec::STANDARD.top_speed();
        let power = (self.p.top_speed * 1.08 / standard_top).powi(3);
        Spec {
            traction: self.p.acceleration / 100.0,
            power,
            handling: self.p.turn_rate / 180.0,
            mass: 1.0,
        }
    }

    /// Decides this tick's controls. `others` is every other car in the race.
    pub fn drive(
        &mut self,
        dt: f32,
        car: &mut Car,
        others: &[Other],
        map: &MapDef,
        track: &Track,
        rng: &mut Rng,
    ) -> Input {
        if self.stopped {
            return Input { brake: car.forward_speed() > 5.0, ..Input::default() };
        }
        if self.p.reaction > 0.0 {
            self.p.reaction -= dt;
            return Input::default();
        }
        if car.stunned() {
            return Input::default();
        }
        self.update_wobble(dt, rng);
        if self.battle {
            return self.battle_drive(dt, car, others, track, rng);
        }

        // stuck off the road for a while (wedged in a corner, run wide): like a kart race's
        // rescue, it's put back on the course just before where it was heading
        if !track.on_road(car.pos) {
            self.off_road_time += dt;
            if self.off_road_time > RESCUE_TIME {
                self.rescue(car, map, rng);
                return Input::default();
            }
        } else {
            self.off_road_time = 0.0;
        }

        let pos = car.pos;
        if pos.dist(self.progress_anchor) > 15.0 {
            self.progress_anchor = pos;
            self.stuck = 0.0;
        } else if find_car_ahead(car, others, 65.0).is_none_or(|ahead| ahead.speed < 10.0) {
            self.stuck += dt;
        }
        if self.back_up > 0.0 {
            self.back_up -= dt;
            let diff = angle_diff((self.target - pos).angle(), car.heading);
            return Input { brake: true, steer: -diff.signum() * 0.7, ..Input::default() };
        }
        if self.stuck > 2.0 {
            self.stuck = 0.0;
            self.back_up = 0.7;
        }
        // reached the current waypoint: move on and pick a new line for the next one
        if pos.dist(self.target) < self.arrive_radius
            || pos.dist(map.waypoint(self.waypoint)) < self.arrive_radius
            || self.passed_corner(pos, map)
        {
            self.waypoint += 1;
            let n = map.waypoints.len();
            if self.waypoint == n && self.sprint {
                // a sprint: this was the run-off past the finish line, pull up here
                self.waypoint = n - 1;
                self.stopped = true;
                return Input { brake: true, ..Input::default() };
            }
            if self.waypoint == n {
                self.waypoint = 0;
                self.lap_count += 1;
                if self.lap_count >= self.laps && !self.endless {
                    self.stop_next = true;
                }
            }
            if self.stop_next && self.waypoint == 1 {
                self.stopped = true;
                return Input { brake: true, ..Input::default() };
            }
            self.pick_target(map, rng);
        }

        let speed = car.forward_speed().max(0.0);
        let heading = car.heading;
        let f = Vec2::from_angle(heading);

        // traffic: keep a gap to a car in front and pull out to the side to pass it
        let mut follow_speed = f32::MAX;
        let mut dodge = None;
        if let Some(ahead) = find_car_ahead(car, others, 50.0 + speed * 0.85) {
            // Stop behind blocked traffic; crawl only when a passing corridor is clear.
            follow_speed = (ahead.speed + (ahead.along - LENGTH - 18.0 - speed * 0.18) * 1.6).max(0.0);
            if self.pass_hold <= 0.0 {
                // car on our right: pass on the left, and the other way round
                self.pass_side = if ahead.lateral.abs() > 0.5 {
                    -ahead.lateral.signum()
                } else if self.p.line_bias >= 0.0 {
                    -1.0
                } else {
                    1.0
                };
            }
            self.pass_hold = 1.2; // keep the passing line until we're well past it
            // right behind it: steer straight for a spot beside it (whichever side has road)
            if ahead.along < 160.0 && (ahead.speed < 20.0 || !(self.corner_sharpness(map) > 0.35 && pos.dist(self.base_point) < 200.0)) {
                dodge = dodge_point(pos, f, ahead.pos, self.pass_side, track, others, speed)
                    .map(|p| (p, self.pass_side))
                    .or_else(|| dodge_point(pos, f, ahead.pos, -self.pass_side, track, others, speed).map(|p| (p, -self.pass_side)));
                match dodge {
                    Some((_, side)) => { self.pass_side = side; follow_speed = follow_speed.max(75.0); },
                    None => follow_speed = follow_speed.min(ahead.speed),
                }
            }
        }
        self.pass_hold -= dt;
        // no passing moves in the middle of a sharp corner: wait for the exit
        let corner = self.corner_sharpness(map);
        let in_corner = corner > 0.5 && pos.dist(self.base_point) < 140.0;
        let pass_target = if dodge.is_some() && self.pass_hold > 0.0 && !in_corner { self.pass_side * 30.0 } else { 0.0 };
        self.pass_shift += clamp(pass_target - self.pass_shift, -60.0 * dt, 60.0 * dt);
        self.update_target();

        // steer toward the target
        // Follow a point on the incoming segment, then approach the corner itself.
        // A short lookahead avoids cutting across the inside of tight bends.
        let from = self.point_before(map, self.waypoint);
        let segment = self.base_point - from;
        let length = segment.len().max(1.0);
        let along = clamp((pos - from).dot(segment) / length, 0.0, length);
        let look = clamp(35.0 + speed * 0.22, 45.0, 115.0);
        let center = from + segment * ((along + look).min(length) / length);
        let candidate = center + self.left_of_travel * clamp(self.line_offset + self.pass_shift, -MAX_LINE_OFFSET, MAX_LINE_OFFSET);
        let line = if safe_point(candidate, track) { candidate } else { center };
        let aim = dodge.map(|(p, _)| p).unwrap_or(line);
        let wanted = (aim - pos).angle();
        let diff = angle_diff(wanted, heading);
        // edge of the road coming up: steer back harder and ease off
        let near_edge = !safe_point(pos + f * (20.0 + speed * 0.2), track);
        let gain = if near_edge { STEER_GAIN * 1.8 } else { STEER_GAIN };
        let steer = self.steer_for(diff * gain + deg(self.wobble) * if near_edge || in_corner || follow_speed < f32::MAX { 0.0 } else { 1.0 }, speed, car.spec);

        // pick a speed: full speed on straights, slow down before sharp corners
        let top = self.p.top_speed;
        let mut desired = top * self.throttle_noise;
        let distance = pos.dist(self.base_point);
        if corner > 0.05 {
            let corner_speed = lerp(top, self.corner_speed(car.spec), corner);
            // brake early enough to reach the corner speed by the time we get there
            let brake_distance = ((speed * speed - corner_speed * corner_speed) / (2.0 * self.p.braking)).max(0.0);
            if distance < brake_distance + 65.0 {
                desired = desired.min(corner_speed);
            }
        }
        desired = desired.min(follow_speed);
        if car.nitro > 0.0 && desired >= top * self.throttle_noise - 1.0 {
            desired = top * 1.3; // nitro: faster on the straights (still brakes for corners)
        }
        if near_edge {
            desired = desired.min(speed * 0.85);
        }
        // a car pointed the wrong way slows down to turn around
        desired *= clamp(1.0 - diff.abs() / deg(120.0), 0.35, 1.0);
        if (self.stop_next && self.waypoint == 1) || (self.sprint && self.waypoint == map.waypoints.len() - 1) {
            desired *= (distance / 200.0).max(0.3); // easing up past the finish
        }
        pedals(speed, desired, steer)
    }

    /// the fastest speed at which the car can still turn on a circle of this CPU's corner radius
    fn corner_speed(&self, spec: Spec) -> f32 {
        // speed v turns at MAX_YAW * handling * yaw_factor(v) = v / radius; above 70 px/s
        // yaw_factor is 1 / (1 + v / 550), so v (1 + v / 550) = radius * max yaw
        let k = self.p.corner_radius * MAX_YAW_RATE * spec.handling;
        let v = (-1.0 + sqrt(1.0 + 4.0 * k / 550.0)) / (2.0 / 550.0);
        v.max(70.0)
    }

    /// the steering that turns the car at `rate` radians per second (if it can)
    fn steer_for(&self, rate: f32, speed: f32, spec: Spec) -> f32 {
        let max_rate = MAX_YAW_RATE * spec.handling * yaw_factor(speed.max(20.0));
        let turn = deg(self.p.turn_rate);
        clamp(clamp(rate, -turn, turn) / max_rate, -1.0, 1.0)
    }

    /// chooses where to aim for the current waypoint: off to one side of the middle of the road
    /// by a random amount (leaning toward this CPU's preferred side), and how early to turn in
    fn pick_target(&mut self, map: &MapDef, rng: &mut Rng) {
        let point = map.waypoint(self.waypoint);
        let dir = (point - self.point_before(map, self.waypoint)).norm();
        self.line_offset =
            clamp(self.p.line_bias * 14.0 + rng.range(-16.0, 16.0), -MAX_LINE_OFFSET, MAX_LINE_OFFSET);
        self.base_point = point;
        self.left_of_travel = dir.perp();
        self.update_target();
        self.arrive_radius = rng.range(40.0, 70.0);
        self.throttle_noise = rng.range(0.94, 1.0);
    }

    /// aim point = waypoint + sideways offset (own line plus passing shift)
    fn update_target(&mut self) {
        let offset = clamp(self.line_offset + self.pass_shift, -MAX_LINE_OFFSET, MAX_LINE_OFFSET);
        self.target = self.base_point + self.left_of_travel * offset;
    }

    /// True once the car is already past the current waypoint, heading down the next stretch
    /// (e.g. it cut inside the corner while passing someone), so it never turns back.
    fn passed_corner(&self, pos: Vec2, map: &MapDef) -> bool {
        let point = map.waypoint(self.waypoint);
        let (inward, outward) = self.corner_dirs(map);
        let r = pos - point;
        r.dot(inward + outward) > 0.0 && r.len2() < 160.0 * 160.0
    }

    /// 0 for a straight, up to 1 for a hairpin, at the current waypoint
    fn corner_sharpness(&self, map: &MapDef) -> f32 {
        let (inward, outward) = self.corner_dirs(map);
        clamp(1.0 - inward.dot(outward), 0.0, 1.0)
    }

    fn corner_dirs(&self, map: &MapDef) -> (Vec2, Vec2) {
        let point = map.waypoint(self.waypoint);
        let inward = (point - self.point_before(map, self.waypoint)).norm();
        let outward = (self.point_after(map, self.waypoint) - point).norm();
        (inward, outward)
    }

    /// the waypoint before i; on a sprint the start has an imaginary one straight behind it
    fn point_before(&self, map: &MapDef, i: usize) -> Vec2 {
        let n = map.waypoints.len();
        if self.sprint && i == 0 {
            let (a, b) = (map.waypoint(0), map.waypoint(1));
            return a - (b - a).norm() * 100.0;
        }
        map.waypoint((i + n - 1) % n)
    }

    /// the waypoint after i; on a sprint the end carries on straight
    fn point_after(&self, map: &MapDef, i: usize) -> Vec2 {
        let n = map.waypoints.len();
        if self.sprint && i == n - 1 {
            let (a, b) = (map.waypoint(n - 2), map.waypoint(n - 1));
            return b + (b - a).norm() * 100.0;
        }
        map.waypoint((i + 1) % n)
    }

    /// slowly drifting random steering, like a driver making small corrections
    fn update_wobble(&mut self, dt: f32, rng: &mut Rng) {
        self.wobble_timer -= dt;
        if self.wobble_timer <= 0.0 {
            self.wobble_timer = rng.range(0.4, 1.2);
            self.wobble_target = rng.range(-2.0, 2.0);
        }
        self.wobble += (self.wobble_target - self.wobble) * (3.0 * dt).min(1.0);
    }

    /// back onto the middle of the course, a little before the waypoint it was heading for
    fn rescue(&mut self, car: &mut Car, map: &MapDef, rng: &mut Rng) {
        #[cfg(test)] { self.rescues += 1; }
        let to = map.waypoint(self.waypoint);
        let from = self.point_before(map, self.waypoint);
        let d = to - from;
        let len = d.len().max(1.0);
        let dir = d * (1.0 / len);
        let back = (len * 0.5).min(160.0);
        car.pos = to - dir * back;
        car.heading = dir.angle();
        car.vel = Vec2::ZERO;
        self.off_road_time = 0.0;
        self.pick_target(map, rng);
    }

    /// Battle driving: pick a target (the nearest car, sometimes a random one), aim a little
    /// ahead of where it's going and floor it. After a crash or when stuck it backs off and
    /// lines up again. It steers round obstacles it sees coming.
    fn battle_drive(&mut self, dt: f32, car: &Car, others: &[Other], track: &Track, rng: &mut Rng) -> Input {
        self.retarget -= dt;
        let still_there = |id: Option<u8>| id.is_some_and(|id| others.iter().any(|o| o.id == id && o.active));
        if self.retarget <= 0.0 || !still_there(self.hunting) {
            self.hunting = pick_prey(car.pos, others, rng.chance(0.25), rng);
            self.retarget = rng.range(2.5, 5.0);
        }
        let speed = car.forward_speed();
        let away = if self.p.line_bias >= 0.0 { 1.0 } else { -1.0 };
        if self.back_up > 0.0 {
            // reversing out of a crash, turning as it goes (steering flips in reverse)
            self.back_up -= dt;
            return Input { brake: true, steer: -away * 0.6, ..Input::default() };
        }
        let heading = car.heading;
        let mut wanted = heading;
        if let Some(prey) = self.hunting.and_then(|id| others.iter().find(|o| o.id == id)) {
            let aim = prey.pos + prey.vel * 0.35;
            wanted = (aim - car.pos).angle();
        }
        // something solid coming up: swing toward whichever side is clear
        let look = 40.0 + speed.max(0.0) * 0.35;
        if !clear_ahead(car.pos, heading, look, track) {
            if clear_ahead(car.pos, heading + deg(50.0), look, track) {
                wanted = heading + deg(70.0);
            } else if clear_ahead(car.pos, heading - deg(50.0), look, track) {
                wanted = heading - deg(70.0);
            } else {
                self.back_up = 0.7;
            }
        }
        let diff = angle_diff(wanted, heading);
        let steer = self.steer_for(diff * STEER_GAIN, speed, car.spec);
        let mut desired = self.p.top_speed * 0.82 * clamp(1.0 - diff.abs() / deg(150.0), 0.45, 1.0);
        if car.nitro > 0.0 {
            desired = self.p.top_speed * 1.25;
        }
        // a big loss of speed (a crash) or no progress: back off and try again
        self.stuck = if speed < 40.0 { self.stuck + dt } else { 0.0 };
        if self.stuck > 0.8 {
            self.stuck = 0.0;
            self.back_up = 0.6;
        }
        pedals(speed.max(0.0), desired, steer)
    }
}

/// gas below the wanted speed, brake well above it, otherwise coast
fn pedals(speed: f32, desired: f32, steer: f32) -> Input {
    Input {
        gas: speed < desired - 2.0,
        brake: speed > desired + 12.0 && speed > 6.0,
        steer,
        ..Input::default()
    }
}

struct Ahead {
    along: f32,
    lateral: f32,
    speed: f32,
    pos: Vec2,
}

/// the nearest car in front, in this car's lane
fn find_car_ahead(me: &Car, others: &[Other], range: f32) -> Option<Ahead> {
    let f = me.forward();
    let mut best: Option<Ahead> = None;
    for o in others {
        let r = o.pos - me.pos;
        let along = r.dot(f);
        let lateral = -r.cross(f); // positive = to the left of travel
        if along > 0.0 && along < range && (lateral.abs().min((lateral + (o.vel - me.vel).dot(f.perp()) * 0.35).abs()) < WIDTH + 7.0) && best.as_ref().is_none_or(|b| along < b.along) {
            best = Some(Ahead { along, lateral, speed: o.vel.dot(f).max(0.0), pos: o.pos });
        }
    }
    best
}

/// A spot beside and a little past the car in front (side 1 = its left), if the whole way
/// there and a bit past it is road (no cutting across a corner).
fn dodge_point(pos: Vec2, f: Vec2, ahead: Vec2, side: f32, track: &Track, others: &[Other], speed: f32) -> Option<Vec2> {
    let p = ahead + f.perp() * (side * 32.0) + f * 25.0;
    let beyond = p + f * 20.0;
    for i in 1..=6 {
        let t = i as f32 / 6.0;
        let at = pos.lerp(p, t);
        let arrival = pos.dist(at) / speed.max(80.0);
        if !safe_point(at, track) || !safe_point(p.lerp(beyond, t), track)
            || others.iter().any(|other| at.dist(other.pos + other.vel * arrival) < LENGTH - 2.0) {
            return None;
        }
    }
    Some(p)
}

fn safe_point(p: Vec2, track: &Track) -> bool {
    track.on_road(p) && track.in_bounds(p, WALL_RADIUS + 4.0)
}

/// the nearest car still in (or a random one), to hunt in a battle
fn pick_prey(pos: Vec2, others: &[Other], random: bool, rng: &mut Rng) -> Option<u8> {
    let live: Vec<&Other> = others.iter().filter(|o| o.active).collect();
    if live.is_empty() {
        return None;
    }
    if random {
        return Some(live[rng.below(live.len())].id);
    }
    live.iter().min_by(|a, b| a.pos.dist2(pos).total_cmp(&b.pos.dist2(pos))).map(|o| o.id)
}

fn clear_ahead(pos: Vec2, dir: f32, distance: f32, track: &Track) -> bool {
    let f = Vec2::from_angle(dir);
    (1..=3).all(|i| track.on_road(pos + f * (distance * i as f32 / 3.0)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::car::Surface;
    use crate::maps;
    use crate::race::{Course, Standing};

    /// one CPU alone on a map: how long it takes to finish, if it does within the limit
    fn solo_time(map: &MapDef, seed: u64, limit: f32, difficulty: u8) -> Option<f32> {
        let track = maps::track(map);
        let mut rng = Rng::new(seed);
        let mut driver = Driver::new(map, &mut rng);
        driver.set_difficulty(difficulty);
        let spawn = maps::cpu_spawn(map, 0);
        let mut car = Car::new(spawn.pos, spawn.heading, driver.spec());
        let course = Course::new(map);
        let mut standing = Standing::default();
        let surface = Surface { grip: map.grip, traction: map.traction };
        let dt = 1.0 / 60.0;
        let mut t = 0.0;
        let mut crashes = 0;
        while t < limit {
            let input = driver.drive(dt, &mut car, &[], map, &track, &mut rng);
            car.tick(dt, &input, &track, surface);
            crashes += car.crashed as u32;
            t += dt;
            course.update(&mut standing, car.pos, t);
            if standing.finished() {
                assert!(crashes <= 3, "{} seed {seed} difficulty {difficulty}: {crashes} wall hits", map.id);
                assert_eq!(driver.rescues, 0, "{} seed {seed} difficulty {difficulty}: rescued", map.id);
                return Some(t);
            }
        }
        None
    }

    #[test]
    fn cpus_finish_every_course() {
        for map in maps::ALL.iter().filter(|m| m.kind != MapKind::Battle) {
            for seed in 0..3 {
                for difficulty in 0..3 {
                    let time = solo_time(map, seed, 240.0, difficulty);
                    assert!(time.is_some(), "{} seed {seed} difficulty {difficulty}: CPU never finished", map.id);
                }
            }
        }
    }
}
