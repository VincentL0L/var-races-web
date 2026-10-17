//! Paints the four sprint maps (one run from start to finish): Summit Sprint, Rush Hour
//! Getaway, Rooftop Run and Moon Base, plus the rover sprite and the map previews.
//!
//! Like the other maps every course starts at (200, 300) heading up, so the starting grid works
//! the same; the road also runs a little way back below the start for the grid. Roads can go at
//! any angle and change width along the way. Maps are painted at 1/3 size (the game scales them
//! up 3x); coordinates are world coordinates (y up) unless called ax / ay.
//!
//! The arithmetic follows the original's single-precision floats exactly, so the art comes out
//! the same pixel for pixel.

use std::path::Path;

use crate::cities::sprite;
use crate::image::{blend, rgb, Picture};
use crate::jrand::{round, JRandom};

const S: i32 = 3;
/// drivable run-off beside the road (the verge)
const WALK: f32 = 18.0;

const OUTLINE: u32 = rgb(0x221a17);
const WHITE: u32 = rgb(0xf6f3ea);
const BLACK: u32 = rgb(0x1b1b1f);
const LIT: u32 = rgb(0xffe58a);
const GLASS: u32 = rgb(0x9fd3f0);
const GLASS_DARK: u32 = rgb(0x6aa6cf);
const RED: u32 = rgb(0xd9433b);
const YELLOW: u32 = rgb(0xf2c84b);

/// half the road's width on each Rush Hour stretch
const RUSH_HALF: [f32; 18] = [60.0, 60.0, 60.0, 50.0, 54.0, 54.0, 54.0, 54.0, 54.0, 54.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0, 60.0];
/// which Rooftop Run stretches are sky bridges (narrow, with railings)
const ROOF_BRIDGES: [usize; 5] = [3, 7, 11, 15, 19];
/// the rooftops the course runs across: x0, y0, x1, y1, roof colour
const ROOF_TOPS: [[i32; 5]; 6] = [
    [60, 100, 860, 1060, 0xa58f7c],
    [1260, 760, 2290, 1560, 0x9aa5b1],
    [2690, 850, 3900, 1560, 0xb3a998],
    [4260, 340, 5170, 1160, 0x8796a6],
    [5560, 340, 6460, 1360, 0xa58f7c],
    [6610, 980, 7170, 1440, 0x9aa5b1],
];

/// one sprint map being painted
struct Sprint {
    w: i32,
    h: i32,
    aw: i32,
    ah: i32,
    art: Vec<u32>,
    /// art pixels taken by water or landmarks, so trees and rocks go around them
    blocked: Vec<bool>,
    rnd: JRandom,
    /// the road: from the grid (below the start) to the run-off past the finish
    pts: Vec<[f32; 2]>,
    /// half the road's width along each stretch
    half: Vec<f32>,
}

pub fn paint_all(root: &Path) -> Result<(), String> {
    let mut summit = Sprint::new("summit", 1920, 7200, 3000, 64.0, 44.0);
    summit.summit();
    summit.save(root, "summit")?;
    let mut rush = Sprint::new("rushhour", 3840, 3840, 1929, 60.0, 60.0);
    rush.half = RUSH_HALF.to_vec();
    rush.rush_hour();
    rush.save(root, "rushhour")?;
    let mut roof = Sprint::new("rooftop", 7200, 1800, 1990, 62.0, 62.0);
    roof.rooftop();
    roof.save(root, "rooftop")?;
    let mut moon = Sprint::new("moon", 5400, 3240, 1969, 64.0, 64.0);
    moon.moon();
    moon.save(root, "moon")?;
    rover().save(root.join("assets/ui/rover.png"), true)?;
    println!("sprint maps painted");
    Ok(())
}

/// the course of a sprint map: a stub below the start line for the grid, then the racing line
/// from sim::maps (start line to finish line to run-off)
fn course(id: &str) -> Vec<[f32; 2]> {
    let mut pts = vec![[200.0, 120.0]];
    pts.extend(sim::maps::get(id).waypoints.iter().map(|&(x, y)| [x, y]));
    pts
}

impl Sprint {
    fn new(id: &str, w: i32, h: i32, seed: i64, start_half: f32, end_half: f32) -> Sprint {
        let pts = course(id);
        let n = pts.len() - 1;
        let half = (0..n)
            .map(|i| start_half + (end_half - start_half) * i as f32 / (n as i32 - 1).max(1) as f32)
            .collect();
        let (aw, ah) = (w / S, h / S);
        Sprint {
            w,
            h,
            aw,
            ah,
            art: vec![0; (aw * ah) as usize],
            blocked: vec![false; (aw * ah) as usize],
            rnd: JRandom::new(seed),
            pts,
            half,
        }
    }

    // ================================================================== pixels

    fn set(&mut self, ax: i32, ay: i32, c: u32) {
        if ax >= 0 && ay >= 0 && ax < self.aw && ay < self.ah {
            self.art[(ay * self.aw + ax) as usize] = c;
        }
    }

    fn get(&self, ax: i32, ay: i32) -> u32 {
        if ax < 0 || ay < 0 || ax >= self.aw || ay >= self.ah {
            return 0xff00_0000;
        }
        self.art[(ay * self.aw + ax) as usize]
    }

    fn shade(&mut self, ax: i32, ay: i32, f: f64) {
        if ax >= 0 && ay >= 0 && ax < self.aw && ay < self.ah {
            let c = blend(self.get(ax, ay), rgb(0x000000), 1.0 - f);
            self.set(ax, ay, c);
        }
    }

    fn disc(&mut self, cx: i32, cy: i32, r: i32, c: u32) {
        for y in -r..=r {
            for x in -r..=r {
                if x * x + y * y <= r * r + r / 2 {
                    self.set(cx + x, cy + y, c);
                }
            }
        }
    }

    fn circle_outline(&mut self, cx: i32, cy: i32, r: i32, c: u32) {
        let mut a = 0;
        while a < 360 {
            let rad = (a as f64).to_radians();
            self.set(cx + round(r as f64 * rad.cos()), cy + round(r as f64 * rad.sin()), c);
            a += 4;
        }
    }

    fn outline(&mut self, ax: i32, ay: i32, w: i32, h: i32) {
        for x in -1..=w {
            self.set(ax + x, ay - 1, OUTLINE);
            self.set(ax + x, ay + h, OUTLINE);
        }
        for y in -1..=h {
            self.set(ax - 1, ay + y, OUTLINE);
            self.set(ax + w, ay + y, OUTLINE);
        }
    }

    fn block(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        for ay in (self.h - y1) / S..=(self.h - y0) / S {
            for ax in x0 / S..=x1 / S {
                if ax >= 0 && ay >= 0 && ax < self.aw && ay < self.ah {
                    self.blocked[(ay * self.aw + ax) as usize] = true;
                }
            }
        }
    }

    // ================================================================== geometry

    /// world point of an art pixel
    fn wx(&self, ax: i32) -> f32 {
        (ax * S) as f32 + 1.5
    }

    fn wy(&self, ay: i32) -> f32 {
        (self.h - ay * S) as f32 - 1.5
    }

    /// distance from (x, y) to the nearest road edge: negative on the road, positive off it
    fn edge(&self, x: f32, y: f32) -> f32 {
        let mut best = f32::MAX;
        for i in 0..self.pts.len() - 1 {
            let (a, b) = (self.pts[i], self.pts[i + 1]);
            let d = seg_dist(x, y, a[0], a[1], b[0], b[1]) - self.half[i];
            best = best.min(d);
        }
        best
    }

    /// room for scenery: well clear of the road
    fn clear(&self, x: f32, y: f32, margin: f32) -> bool {
        let ax = (x / S as f32) as i32;
        let ay = ((self.h as f32 - y) / S as f32) as i32;
        x > 0.0
            && y > 0.0
            && x < self.w as f32
            && y < self.h as f32
            && self.edge(x, y) > WALK + 4.0 + margin
            && (ax < 0 || ay < 0 || ax >= self.aw || ay >= self.ah || !self.blocked[(ay * self.aw + ax) as usize])
    }

    fn free_at(&self, ax: i32, ay: i32) -> bool {
        ax >= 0
            && ay >= 0
            && ax < self.aw
            && ay < self.ah
            && !self.blocked[(ay * self.aw + ax) as usize]
            && self.edge(self.wx(ax), self.wy(ay)) > WALK + 3.0
    }

    // ================================================================== SUMMIT SPRINT

    fn summit(&mut self) {
        let (w, h) = (self.w, self.h);
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                let y = self.wy(ay);
                let n = noise(ax, ay);
                // forest at the bottom, rock in the middle, snow at the top (dithered edges)
                let zone = y / h as f32 + (n - 0.5) * 0.06;
                let c = if zone < 0.36 {
                    let mut c = if (ax / 2 + ay / 2) % 2 == 0 { rgb(0x5e9e48) } else { rgb(0x56943f) };
                    if (ax * 7 + ay * 3) % 29 == 0 {
                        c = rgb(0x7cbc5a);
                    }
                    c
                } else if zone < 0.66 {
                    let mut c = if n > 0.6 {
                        rgb(0x9a8f80)
                    } else if n > 0.3 {
                        rgb(0x8a8072)
                    } else {
                        rgb(0x7c7366)
                    };
                    if (ax * 5 + ay * 11) % 37 == 0 {
                        c = rgb(0xb3a998);
                    }
                    c
                } else {
                    let mut c = if n > 0.55 { rgb(0xf4f7fb) } else { rgb(0xe3ebf3) };
                    if (ax * 3 + ay * 7) % 41 == 0 {
                        c = rgb(0xc9d6e4);
                    }
                    c
                };
                self.set(ax, ay, c);
            }
        }
        // a mountain lake in the forest, cliffs and boulders on the slopes
        self.lake(1000, 430, 230, 110);
        self.lake(1500, 1900, 160, 80);
        for _ in 0..160 {
            let x = self.rnd.next_int(w) as f32;
            let y = h as f32 * 0.36 + self.rnd.next_int((h as f32 * 0.3) as i32) as f32;
            if self.clear(x, y, 20.0) {
                let r = 3 + self.rnd.next_int(5);
                self.boulder((x / S as f32) as i32, ((h as f32 - y) / S as f32) as i32, r);
            }
        }
        for _ in 0..70 {
            let x = self.rnd.next_int(w) as f32;
            let y = h as f32 * 0.35 + self.rnd.next_int((h as f32 * 0.33) as i32) as f32;
            if self.clear(x, y, 40.0) {
                let size = 16 + self.rnd.next_int(18);
                self.cliff((x / S as f32) as i32, ((h as f32 - y) / S as f32) as i32, size);
            }
        }
        // pine trees: thick in the forest, thinning out, snowy up high
        for _ in 0..3600 {
            let x = self.rnd.next_int(w) as f32;
            let y = self.rnd.next_int(h) as f32;
            let zone = y / h as f32;
            if zone > 0.36 && self.rnd.next_float() < (zone - 0.36) * 3.2 {
                continue;
            }
            if zone > 0.8 {
                continue;
            }
            if self.clear(x, y, 8.0) {
                let size = 4 + self.rnd.next_int(3);
                self.pine((x / S as f32) as i32, ((h as f32 - y) / S as f32) as i32, zone > 0.6, size);
            }
        }
        self.paint_road(rgb(0x45474e), rgb(0x3f4147), rgb(0xf2c84b), false, true);
        self.observatory(1100, 6860);
        self.start_line();
        self.finish_line();
    }

    fn lake(&mut self, cx: i32, cy: i32, rx: i32, ry: i32) {
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                let (x, y) = (self.wx(ax), self.wy(ay));
                let d = (((x - cx as f32) / rx as f32) as f64).powi(2)
                    + (((y - cy as f32) / ry as f32) as f64).powi(2)
                    + 0.08 * (x as f64 / 23.0).sin();
                if d < 1.25 && ax >= 0 {
                    self.blocked[(ay * self.aw + ax) as usize] = true;
                }
                if d < 1.0 && self.edge(x, y) > WALK + 6.0 {
                    let c = if d > 0.85 {
                        rgb(0x8a8072)
                    } else if (ax * 5 + ay * 9) % 31 == 0 {
                        rgb(0xbfe6ff)
                    } else if d > 0.6 {
                        rgb(0x3378c0)
                    } else {
                        rgb(0x3f8fd8)
                    };
                    self.set(ax, ay, c);
                }
            }
        }
    }

    fn pine(&mut self, ax: i32, ay: i32, snowy: bool, size: i32) {
        for dx in -size..=size {
            self.shade(ax + dx + 1, ay + 2, 0.72);
        }
        self.set(ax, ay + 1, rgb(0x5e3820));
        self.set(ax, ay, rgb(0x7a4a2a));
        // three tiers, wider at the bottom
        for tier in 0..3 {
            let top = ay - 2 - tier * (size - 1);
            let w = size - tier;
            for dy in 0..size {
                let span = (w * dy / size).max(0);
                for dx in -span..=span {
                    let edge_px = dx.abs() == span || dy == size - 1;
                    let mut c = if edge_px {
                        rgb(0x1e3b24)
                    } else if dx < 0 {
                        rgb(0x3d7a3f)
                    } else {
                        rgb(0x2c5e31)
                    };
                    if snowy && dy < 2 && !edge_px {
                        c = WHITE;
                    }
                    self.set(ax + dx, top - size + dy + 1, c);
                }
            }
        }
    }

    fn boulder(&mut self, ax: i32, ay: i32, r: i32) {
        self.disc(ax + 1, ay + 1, r, rgb(0x4f4840));
        self.disc(ax, ay, r, rgb(0x8f8678));
        self.disc(ax - r / 3, ay - r / 3, (r / 2).max(1), rgb(0xb3aa9b));
        self.circle_outline(ax, ay, r, OUTLINE);
    }

    /// a rock face: stepped grey ledges with dark cracks
    fn cliff(&mut self, ax: i32, ay: i32, size: i32) {
        for dy in 0..size {
            for dx in -size..=size {
                let d = (dx as f64 / size as f64).powi(2) + ((dy as f64 - size as f64 / 2.0) / (size as f64 / 2.0)).powi(2);
                if d < 1.0 && self.free_at(ax + dx, ay + dy) {
                    let ledge = (dy + dx.abs() / 3) % 6;
                    let mut c = if ledge == 0 {
                        rgb(0x5a5248)
                    } else if ledge < 3 {
                        rgb(0xa49a8a)
                    } else {
                        rgb(0x887e70)
                    };
                    if (dx * 7 + dy * 13) % 23 == 0 {
                        c = rgb(0x3e3832);
                    }
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
    }

    fn observatory(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S, (self.h - y) / S);
        let (ox, oy) = (ax + 30, ay - 8);
        // the building, then the silver dome with its open slit
        for dy in 0..8 {
            for dx in -12i32..=12 {
                let c = if dx.abs() == 12 || dy == 7 {
                    OUTLINE
                } else if dy < 2 {
                    rgb(0xc9c3b6)
                } else {
                    WHITE
                };
                self.set(ox + dx, oy + dy, c);
            }
        }
        for dy in 0..12 {
            let w = round(12.0 * (1.0 - (dy as f64 / 12.0).powi(2)).sqrt());
            for dx in -w..=w {
                let mut c = if dx.abs() == w {
                    OUTLINE
                } else if dx < -3 {
                    rgb(0xf2f4f7)
                } else {
                    rgb(0xc8ccd4)
                };
                if dx.abs() <= 1 && dy > 2 {
                    c = rgb(0x26344e);
                }
                self.set(ox + dx, oy - dy, c);
            }
        }
        self.set(ox, oy - 13, RED);
    }

    // ================================================================== RUSH HOUR GETAWAY

    fn rush_hour(&mut self) {
        let (w, h) = (self.w, self.h);
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                self.set(ax, ay, if ax % 8 == 0 || ay % 8 == 0 { rgb(0xaaa395) } else { rgb(0xbdb6a7) });
            }
        }
        // the harbor along the top and the right
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                let (x, y) = (self.wx(ax), self.wy(ay));
                let (xd, yd) = (x as f64, y as f64);
                let water = yd > (h - 110) as f64 - 6.0 * (xd / 37.0).sin() || (xd > (w - 110) as f64 + 4.0 * (yd / 29.0).sin() && y > 400.0);
                if water && self.edge(x, y) > 0.0 {
                    let n = (ax * 5 + (ay / 2) * 11) % 29;
                    self.set(ax, ay, if n == 0 { rgb(0xbfe6ff) } else { rgb(0x3f8fd8) });
                    self.blocked[(ay * self.aw + ax) as usize] = true;
                }
            }
        }
        self.garage(1640, 1560, 2760, 2340);
        self.block(1640, 1560, 2760, 2340);
        self.bank(200, 200);
        self.block(200, 160, 500, 330);
        self.containers(w - 200, 1000, w - 150, 3300);
        self.block(w - 220, 900, w, 3400);
        self.city(&[[0, 0, w, h]]);
        self.alley_details(1200, 1100, 1700);
        self.paint_road(rgb(0x4d4f57), rgb(0x45474e), rgb(0xf6d24a), true, false);
        self.warehouse(3450, 2600);
        self.start_line();
        self.finish_line();
    }

    /// blocks of towers with flat roofs, rooftop units and lit windows along the front
    fn city(&mut self, blocks: &[[i32; 4]]) {
        let roofs = [rgb(0x9aa5b1), rgb(0x8796a6), rgb(0xb3b8bd), rgb(0x7f8a99), rgb(0xa9b4c4), rgb(0xa58f7c)];
        for b in blocks {
            let mut y = b[1];
            while y < b[3] - 60 {
                let mut x = b[0];
                while x < b[2] - 60 {
                    let w = 90 + 30 * self.rnd.next_int(4);
                    let h = 66 + 15 * self.rnd.next_int(3);
                    if self.fits_box(x, y, w.min(b[2] - x), h) {
                        let roof = roofs[self.rnd.next_int(roofs.len() as i32) as usize];
                        self.tower(x / S, (self.h - y - h) / S, w.min(b[2] - x) / S, h / S, roof);
                    }
                    x += w + 12;
                }
                y += 96;
            }
        }
    }

    fn fits_box(&self, x: i32, y: i32, w: i32, h: i32) -> bool {
        let mut yy = y;
        while yy <= y + h {
            let mut xx = x;
            while xx <= x + w {
                if !self.clear(xx as f32, yy as f32, 2.0) || !self.free_at(xx / S, (self.h - yy) / S) {
                    return false;
                }
                xx += 6;
            }
            yy += 6;
        }
        true
    }

    /// a building seen from above, a strip of its front showing at the bottom
    fn tower(&mut self, ax: i32, ay: i32, w: i32, h: i32, roof: u32) {
        if w < 5 || h < 5 {
            return;
        }
        for y in 1..=h + 1 {
            self.shade(ax + w + 1, ay + y, 0.65);
        }
        for x in 1..=w + 1 {
            self.shade(ax + x, ay + h + 1, 0.65);
        }
        let front = (h / 4).max(3);
        for y in 0..h {
            for x in 0..w {
                let c = if y >= h - front {
                    // the front wall: rows of windows, some lit
                    if x % 3 == 0 || (y - (h - front)) % 2 == 0 {
                        blend(roof, OUTLINE, 0.45)
                    } else if self.rnd.next_int(6) == 0 {
                        LIT
                    } else {
                        GLASS_DARK
                    }
                } else if y == 0 || x == 0 {
                    blend(roof, WHITE, 0.25)
                } else if (x + y) % 9 == 0 {
                    blend(roof, OUTLINE, 0.12)
                } else {
                    roof
                };
                self.set(ax + x, ay + y, c);
            }
        }
        // rooftop units
        for _ in 0..2 + w / 12 {
            let ux = ax + 2 + self.rnd.next_int((w - 6).max(1));
            let uy = ay + 2 + self.rnd.next_int((h - front - 5).max(1));
            for dy in 0..2 {
                for dx in 0..3 {
                    self.set(ux + dx, uy + dy, if dy == 0 { rgb(0xd5d8dc) } else { rgb(0x8d9298) });
                }
            }
        }
        self.outline(ax, ay, w, h);
    }

    /// the multi-storey car park: concrete deck, painted bays, pillars and parked cars
    fn garage(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        let h = self.h;
        for ay in (h - y1) / S..=(h - y0) / S {
            for ax in x0 / S..=x1 / S {
                let (x, y) = (self.wx(ax), self.wy(ay));
                let edge_px = ax == x0 / S || ax == x1 / S || ay == (h - y1) / S || ay == (h - y0) / S;
                let mut c = if edge_px {
                    OUTLINE
                } else if (ax + ay) % 13 == 0 {
                    rgb(0x9a9a9a)
                } else {
                    rgb(0xa8a8a6)
                };
                if !edge_px && self.edge(x, y) > 0.0 {
                    // parking bays with white lines, a pillar every few bays
                    let (bx, by) = ((ax - x0 / S) % 10, (ay - (h - y1) / S) % 22);
                    if bx == 0 && by < 14 {
                        c = WHITE;
                    }
                    if bx == 0 && by == 0 {
                        c = rgb(0x6f6f6f);
                    }
                    if (ax - x0 / S) % 30 == 15 && (ay - (h - y1) / S) % 22 == 18 {
                        c = rgb(0x5a5a5a);
                    }
                }
                self.set(ax, ay, c);
            }
        }
        // parked cars in some bays
        let paint = [RED, rgb(0x3b6fd9), WHITE, rgb(0x2a2a30), YELLOW, rgb(0x3fae49)];
        for _ in 0..70 {
            let ax = x0 / S + 3 + self.rnd.next_int((x1 - x0) / S - 6);
            let ay = (h - y1) / S + 2 + self.rnd.next_int((y1 - y0) / S - 6);
            if self.edge(self.wx(ax), self.wy(ay)) > 10.0 && self.edge(self.wx(ax), self.wy(ay + 4)) > 10.0 {
                let c = paint[self.rnd.next_int(paint.len() as i32) as usize];
                self.mini_car(ax, ay, c);
            }
        }
    }

    fn mini_car(&mut self, ax: i32, ay: i32, color: u32) {
        for dy in 0..5 {
            for dx in 0..3 {
                self.set(ax + dx, ay + dy, if dy == 1 { GLASS_DARK } else { color });
            }
        }
        self.outline(ax, ay, 3, 5);
    }

    /// dumpsters down the alley
    fn alley_details(&mut self, x: i32, y0: i32, y1: i32) {
        let mut y = y0 + 80;
        while y < y1 - 60 {
            for side in [-1, 1] {
                let px = (x + side * (42 + 26)) as f32;
                if self.edge(px, y as f32) > 0.0 {
                    let (ax, ay) = ((px / S as f32) as i32, (self.h - y) / S);
                    for dy in 0..4 {
                        for dx in 0..6 {
                            self.set(ax + dx - 3, ay + dy, if dy == 0 { rgb(0x2f6f42) } else { rgb(0x3f8a54) }); // a dumpster
                        }
                    }
                    self.outline(ax - 3, ay, 6, 4);
                }
            }
            y += 70;
        }
    }

    /// the bank at the start: steps, columns and a gold roof line
    fn bank(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S + 22, (self.h - y) / S - 34);
        for dy in 0..30 {
            for dx in 0..60 {
                let c = if dy < 6 {
                    if dy == 0 { rgb(0xd8b84a) } else { rgb(0xe8e2d0) } // the pediment
                } else if dy > 25 {
                    if dy % 2 == 0 { rgb(0xc9c3b6) } else { rgb(0xddd7c9) } // the steps
                } else if dx % 8 < 3 {
                    WHITE // columns
                } else {
                    rgb(0x8f8a80)
                };
                if self.free_at(ax + dx, ay + dy) {
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
        self.outline(ax, ay, 60, 30);
        // the getaway bags of cash by the curb
        self.set(ax - 3, ay + 27, rgb(0x6fae4a));
        self.set(ax - 3, ay + 28, rgb(0x4f8a34));
    }

    /// stacks of shipping containers on the quay
    fn containers(&mut self, x0: i32, y0: i32, _x1: i32, y1: i32) {
        let colors = [RED, rgb(0x3b6fd9), rgb(0xe08a2e), rgb(0x2f8f6a), rgb(0x8a4ae0)];
        let mut y = y0;
        while y < y1 {
            let (ax, ay) = (x0 / S, (self.h - y) / S);
            let c = colors[self.rnd.next_int(colors.len() as i32) as usize];
            let mut ok = true;
            let mut dy = 0;
            while dy < 10 && ok {
                ok = self.free_at(ax, ay + dy) && self.edge(self.wx(ax), self.wy(ay + dy)) > WALK;
                dy += 1;
            }
            if ok {
                for dy in 0..10 {
                    for dx in 0..14 {
                        self.set(ax + dx, ay + dy, if dx % 2 == 0 { blend(c, OUTLINE, 0.2) } else { c });
                    }
                }
                self.outline(ax, ay, 14, 10);
            }
            y += 36;
        }
    }

    /// the warehouse at the end: a big corrugated roof
    fn warehouse(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S - 40, (self.h - y) / S - 10);
        for dy in 0..40 {
            for dx in 0..80 {
                if self.edge(self.wx(ax + dx), self.wy(ay + dy)) <= WALK + 2.0 {
                    continue;
                }
                let mut c = if dx % 3 == 0 { rgb(0x7f8a8f) } else { rgb(0x9aa5aa) };
                if dy == 0 {
                    c = rgb(0xc9d0d4);
                }
                self.set(ax + dx, ay + dy, c);
            }
        }
    }

    // ================================================================== ROOFTOP RUN

    fn rooftop(&mut self) {
        for b in ROOF_BRIDGES {
            self.half[b] = 46.0; // the sky bridges are narrow
        }
        // far below: streets with tiny traffic
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                let mut c = if (ax / 4 + ay / 4) % 2 == 0 { rgb(0x2c2e35) } else { rgb(0x30323a) };
                if ax % 60 < 2 || ay % 50 < 2 {
                    c = rgb(0x5a5a5e);
                }
                self.set(ax, ay, c);
            }
        }
        for _ in 0..700 {
            let ax = self.rnd.next_int(self.aw);
            let ay = self.rnd.next_int(self.ah);
            let c = if self.rnd.next_bool() { LIT } else { RED };
            self.set(ax, ay, c);
        }
        // other buildings all around, clear of the course's own rooftops
        let (w, h) = (self.w, self.h);
        let mut x = 40;
        while x < w - 300 {
            let mut y = 30;
            while y < h - 260 {
                let x1 = x + 520 + self.rnd.next_int(60);
                let y1 = y + 360 + self.rnd.next_int(60);
                if !overlaps_course_roof(x - 60, y - 60, x1 + 60, y1 + 60) {
                    let color = rgb([0x7f8a99, 0x8a8478, 0x6f7a88][self.rnd.next_int(3) as usize]);
                    self.roof(x, y, x1.min(w - 20), y1.min(h - 20), color);
                }
                y += 470;
            }
            x += 640;
        }
        for r in ROOF_TOPS {
            self.roof(r[0], r[1], r[2], r[3], rgb(r[4] as u32));
        }
        self.paint_road(rgb(0x5a5c62), rgb(0x52545a), WHITE, false, false);
        self.bridges();
        self.helipad(7000, 1260);
        self.helipad(500, 330);
        self.helipad(3500, 1350);
        self.billboards();
        self.start_line();
        self.finish_line();
    }

    /// a rooftop: gravel with a parapet, water tanks, vents and skylights
    fn roof(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, color: u32) {
        let (ax0, ax1, ay0, ay1) = (x0 / S, x1 / S, (self.h - y1) / S, (self.h - y0) / S);
        for ay in ay0..=ay1 + 4 {
            for ax in ax0..=ax1 + 3 {
                if ay > ay1 || ax > ax1 {
                    self.shade(ax, ay, 0.55); // the building's shadow on the street below
                }
            }
        }
        for ay in ay0..=ay1 {
            for ax in ax0..=ax1 {
                let parapet = ax - ax0 < 2 || ax1 - ax < 2 || ay - ay0 < 2 || ay1 - ay < 2;
                let c = if parapet {
                    if ax - ax0 < 1 || ay - ay0 < 1 {
                        blend(color, WHITE, 0.3)
                    } else {
                        blend(color, OUTLINE, 0.35)
                    }
                } else if (ax * 7 + ay * 13) % 17 == 0 {
                    blend(color, OUTLINE, 0.15)
                } else {
                    color
                };
                self.set(ax, ay, c);
            }
        }
        self.outline(ax0, ay0, ax1 - ax0 + 1, ay1 - ay0 + 1);
        for _ in 0..(ax1 - ax0) * (ay1 - ay0) / 900 {
            let ux = ax0 + 4 + self.rnd.next_int((ax1 - ax0 - 10).max(1));
            let uy = ay0 + 4 + self.rnd.next_int((ay1 - ay0 - 10).max(1));
            if self.edge(self.wx(ux), self.wy(uy)) < WALK + 10.0 || self.edge(self.wx(ux + 6), self.wy(uy + 6)) < WALK + 10.0 {
                continue;
            }
            match self.rnd.next_int(3) {
                0 => {
                    // a water tank
                    self.disc(ux + 3, uy + 3, 3, rgb(0x8a5a36));
                    self.circle_outline(ux + 3, uy + 3, 3, OUTLINE);
                    self.set(ux + 2, uy + 2, rgb(0xa8784e));
                }
                1 => {
                    // a skylight
                    for dy in 0..4 {
                        for dx in 0..6 {
                            self.set(ux + dx, uy + dy, if dy == 0 { rgb(0xc8ecff) } else { GLASS });
                        }
                    }
                    self.outline(ux, uy, 6, 4);
                }
                _ => {
                    // air-con units
                    for dy in 0..3 {
                        for dx in 0..4 {
                            self.set(ux + dx, uy + dy, if dy == 0 { rgb(0xe3e5e9) } else { rgb(0xa8acb2) });
                        }
                    }
                    self.set(ux + 1, uy + 1, OUTLINE);
                    self.outline(ux, uy, 4, 3);
                }
            }
        }
    }

    /// the sky bridges between buildings: steel railings, glass sides, arrows
    fn bridges(&mut self) {
        let h = self.h as f32;
        for s in ROOF_BRIDGES {
            let ([ax, ay], [bx, by]) = (self.pts[s], self.pts[s + 1]);
            let len = ((bx - ax) as f64).hypot((by - ay) as f64) as f32;
            let (dx, dy) = ((bx - ax) / len, (by - ay) / len);
            let mut t = 0.0f32;
            while t < len {
                for side in [-1.0f32, 1.0] {
                    let mut w = 0.0f32;
                    while w < 7.0 {
                        let x = ax + dx * t - dy * side * (self.half[s] + 2.0 + w);
                        let y = ay + dy * t + dx * side * (self.half[s] + 2.0 + w);
                        self.set((x / S as f32) as i32, ((h - y) / S as f32) as i32, if w < 3.0 { rgb(0xc8ccd4) } else { rgb(0x7fb2e0) });
                        w += 1.5;
                    }
                }
                t += 1.5;
            }
            // chevrons pointing the way
            let mut t = 40.0f32;
            while t < len - 30.0 {
                let (cx, cy) = (ax + dx * t, ay + dy * t);
                for k in -10i32..=10 {
                    let kf = k as f32;
                    let px = cx - dy * kf - dx * k.abs() as f32 * 0.8;
                    let py = cy + dx * kf - dy * k.abs() as f32 * 0.8;
                    self.set((px / S as f32) as i32, ((h - py) / S as f32) as i32, YELLOW);
                }
                t += 70.0;
            }
        }
    }

    fn helipad(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S, (self.h - y) / S);
        for dy in -12i32..=12 {
            for dx in -12i32..=12 {
                let d = (dx as f64).hypot(dy as f64);
                if d <= 12.0 {
                    let mut c = if d > 11.0 {
                        YELLOW
                    } else if d > 9.5 {
                        rgb(0x3a3c42)
                    } else {
                        rgb(0x4a4c52)
                    };
                    if self.edge(self.wx(ax + dx), self.wy(ay + dy)) < 0.0 {
                        c = if d > 11.0 { YELLOW } else { blend(c, rgb(0x5a5c62), 0.5) };
                    }
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
        // the H
        for k in -5..=5 {
            self.set(ax - 4, ay + k, WHITE);
            self.set(ax + 4, ay + k, WHITE);
        }
        for k in -4..=4 {
            self.set(ax + k, ay, WHITE);
        }
    }

    /// neon billboards standing on the roof edges
    fn billboards(&mut self) {
        let neon = [rgb(0xff4fa3), rgb(0x3fe0ff), rgb(0xffe23f), rgb(0x8cff5a), rgb(0xff7a3f)];
        // along the course's rooftops, near their edges
        let mut spots = Vec::new();
        for r in ROOF_TOPS {
            spots.push([r[0] + 60, r[3] - 40]);
            spots.push([r[2] - 140, r[1] + 70]);
        }
        for p in spots {
            let (ax, ay) = (p[0] / S, (self.h - p[1]) / S);
            if self.edge(p[0] as f32, p[1] as f32) < WALK + 10.0 {
                continue;
            }
            for dy in 0..7 {
                for dx in 0..22 {
                    let c = if dy == 0 || dy == 6 || dx == 0 || dx == 21 {
                        OUTLINE
                    } else {
                        neon[((dx / 4 + dy) % neon.len() as i32) as usize]
                    };
                    self.set(ax + dx, ay + dy, c);
                }
            }
            self.set(ax + 4, ay + 7, OUTLINE);
            self.set(ax + 17, ay + 7, OUTLINE);
        }
    }

    // ================================================================== MOON BASE

    fn moon(&mut self) {
        let (w, h) = (self.w, self.h);
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                let n = noise(ax, ay);
                let mut c = if n > 0.65 {
                    rgb(0xa7a7aa)
                } else if n > 0.35 {
                    rgb(0x97979b)
                } else {
                    rgb(0x8a8a8f)
                };
                if (ax * 7 + ay * 3) % 31 == 0 {
                    c = rgb(0x7a7a80);
                }
                self.set(ax, ay, c);
            }
        }
        // craters of every size
        for _ in 0..260 {
            let x = self.rnd.next_int(w) as f32;
            let y = self.rnd.next_int(h) as f32;
            let r = 4 + ((self.rnd.next_float() as f64).powf(3.0) * 46.0) as i32;
            if self.clear(x, y, (r * S + 4) as f32) {
                self.crater((x / S as f32) as i32, ((h as f32 - y) / S as f32) as i32, r);
            }
        }
        // the base: domes in clusters, solar farms beside them
        let (mut i, mut placed) = (0, 0);
        while i < 400 && placed < 16 {
            let x = (300 + self.rnd.next_int(w - 600)) as f32;
            let y = (300 + self.rnd.next_int(h - 600)) as f32;
            let r = 9 + self.rnd.next_int(10);
            if self.clear(x, y, (r * S + 10) as f32) {
                self.dome((x / S as f32) as i32, ((h as f32 - y) / S as f32) as i32, r);
                for _ in 0..5 {
                    let sx = x + 90.0 + self.rnd.next_int(120) as f32;
                    let sy = y - 60.0 + self.rnd.next_int(120) as f32;
                    if self.clear(sx, sy, 20.0) {
                        self.solar((sx / S as f32) as i32, ((h as f32 - sy) / S as f32) as i32);
                    }
                }
                placed += 1;
            }
            i += 1;
        }
        self.flag(500, 1350);
        for _ in 0..14 {
            let x = (500 + self.rnd.next_int(w - 1000)) as f32;
            let y = (200 + self.rnd.next_int(h - 400)) as f32;
            if self.clear(x, y, 20.0) {
                self.parked_rover((x / S as f32) as i32, ((h as f32 - y) / S as f32) as i32);
            }
        }
        self.earth(5120, 3000);
        self.paint_road(rgb(0xb9b9bd), rgb(0xb0b0b4), rgb(0x8fdcff), false, false);
        self.tire_tracks();
        self.launch_pad(4810, 2840);
        self.start_line();
        self.finish_line();
    }

    fn crater(&mut self, ax: i32, ay: i32, r: i32) {
        for dy in -r - 1..=r + 1 {
            for dx in -r - 1..=r + 1 {
                let d = (dx as f64).hypot(dy as f64) / r as f64;
                if d > 1.15 {
                    continue;
                }
                let base = self.get(ax + dx, ay + dy);
                let c = if d > 0.92 {
                    // the raised rim: lit on its outer top-left, shadowed bottom-right
                    blend(base, if dx + dy < 0 { WHITE } else { OUTLINE }, 0.22)
                } else {
                    // the bowl: the inside wall facing the sun is lit, the far wall in shadow
                    let wall = ((d - 0.45) / 0.47).max(0.0);
                    let facing = (dx + dy) as f64 / ((dx as f64).hypot(dy as f64) + 1e-6) / 2f64.sqrt();
                    let floor = blend(base, OUTLINE, 0.08);
                    if facing < 0.0 {
                        blend(floor, OUTLINE, 0.35 * wall * -facing)
                    } else {
                        blend(floor, WHITE, 0.3 * wall * facing)
                    }
                };
                self.set(ax + dx, ay + dy, c);
            }
        }
    }

    fn dome(&mut self, ax: i32, ay: i32, r: i32) {
        self.disc(ax + 2, ay + 2, r, rgb(0x55555b));
        for dy in -r..=r {
            for dx in -r..=r {
                let d = (dx as f64).hypot(dy as f64);
                if d <= r as f64 {
                    let c = if d > (r - 1) as f64 {
                        OUTLINE
                    } else if dx + dy < -r / 2 {
                        rgb(0xdff3ff)
                    } else if (dx * 3 + dy * 5) % 7 == 0 {
                        rgb(0x7fb2d0)
                    } else {
                        GLASS
                    };
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
        // the frame and a glow from inside
        for k in -r + 1..r {
            self.set(ax + k, ay, rgb(0xc8ccd4));
            self.set(ax, ay + k, rgb(0xc8ccd4));
        }
        self.set(ax + 2, ay + 2, LIT);
    }

    fn solar(&mut self, ax: i32, ay: i32) {
        for dy in 0..4 {
            for dx in 0..9 {
                self.set(ax + dx, ay + dy, if dx % 3 == 2 { rgb(0x8a8e98) } else { rgb(0x2f4fa8) });
            }
        }
        self.outline(ax, ay, 9, 4);
    }

    fn flag(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S, (self.h - y) / S);
        for k in 0..12 {
            self.set(ax, ay - k, rgb(0xd8d8d8));
        }
        let stripes = [RED, WHITE];
        for dy in 0..6 {
            for dx in 1..10 {
                let c = if dx < 4 && dy < 3 { rgb(0x2f44d8) } else { stripes[(dy % 2) as usize] };
                self.set(ax + dx, ay - 11 + dy, c);
            }
        }
    }

    fn parked_rover(&mut self, ax: i32, ay: i32) {
        for dy in 0..6 {
            for dx in 0..8 {
                let c = if (dx == 0 || dx == 7) && (dy == 0 || dy == 5) {
                    BLACK
                } else if dy == 2 {
                    GLASS_DARK
                } else {
                    WHITE
                };
                self.set(ax + dx, ay + dy, c);
            }
        }
        self.outline(ax, ay, 8, 6);
    }

    /// the Earth peeking over the horizon in the corner
    fn earth(&mut self, x: i32, y: i32) {
        let (ax, ay, r) = (x / S, (self.h - y) / S, 26);
        for dy in -r..=r {
            for dx in -r..=r {
                let d = (dx as f64).hypot(dy as f64);
                if d <= r as f64 && self.free_at(ax + dx, ay + dy) {
                    let n = (dx as f64 / 4.0).sin() * (dy as f64 / 5.0).cos() + ((dx + dy) as f64 / 7.0).sin();
                    let mut c = if d > (r - 1) as f64 {
                        rgb(0x9fd8ff)
                    } else if n > 0.6 {
                        rgb(0x4f9a4a)
                    } else if n < -1.2 {
                        WHITE
                    } else {
                        rgb(0x2f6fc8)
                    };
                    if (dx - dy) as f64 > r as f64 * 0.6 {
                        c = blend(c, BLACK, 0.55); // the night side
                    }
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
    }

    /// tyre tracks along the lunar road
    fn tire_tracks(&mut self) {
        let h = self.h as f32;
        let mut i = 2;
        while i + 1 < self.pts.len() - 1 {
            let ([ax, ay], [bx, by]) = (self.pts[i], self.pts[i + 1]);
            let len = ((bx - ax) as f64).hypot((by - ay) as f64) as f32;
            let (dx, dy) = ((bx - ax) / len, (by - ay) / len);
            let mut t = 0.0f32;
            while t < len {
                for k in [-24.0f32, -16.0, 16.0, 24.0] {
                    let (x, y) = (ax + dx * t - dy * k, ay + dy * t + dx * k);
                    if (t as i32 / 6) % 2 == 0 {
                        let (px, py) = ((x / S as f32) as i32, ((h - y) / S as f32) as i32);
                        let c = blend(self.get(px, py), OUTLINE, 0.18);
                        self.set(px, py, c);
                    }
                }
                t += 3.0;
            }
            i += 1;
        }
    }

    /// the rocket waiting on its launch pad past the finish
    fn launch_pad(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S + 30, (self.h - y) / S);
        for dy in -20i32..=20 {
            for dx in -20i32..=20 {
                let d = (dx as f64).hypot(dy as f64);
                if d <= 20.0 && self.edge(self.wx(ax + dx), self.wy(ay + dy)) > WALK {
                    let c = if d > 19.0 {
                        YELLOW
                    } else if d as i32 % 6 == 0 {
                        rgb(0x5a5a5e)
                    } else {
                        rgb(0x6f6f74)
                    };
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
        // the rocket seen from above: a white body, red nose, four fins and a shadow
        self.disc(ax + 5, ay + 5, 8, rgb(0x55555b));
        self.disc(ax, ay, 8, OUTLINE);
        self.disc(ax, ay, 7, WHITE);
        self.disc(ax - 2, ay - 2, 3, rgb(0xffffff));
        self.disc(ax, ay, 3, RED);
        for k in 8..13 {
            self.set(ax + k, ay, RED);
            self.set(ax - k, ay, RED);
            self.set(ax, ay + k, RED);
            self.set(ax, ay - k, RED);
        }
    }

    // ================================================================== the road itself

    /// asphalt (or packed dust), a curb, the drivable verge, a fence along its outer edge, and
    /// a dashed centre line on every stretch. `mountain`: white guard-rail posts, not bollards.
    fn paint_road(&mut self, asphalt: u32, speck: u32, lane: u32, double_line: bool, mountain: bool) {
        let s = S as f32;
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                let (x, y) = (self.wx(ax), self.wy(ay));
                let e = self.edge(x, y);
                if e <= 0.0 {
                    let mut c = if e > -s {
                        rgb(0x7c786f)
                    } else if (ax * 5 + ay * 3) % 17 == 0 {
                        speck
                    } else {
                        asphalt
                    };
                    if e > -3.0 * s && e <= -2.0 * s {
                        c = rgb(0xd8d4ca); // the edge line
                    }
                    self.set(ax, ay, c);
                } else if e <= WALK {
                    let base = self.get(ax, ay);
                    let verge = if ax % 4 == 0 || ay % 4 == 0 { rgb(0xb8b0a2) } else { rgb(0xcbc3b5) };
                    self.set(ax, ay, blend(base, verge, 0.75));
                } else if e <= WALK + s {
                    let c = if mountain {
                        if (ax + ay) % 5 == 0 { RED } else { WHITE }
                    } else if (ax + ay) % 4 == 0 {
                        YELLOW
                    } else {
                        rgb(0x55514b)
                    };
                    self.set(ax, ay, c);
                }
            }
        }
        // a dashed centre line along each stretch (not the grid stub or the run-off)
        let h = self.h as f32;
        let mut i = 1;
        while i + 1 < self.pts.len() - 1 {
            let ([ax, ay], [bx, by]) = (self.pts[i], self.pts[i + 1]);
            let len = ((bx - ax) as f64).hypot((by - ay) as f64) as f32;
            let (dx, dy) = ((bx - ax) / len, (by - ay) / len);
            let mut t = 50.0f32;
            while t < len - 50.0 {
                if ((t / 20.0) as i32) % 2 == 0 {
                    let ks: &[f32] = if double_line { &[-3.0, 3.0] } else { &[0.0] };
                    for &k in ks {
                        let (x, y) = (ax + dx * t - dy * k, ay + dy * t + dx * k);
                        self.set((x / s) as i32, ((h - y) / s) as i32, lane);
                    }
                }
                t += 1.5;
            }
            i += 1;
        }
    }

    fn start_line(&mut self) {
        let (p1, p2) = (self.pts[1], self.pts[2]);
        self.checkers(p1[0], p1[1], p2[0] - p1[0], p2[1] - p1[1], self.half[1]);
    }

    fn finish_line(&mut self) {
        let f = self.pts.len() - 2;
        let (pf, pe) = (self.pts[f], self.pts[f - 1]);
        self.checkers(pf[0], pf[1], pf[0] - pe[0], pf[1] - pe[1], self.half[f - 1]);
    }

    /// a checkered line across the road at (x, y), facing along (dx, dy)
    fn checkers(&mut self, x: f32, y: f32, dx: f32, dy: f32, half_width: f32) {
        let len = (dx as f64).hypot(dy as f64) as f32;
        let (dx, dy) = (dx / len, dy / len);
        let h = self.h as f32;
        let mut along = 0.0f32;
        while along < 24.0 {
            let mut across = -half_width + 3.0;
            while across < half_width - 3.0 {
                let (px, py) = (x + dx * along - dy * across, y + dy * along + dx * across);
                let cx = (((across + half_width) / 6.0) as f64).floor() as i32;
                let cy = ((along / 6.0) as f64).floor() as i32;
                self.set((px / S as f32) as i32, ((h - py) / S as f32) as i32, if (cx + cy) % 2 == 0 { WHITE } else { BLACK });
                across += 1.5;
            }
            along += 1.5;
        }
    }

    // ================================================================== saving

    /// the picture and the road mask at art resolution (the game scales them up 3x)
    fn save(&self, root: &Path, folder: &str) -> Result<(), String> {
        let dir = root.join(format!("assets/maps/{folder}"));
        let (aw, ah) = (self.aw as usize, self.ah as usize);
        let mut map = Picture::new(aw, ah);
        let mut mask = Picture::new(aw, ah);
        for ay in 0..self.ah {
            for ax in 0..self.aw {
                map.set(ax as usize, ay as usize, self.art[(ay * self.aw + ax) as usize]);
                let road = self.edge(self.wx(ax), self.wy(ay)) <= 0.0;
                mask.set(ax as usize, ay as usize, if road { 0xffffffff } else { 0xff000000 });
            }
        }
        map.save(dir.join("background.png"), true)?;
        mask.save(dir.join("road_mask.png"), false)?;
        preview(&map, 384, 216).save(dir.join("preview.png"), false)?;
        println!("{folder} {}x{}", self.w, self.h);
        Ok(())
    }
}

fn overlaps_course_roof(x0: i32, y0: i32, x1: i32, y1: i32) -> bool {
    ROOF_TOPS.iter().any(|r| x0 < r[2] && x1 > r[0] && y0 < r[3] && y1 > r[1])
}

fn proj(x: f32, y: f32, ax: f32, ay: f32, bx: f32, by: f32) -> f32 {
    let (dx, dy) = (bx - ax, by - ay);
    let l2 = dx * dx + dy * dy;
    if l2 < 1e-6 {
        0.0
    } else {
        0f32.max(1f32.min(((x - ax) * dx + (y - ay) * dy) / l2))
    }
}

fn seg_dist(x: f32, y: f32, ax: f32, ay: f32, bx: f32, by: f32) -> f32 {
    let t = proj(x, y, ax, ay, bx, by);
    ((x - (ax + (bx - ax) * t)) as f64).hypot((y - (ay + (by - ay) * t)) as f64) as f32
}

/// smooth value noise in 0..1, for patchy ground
fn noise(ax: i32, ay: i32) -> f32 {
    let (x, y) = (ax as f64, ay as f64);
    let v = (x * 0.07).sin() * (y * 0.05).cos() + ((x + y) * 0.031).sin() * 0.7 + (x * 0.19 + y * 0.13).sin() * 0.3;
    ((v + 2.0) / 4.0) as f32
}

/// the whole map shrunk to fit the picker's card, on a dark background
fn preview(src: &Picture, w: usize, h: usize) -> Picture {
    let mut out = Picture::new(w, h);
    out.pixels.fill(0xff1c1714);
    let scale = (w as f64 / src.width as f64).min(h as f64 / src.height as f64);
    let (pw, ph) = ((src.width as f64 * scale) as usize, (src.height as f64 * scale) as usize);
    let (ox, oy) = ((w - pw) / 2, (h - ph) / 2);
    for y in 0..ph {
        for x in 0..pw {
            let (sx0, sy0) = ((x as f64 / scale) as usize, (y as f64 / scale) as usize);
            let (sx1, sy1) = (((x + 1) as f64 / scale) as usize, ((y + 1) as f64 / scale) as usize);
            let (mut r, mut g, mut b, mut n) = (0u64, 0u64, 0u64, 0u64);
            let mut sy = sy0;
            while sy < sy1.min(src.height) {
                let mut sx = sx0;
                while sx < sx1.min(src.width) {
                    let c = src.get(sx, sy);
                    r += (c >> 16 & 255) as u64;
                    g += (c >> 8 & 255) as u64;
                    b += (c & 255) as u64;
                    n += 1;
                    sx += 2;
                }
                sy += 2;
            }
            if let (Some(r), Some(g), Some(b)) = (r.checked_div(n), g.checked_div(n), b.checked_div(n)) {
                out.set(ox + x, oy + y, 0xff00_0000 | (r as u32) << 16 | (g as u32) << 8 | b as u32);
            }
        }
    }
    out
}

/// the moon rover, seen from above
fn rover() -> Picture {
    let rows = [
        "..............", "...kkkkkkkk...", "..kyGGGGGGyk..", "..kGGGGGGGGk..", "wwkGbbbbbbGkww", "wwkGbbbbbbGkww",
        "wwkGbbbbbbGkww", "..kGGGGGGGGk..", "..kGssssssGk..", "..kGsGGGGsGk..", "..kGsGddGsGk..", "..kGsGddGsGk..",
        "..kGsGGGGsGk..", "wwkGssssssGkww", "wwkGGGGGGGGkww", "wwkGGGGGGGGkww", "..kGppppppGk..", "..kGppppppGk..",
        "..kGppppppGk..", "..kGGGGGGGGk..", "wwkGGGGGGGGkww", "wwkGGGGGGGGkww", "wwkGGGGGGGGkww", "..kGGGGGGGGk..",
        "..krGGGGGGrk..", "...kkkkkkkk...", "..............", "..............",
    ];
    // k outline, G white body, b visor, s silver, d antenna dish, p gold panel, w wheels, y light, r tail
    sprite(
        rows,
        "kGbsdpwyr",
        &[
            rgb(0x1c1c22), rgb(0xeef0f3), rgb(0x2a3a5a), rgb(0xa8acb4), rgb(0x5a5e66), rgb(0xd8b84a), rgb(0x2a2a2e), rgb(0xbfe6ff),
            rgb(0xd23a3a),
        ],
    )
}
