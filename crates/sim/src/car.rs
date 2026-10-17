//! Car physics, the same for human players and CPUs: engine, brakes, speed-dependent
//! steering, tire grip (a little drift when boosting), slow grass off the road, sliding along
//! walls, item effects, and car-to-car bumps.
//!
//! Distances are map pixels and times seconds. On the speedometer 1 mph = 2.5 px/s: about
//! 0-60 in 2 s, 0-100 in 4.5 s, top speed about 190 mph (about 235 mph boosting).

use core::f32::consts::PI;

use serde::{Deserialize, Serialize};

use crate::math::{approach_zero, clamp, cos, exp, signum, sin, sqrt, Vec2};
use crate::track::Track;

/// a car's collision shape: a capsule as long and wide as the car sprite
pub const WIDTH: f32 = 10.0;
pub const LENGTH: f32 = 20.0;
const RADIUS: f32 = WIDTH / 2.0;
/// half the length of the capsule's straight middle part
const HALF_SPINE: f32 = (LENGTH - WIDTH) / 2.0;
/// the radius kept clear of walls (half the car's length)
pub const WALL_RADIUS: f32 = LENGTH / 2.0;

// The engine works like a real one: at low speed the tires limit how hard the car can launch
// (TRACTION); once moving, engine power divided by speed limits it, so the pull fades the
// faster you go. Air drag grows with speed squared and sets the top speed.
const TRACTION: f32 = 100.0;
const ENGINE_POWER: f32 = 12000.0;
const BOOST_TRACTION: f32 = 130.0;
const BOOST_POWER_MULT: f32 = 1.7;
const BRAKE_DECEL: f32 = 420.0;
const REVERSE_ACCEL: f32 = 170.0;
const REVERSE_TOP_SPEED: f32 = 90.0;
/// tire rolling resistance, always there
const ROLLING_DRAG: f32 = 8.0;
/// extra slow-down from the engine when you let off the gas
const ENGINE_BRAKING: f32 = 22.0;
/// wind resistance, grows with speed squared
pub const AIR_DRAG: f32 = 0.000077;
/// fastest the car can rotate, radians per second (200 degrees)
pub const MAX_YAW_RATE: f32 = 200.0 * PI / 180.0;
/// how quickly the wheels turn toward the pressed direction
const STEER_RESPONSE: f32 = 8.0;
/// how strongly the tires stop sideways sliding (higher = grippier)
const GRIP: f32 = 9.0;
const BOOST_GRIP: f32 = 5.5;
/// top speed on grass, about 46 mph, and how hard the grass slows a faster car to it
const GRASS_TOP_SPEED: f32 = 115.0;
const GRASS_DRAG: f32 = 520.0;
const GRASS_GRIP: f32 = 4.0;
/// fraction of speed kept when hitting a wall hard
const WALL_SCRAPE: f32 = 0.6;
/// extra slow-down per second while grinding along a wall
const SCRAPE_FRICTION: f32 = 2.5;
/// how bouncy car-to-car hits are (0 = dead stop, 1 = full bounce)
const CAR_BOUNCE: f32 = 0.3;
/// a hit at more than this closing speed makes a crash sound
pub const CRASH_SPEED: f32 = 60.0;
/// an automatic car creeps along at idle with no pedal pressed (about 5 mph)
const IDLE_CREEP: f32 = 12.0;
const CREEP_ACCEL: f32 = 30.0;
const MANA_REGEN: f32 = 10.0;
const BOOST_COST: f32 = 20.0;
pub const MAX_MANA: f32 = 100.0;
/// physics sub-steps per simulation tick (60 Hz ticks, 120 Hz physics)
pub const SUBSTEPS: u32 = 2;

pub const SPIN_TIME: f32 = 1.0;
pub const NITRO_TIME: f32 = 1.3;
const NITRO_KICK: f32 = 150.0;

/// One tick of controls. Keyboard cars drive arcade style (brake, then reverse once stopped);
/// touch controls drive like an automatic gearbox (see `automatic`).
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Input {
    pub gas: bool,
    pub brake: bool,
    pub boost: bool,
    /// -1 full right .. 1 full left
    pub steer: f32,
    /// use the held item (a press; held blasters keep firing)
    pub item: bool,
    /// touch controls: an automatic gearbox, with idle creep and no reverse on the brake
    pub automatic: bool,
    /// touch controls: the joystick pulled back, so gas reverses
    pub backwards: bool,
}

impl Input {
    /// packs into two bytes for the network: flags, and steering as -127..127
    pub fn pack(&self) -> [u8; 2] {
        let flags = self.gas as u8
            | (self.brake as u8) << 1
            | (self.boost as u8) << 2
            | (self.item as u8) << 3
            | (self.automatic as u8) << 4
            | (self.backwards as u8) << 5;
        [flags, (clamp(self.steer, -1.0, 1.0) * 127.0) as i8 as u8]
    }

    pub fn unpack(b: [u8; 2]) -> Input {
        Input {
            gas: b[0] & 1 != 0,
            brake: b[0] & 2 != 0,
            boost: b[0] & 4 != 0,
            item: b[0] & 8 != 0,
            automatic: b[0] & 16 != 0,
            backwards: b[0] & 32 != 0,
            steer: b[1] as i8 as f32 / 127.0,
        }
    }
}

/// How a car performs, relative to the standard car (1 = standard).
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct Spec {
    /// how hard the tires can launch the car
    pub traction: f32,
    /// engine power: sets the top speed
    pub power: f32,
    /// how fast it turns and how well the tires hold
    pub handling: f32,
    /// heavier cars shove lighter ones, and get shoved less
    pub mass: f32,
}

impl Spec {
    pub const STANDARD: Spec = Spec { traction: 1.0, power: 1.0, handling: 1.0, mass: 1.0 };

    /// the speed where engine power and air drag balance
    pub fn top_speed(&self) -> f32 {
        crate::math::cbrt(ENGINE_POWER * self.power / AIR_DRAG)
    }
}

/// A garage car. Car numbers 1.. pick one: model = (number - 1) / 3, paint = (number - 1) % 3.
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Debug, Serialize)]
pub struct CarModel {
    pub id: &'static str,
    pub name: &'static str,
    pub tagline: &'static str,
    pub spec: Spec,
}

pub const PAINTS: u8 = 3;

pub static MODELS: [CarModel; 5] = [
    CarModel {
        id: "sprinter",
        name: "Sprinter",
        tagline: "Light and nimble. Off the line first, out of puff on the straights.",
        spec: Spec { traction: 1.35, power: 0.86, handling: 1.18, mass: 0.75 },
    },
    CarModel {
        id: "roadster",
        name: "Roadster",
        tagline: "Balanced in every way. A good car to learn on.",
        spec: Spec { traction: 1.0, power: 1.0, handling: 1.0, mass: 1.0 },
    },
    CarModel {
        id: "gt",
        name: "GT",
        tagline: "The fastest flat out, but it takes a while to get there.",
        spec: Spec { traction: 0.82, power: 1.3, handling: 0.95, mass: 0.95 },
    },
    CarModel {
        id: "muscle",
        name: "Muscle",
        tagline: "Big power and a hard launch. Slides around in the corners.",
        spec: Spec { traction: 1.15, power: 1.18, handling: 0.78, mass: 1.25 },
    },
    CarModel {
        id: "hauler",
        name: "Hauler",
        tagline: "Slow as a fridge, but nobody pushes it around. Bump everyone.",
        spec: Spec { traction: 0.72, power: 0.82, handling: 0.9, mass: 2.0 },
    },
];

/// the model of a car number (anything unknown gives the Roadster)
pub fn model_of(car: u8) -> &'static CarModel {
    let m = (car as usize).wrapping_sub(1) / PAINTS as usize;
    MODELS.get(m).unwrap_or(&MODELS[1])
}

/// how many car numbers there are
pub fn car_count() -> u8 {
    MODELS.len() as u8 * PAINTS
}

/// One car's physical state.
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Car {
    /// center of the car
    pub pos: Vec2,
    pub vel: Vec2,
    /// radians, 0 = +x (right), PI/2 = up
    pub heading: f32,
    pub spec: Spec,
    /// how far the wheels are turned, -1..1
    pub steer: f32,
    /// the boost tank, 0..MAX_MANA
    pub mana: f32,
    pub boosting: bool,
    pub on_grass: bool,
    // item effects, in seconds left
    pub spin: f32,
    spin_start: f32,
    pub slow: f32,
    pub nitro: f32,
    pub freeze: f32,
    pub burn: f32,
    /// a crash sound at most a couple of times a second
    crash_cooldown: f32,
    /// set when the car hit something hard this tick (for the crash sound)
    pub crashed: bool,
}

/// the map's surface: the moon and the ice are slippery
#[derive(Clone, Copy, Debug)]
pub struct Surface {
    pub grip: f32,
    pub traction: f32,
}

impl Car {
    pub fn new(pos: Vec2, heading: f32, spec: Spec) -> Car {
        Car {
            pos,
            vel: Vec2::ZERO,
            heading,
            spec,
            steer: 0.0,
            mana: MAX_MANA,
            boosting: false,
            on_grass: false,
            spin: 0.0,
            spin_start: 0.0,
            slow: 0.0,
            nitro: 0.0,
            freeze: 0.0,
            burn: 0.0,
            crash_cooldown: 0.0,
            crashed: false,
        }
    }

    pub fn forward(&self) -> Vec2 {
        Vec2::from_angle(self.heading)
    }

    /// signed speed along the way the car points
    pub fn forward_speed(&self) -> f32 {
        self.vel.dot(self.forward())
    }

    pub fn speed(&self) -> f32 {
        self.vel.len()
    }

    /// true while the driver has no control (spun out or frozen)
    pub fn stunned(&self) -> bool {
        self.spin > 0.0 || self.freeze > 0.0
    }

    /// engine power with items: more on nitro, cut while slowed by a pulse or burning
    fn item_power(&self) -> f32 {
        (if self.nitro > 0.0 { 2.2 } else { 1.0 }) * (if self.slow > 0.0 { 0.35 } else { 1.0 })
    }

    /// hit by a Bottle Rocket or an Oil Slick: skid, spin round twice, no control
    pub fn spin_out(&mut self) {
        if self.spin > 0.0 {
            return;
        }
        self.spin = SPIN_TIME;
        self.spin_start = self.heading;
        self.vel = self.vel * 0.6;
        self.crash();
    }

    /// hit by a Static Pulse: the engine loses power for a while
    pub fn slow_down(&mut self, seconds: f32) {
        self.slow = self.slow.max(seconds);
    }

    /// hit by a Frost Blaster shot: frozen solid for a moment
    pub fn frost(&mut self, seconds: f32) {
        self.freeze = seconds;
    }

    /// hit by a Fire Blaster shot: knocked back to half speed, and down on power while it burns
    pub fn ignite(&mut self, seconds: f32) {
        self.vel = self.vel * 0.5;
        self.burn = seconds;
        self.slow = self.slow.max(seconds);
    }

    /// used a Nitro: a kick of speed and extra power for a moment
    pub fn use_nitro(&mut self) {
        self.nitro = NITRO_TIME;
        self.vel += self.forward() * NITRO_KICK;
    }

    /// stops the car dead (knocked out of a battle)
    pub fn wreck(&mut self) {
        self.vel = Vec2::ZERO;
        self.spin = 0.0;
        self.freeze = 0.0;
    }

    fn crash(&mut self) {
        if self.crash_cooldown <= 0.0 {
            self.crashed = true;
            self.crash_cooldown = 0.5;
        }
    }

    /// One simulation tick of `dt` seconds: timers, item effects, then the physics in
    /// SUBSTEPS equal steps so the car handles the same at any tick length.
    pub fn tick(&mut self, dt: f32, input: &Input, track: &Track, surface: Surface) {
        self.crashed = false;
        self.crash_cooldown -= dt;
        self.slow -= dt;
        self.nitro -= dt;
        // frozen by a Frost Blaster: an icy block sliding to a stop, no control
        if self.freeze > 0.0 {
            self.freeze -= dt;
            self.vel = self.vel * exp(-1.6 * dt);
            self.boosting = false;
            self.move_with_walls(dt, track);
            return;
        }
        if self.burn > 0.0 {
            self.burn -= dt;
        }
        // spun out: skid, spin round twice, no control until it's over
        if self.spin > 0.0 {
            self.spin -= dt;
            self.heading += 4.0 * PI / SPIN_TIME * dt;
            self.vel = self.vel * exp(-2.5 * dt);
            self.boosting = false;
            self.move_with_walls(dt, track);
            if self.spin <= 0.0 {
                self.heading = self.spin_start;
            }
            return;
        }
        let step = dt / SUBSTEPS as f32;
        for _ in 0..SUBSTEPS {
            self.step(step, input, track, surface);
        }
    }

    fn step(&mut self, dt: f32, input: &Input, track: &Track, surface: Surface) {
        // split velocity into "along the car" and "sideways" parts
        let f = self.forward();
        let mut forward = self.vel.dot(f);
        let mut sideways = self.vel.dot(f.perp());
        let gas = input.gas;

        self.boosting = gas && input.boost && self.mana > 0.0;
        self.mana = if self.boosting {
            (self.mana - BOOST_COST * dt).max(0.0)
        } else {
            (self.mana + MANA_REGEN * dt).min(MAX_MANA)
        };

        let mut creeping = false;
        if input.automatic {
            (forward, creeping) = self.automatic_gearbox(dt, input, forward, surface);
        } else if input.brake {
            forward = if forward > 5.0 {
                (forward - BRAKE_DECEL * dt).max(0.0)
            } else {
                // stopped and still holding brake: reverse
                (forward - REVERSE_ACCEL * dt).max(-REVERSE_TOP_SPEED)
            };
        } else if gas {
            if forward < 0.0 {
                // going backwards: gas acts as a brake first
                forward = (forward + BRAKE_DECEL * dt).min(0.0);
            } else {
                forward += self.engine_pull(forward, surface) * dt;
            }
        } else {
            forward = approach_zero(forward, ENGINE_BRAKING * dt);
        }
        forward = approach_zero(forward, ROLLING_DRAG * dt);
        forward -= signum(forward) * AIR_DRAG * forward * forward * dt;

        // grass: way slower. Anything over the grass top speed is scrubbed off quickly, so
        // cutting across the grass doesn't pay
        self.on_grass = !track.on_road(self.pos);
        if self.on_grass && forward.abs() > GRASS_TOP_SPEED {
            forward = signum(forward) * GRASS_TOP_SPEED.max(forward.abs() - GRASS_DRAG * dt);
        }

        // tires resist sliding sideways; less grip while boosting (a little drift) and on grass
        let tires = if self.on_grass {
            GRASS_GRIP
        } else {
            (if self.boosting { BOOST_GRIP } else { GRIP }) * self.spec.handling
        };
        let grip = tires * surface.grip;
        sideways *= exp(-grip * dt);

        self.vel = f * forward + f.perp() * sideways;

        // steering: wheels turn smoothly, and a car can only rotate while it's moving
        self.steer += (clamp(input.steer, -1.0, 1.0) - self.steer) * (STEER_RESPONSE * dt).min(1.0);
        let speed = forward.abs();
        let direction = if forward >= 0.0 { 1.0 } else { -1.0 }; // steering flips when reversing
        self.heading += self.steer * MAX_YAW_RATE * self.spec.handling * yaw_factor(speed) * direction * dt;
        if speed < 1.0 && !gas && !creeping {
            self.vel = Vec2::ZERO;
        }

        self.move_with_walls(dt, track);
    }

    /// how hard the engine pulls forward at this speed
    fn engine_pull(&self, forward: f32, surface: Surface) -> f32 {
        let traction = (if self.boosting { BOOST_TRACTION } else { TRACTION }) * self.spec.traction * surface.traction;
        let power =
            ENGINE_POWER * self.spec.power * (if self.boosting { BOOST_POWER_MULT } else { 1.0 }) * self.item_power();
        (traction * if self.nitro > 0.0 { 1.8 } else { 1.0 }).min(power / forward.max(1.0))
    }

    /// How a real automatic responds to its pedals (touch controls):
    ///  - gas: the engine pulls forward; with the joystick pulled back it reverses (slowly)
    ///  - brake: slows the car to a stop and holds it there; it never reverses
    ///  - no pedal: the engine idles, coasting down and then creeping at walking pace
    fn automatic_gearbox(&self, dt: f32, input: &Input, mut forward: f32, surface: Surface) -> (f32, bool) {
        let mut creeping = false;
        if input.brake {
            forward = approach_zero(forward, BRAKE_DECEL * dt);
        } else if input.gas && input.backwards {
            forward = if forward > 0.0 {
                approach_zero(forward, BRAKE_DECEL * dt)
            } else {
                (forward - REVERSE_ACCEL * dt).max(-REVERSE_TOP_SPEED)
            };
        } else if input.gas {
            if forward < 0.0 {
                forward = approach_zero(forward, BRAKE_DECEL * dt);
            } else {
                forward += self.engine_pull(forward, surface) * dt;
            }
        } else if !input.backwards && (0.0..IDLE_CREEP).contains(&forward) {
            creeping = true;
            forward += (IDLE_CREEP - forward).min(CREEP_ACCEL * dt);
        } else {
            forward = approach_zero(forward, ENGINE_BRAKING * dt);
        }
        (forward, creeping)
    }

    /// moves the car; on hitting a wall it slides along it instead of bouncing backwards
    fn move_with_walls(&mut self, dt: f32, track: &Track) {
        let next = self.pos + self.vel * dt;
        if track.in_bounds(next, WALL_RADIUS) {
            self.pos = next;
            return;
        }
        let impact;
        if track.in_bounds(Vec2::new(next.x, self.pos.y), WALL_RADIUS) {
            // wall is above or below: keep sliding sideways along it
            impact = self.vel.y.abs();
            self.vel.y = 0.0;
            self.vel.x *= exp(-SCRAPE_FRICTION * dt);
            self.pos.x = next.x;
        } else if track.in_bounds(Vec2::new(self.pos.x, next.y), WALL_RADIUS) {
            impact = self.vel.x.abs();
            self.vel.x = 0.0;
            self.vel.y *= exp(-SCRAPE_FRICTION * dt);
            self.pos.y = next.y;
        } else {
            // head-on: stop with a small knock back
            impact = self.vel.len();
            self.vel = self.vel * -0.2;
        }
        if impact > CRASH_SPEED {
            self.vel = self.vel * WALL_SCRAPE;
            self.crash();
        }
    }

    /// moves the car by `d` if it fits there, else half of it, else not at all
    fn nudge(&mut self, d: Vec2, track: &Track) {
        if track.in_bounds(self.pos + d, WALL_RADIUS) {
            self.pos += d;
        } else if track.in_bounds(self.pos + d * 0.5, WALL_RADIUS) {
            self.pos += d * 0.5;
        }
    }
}

/// how much of the full turn rate a car gets at this speed: none standing still, all of it
/// at 70 px/s, then a little less the faster it goes
pub fn yaw_factor(speed: f32) -> f32 {
    clamp(speed / 70.0, 0.0, 1.0) / (1.0 + speed / 550.0)
}

/// How far car A has to move to stop touching car B (zero if they don't touch): the closest
/// points between the two capsules' spines.
pub fn separation(a_pos: Vec2, a_heading: f32, b_pos: Vec2, b_heading: f32) -> Option<Vec2> {
    let d = a_pos - b_pos;
    if d.len2() > LENGTH * LENGTH {
        return None;
    }
    let ad = Vec2::new(cos(a_heading), sin(a_heading)) * HALF_SPINE;
    let bd = Vec2::new(cos(b_heading), sin(b_heading)) * HALF_SPINE;
    let a0 = a_pos - ad;
    let b0 = b_pos - bd;
    let u = ad * 2.0;
    let v = bd * 2.0;
    let w = a0 - b0;
    let (uu, uv, vv) = (u.dot(u), u.dot(v), v.dot(v));
    let (uw, vw) = (u.dot(w), v.dot(w));
    let denom = uu * vv - uv * uv;
    let mut s = if denom > 1e-6 { clamp((uv * vw - vv * uw) / denom, 0.0, 1.0) } else { 0.0 };
    let t = clamp((uv * s + vw) / vv, 0.0, 1.0);
    s = clamp((uv * t - uw) / uu, 0.0, 1.0);
    let mut p = (a0 + u * s) - (b0 + v * t);
    let mut dist = p.len();
    let overlap = 2.0 * RADIUS - dist;
    if overlap <= 0.0 {
        return None;
    }
    if dist < 1e-4 {
        // exactly on top of each other: push apart along the line between centers, or sideways
        p = d;
        dist = p.len();
        if dist < 1e-4 {
            p = Vec2::new(1.0, 0.0);
            dist = 1.0;
        }
    }
    Some(p * (overlap / dist))
}

/// Pushes two touching cars apart and trades the speed they were closing at: each takes the
/// other's share of the total weight, so a heavy car hardly budges and rear-ending a slower
/// car shoves it along. Returns the closing speed (0 if they don't touch or are separating).
pub fn collide(a: &mut Car, b: &mut Car, track: &Track) -> f32 {
    let Some(push) = separation(a.pos, a.heading, b.pos, b.heading) else {
        return 0.0;
    };
    let total = a.spec.mass + b.spec.mass;
    let a_share = b.spec.mass / total;
    let b_share = a.spec.mass / total;
    // a tiny gap so the cars end up just apart, not exactly touching
    let len = push.len();
    let push = push * ((len + 0.05) / len);
    a.nudge(push * a_share, track);
    b.nudge(-push * b_share, track);
    let n = push * (1.0 / sqrt(push.len2()));
    let into = (a.vel - b.vel).dot(n); // negative = closing
    if into >= 0.0 {
        return 0.0;
    }
    a.vel -= n * (into * a_share * (1.0 + CAR_BOUNCE));
    b.vel += n * (into * b_share * (1.0 + CAR_BOUNCE));
    if -into > CRASH_SPEED {
        a.crash();
        b.crash();
    }
    -into
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::track::Mask;

    fn open_track() -> Track {
        Track::new(Mask::from_fn(400, 400, |_, _| true), 1, false)
    }

    const NORMAL: Surface = Surface { grip: 1.0, traction: 1.0 };

    fn drive(car: &mut Car, input: Input, seconds: f32, track: &Track) {
        for _ in 0..(seconds * 60.0) as usize {
            car.tick(1.0 / 60.0, &input, track, NORMAL);
        }
    }

    #[test]
    fn standard_car_reaches_sixty_in_about_two_seconds() {
        let track = Track::new(Mask::from_fn(100, 20000, |_, _| true), 1, false);
        let mut car = Car::new(Vec2::new(50.0, 20.0), PI / 2.0, Spec::STANDARD);
        drive(&mut car, Input { gas: true, ..Input::default() }, 2.0, &track);
        let mph = car.speed() / 2.5;
        assert!((50.0..70.0).contains(&mph), "{mph} mph after 2 s");
        drive(&mut car, Input { gas: true, ..Input::default() }, 40.0, &track);
        let top = car.speed() / 2.5;
        assert!((180.0..200.0).contains(&top), "top speed {top} mph");
    }

    #[test]
    fn brake_then_reverse() {
        let track = open_track();
        let mut car = Car::new(Vec2::new(200.0, 200.0), PI / 2.0, Spec::STANDARD);
        drive(&mut car, Input { brake: true, ..Input::default() }, 1.0, &track);
        assert!(car.forward_speed() < -50.0);
    }

    #[test]
    fn steering_turns_left_for_positive_input() {
        let track = open_track();
        let mut car = Car::new(Vec2::new(200.0, 100.0), PI / 2.0, Spec::STANDARD);
        drive(&mut car, Input { gas: true, steer: 1.0, ..Input::default() }, 0.5, &track);
        assert!(car.heading > PI / 2.0);
    }

    #[test]
    fn capsules_overlap_side_by_side_but_not_apart() {
        let up = PI / 2.0;
        let push = separation(Vec2::new(0.0, 0.0), up, Vec2::new(8.0, 0.0), up).unwrap();
        assert!((push.x + 2.0).abs() < 1e-4 && push.y.abs() < 1e-4);
        assert!(separation(Vec2::new(0.0, 0.0), up, Vec2::new(11.0, 0.0), up).is_none());
        assert!(separation(Vec2::new(0.0, 0.0), up, Vec2::new(0.0, 21.0), up).is_none());
    }

    #[test]
    fn heavy_car_shoves_a_light_one() {
        let track = open_track();
        let mut hauler = Car::new(Vec2::new(200.0, 200.0), 0.0, MODELS[4].spec);
        let mut sprinter = Car::new(Vec2::new(208.0, 200.0), PI / 2.0, MODELS[0].spec);
        hauler.vel = Vec2::new(200.0, 0.0);
        let closing = collide(&mut hauler, &mut sprinter, &track);
        assert!(closing > 0.0);
        assert!(sprinter.vel.x > 150.0, "sprinter knocked to {:?}", sprinter.vel);
        assert!(hauler.vel.x > 100.0);
    }

    #[test]
    fn input_packs_into_two_bytes() {
        let input = Input { gas: true, boost: true, steer: -0.5, automatic: true, ..Input::default() };
        let back = Input::unpack(input.pack());
        assert_eq!((back.gas, back.boost, back.automatic, back.brake), (true, true, true, false));
        assert!((back.steer + 0.5).abs() < 0.01);
    }

    #[test]
    fn car_numbers_pick_models() {
        assert_eq!(model_of(1).id, "sprinter");
        assert_eq!(model_of(15).id, "hauler");
        assert_eq!(model_of(0).id, "roadster");
        assert_eq!(model_of(99).id, "roadster");
    }
}
