//! Paints the battle arenas (demolition derby): Junkyard, Stadium and Ice Rink. Each is an open
//! floor inside a wall, with a few obstacles to ram people into.
//!
//! Maps are 1920 x 1440 world pixels, saved at 1/3 size (the game scales them up 3x). The road
//! mask is white wherever you can drive.

use std::path::Path;

use crate::image::{blend, rgb, Picture};
use crate::jrand::{round, JRandom};

const W: i32 = 1920;
const H: i32 = 1440;
const S: i32 = 3;
const AW: i32 = W / S;
const AH: i32 = H / S;
/// the floor: inside this rectangle (world coords), corners rounded
const X0: i32 = 140;
const Y0: i32 = 140;
const X1: i32 = 1780;
const Y1: i32 = 1300;
const CORNER: i32 = 160;

const OUTLINE: u32 = rgb(0x221a17);
const WHITE: u32 = rgb(0xf6f3ea);
const BLACK: u32 = rgb(0x1b1b1f);
const RED: u32 = rgb(0xd9433b);
const YELLOW: u32 = rgb(0xf2c84b);
const LIT: u32 = rgb(0xffe58a);
const PAINT: [u32; 8] = [
    rgb(0xd9433b), rgb(0x3b6fd9), rgb(0xf2c84b), rgb(0x3fae49), rgb(0xe08a2e), rgb(0x8a4ae0), rgb(0xf3f5f8), rgb(0x2a2a30),
];

struct Arena {
    art: Vec<u32>,
    floor: Vec<bool>,
    rnd: JRandom,
}

pub fn paint_all(root: &Path) -> Result<(), String> {
    let mut a = Arena::new(1);
    a.junkyard();
    a.save(root, "junkyard")?;
    let mut a = Arena::new(2);
    a.stadium();
    a.save(root, "stadium")?;
    let mut a = Arena::new(3);
    a.rink();
    a.save(root, "rink")?;
    println!("arenas painted");
    Ok(())
}

/// world point of an art pixel
fn wx(ax: i32) -> f32 {
    (ax * S) as f32 + 1.5
}

fn wy(ay: i32) -> f32 {
    (H - ay * S) as f32 - 1.5
}

fn nearest_floor(x: f32, y: f32) -> (f32, f32) {
    let cx = ((X0 + CORNER) as f32).max(((X1 - CORNER) as f32).min(x));
    let cy = ((Y0 + CORNER) as f32).max(((Y1 - CORNER) as f32).min(y));
    (cx, cy)
}

/// inside the rounded rectangle of the arena floor
fn inside_floor(x: f32, y: f32) -> bool {
    let (cx, cy) = nearest_floor(x, y);
    x >= X0 as f32
        && x <= X1 as f32
        && y >= Y0 as f32
        && y <= Y1 as f32
        && ((x - cx) as f64).hypot((y - cy) as f64) <= CORNER as f64
}

/// distance outside the floor's edge (negative inside)
fn outside(x: f32, y: f32) -> f32 {
    let (cx, cy) = nearest_floor(x, y);
    ((x - cx) as f64).hypot((y - cy) as f64) as f32 - CORNER as f32
}

/// smooth value noise in 0..1, for patchy ground
fn noise(ax: i32, ay: i32) -> f32 {
    let (x, y) = (ax as f64, ay as f64);
    let v = (x * 0.07).sin() * (y * 0.05).cos() + ((x + y) * 0.031).sin() * 0.7 + (x * 0.19 + y * 0.13).sin() * 0.3;
    ((v + 2.0) / 4.0) as f32
}

/// a crowd seen from above: rows of seats, each with a head and shoulders (or empty)
fn crowd(ax: i32, ay: i32, shirts: &[u32]) -> u32 {
    let (cx, cy, px, py) = (ax / 3, ay / 4, ax % 3, ay % 4);
    let mut hash = cx.wrapping_mul(73856093) ^ cy.wrapping_mul(19349663);
    hash ^= ((hash as u32) >> 13) as i32;
    let empty = hash & 7 == 0;
    if py == 3 {
        return rgb(0x2a2c33); // the step behind the row
    }
    if empty || px == 2 {
        return if py == 2 { rgb(0x4a4d56) } else { rgb(0x3a3d46) }; // an empty seat or the gap
    }
    if py == 0 {
        let hair = [rgb(0x2a1d17), rgb(0x5a3a22), rgb(0xd8b04a), rgb(0x1b1b1f)];
        return if px == 0 { hair[((hash as u32 >> 4) & 3) as usize] } else { rgb(0xf0c8a0) }; // a head
    }
    shirts[(((hash as u32 >> 6) & 0x7fff) as usize) % shirts.len()] // shoulders
}

impl Arena {
    fn new(seed: i64) -> Arena {
        let mut floor = vec![false; (AW * AH) as usize];
        for ay in 0..AH {
            for ax in 0..AW {
                floor[(ay * AW + ax) as usize] = inside_floor(wx(ax), wy(ay));
            }
        }
        Arena { art: vec![0; (AW * AH) as usize], floor, rnd: JRandom::new(seed) }
    }

    fn set(&mut self, ax: i32, ay: i32, c: u32) {
        if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
            self.art[(ay * AW + ax) as usize] = c;
        }
    }

    fn get(&self, ax: i32, ay: i32) -> u32 {
        if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
            self.art[(ay * AW + ax) as usize]
        } else {
            0xff00_0000
        }
    }

    fn shade(&mut self, ax: i32, ay: i32, f: f64) {
        let c = blend(self.get(ax, ay), rgb(0), 1.0 - f);
        self.set(ax, ay, c);
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

    fn circle(&mut self, cx: i32, cy: i32, r: i32, c: u32) {
        let mut a = 0;
        while a < 360 {
            let rad = (a as f64).to_radians();
            self.set(cx + round(r as f64 * rad.cos()), cy + round(r as f64 * rad.sin()), c);
            a += 3;
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

    /// an obstacle: takes a disc out of the floor (you can't drive through it)
    fn obstacle(&mut self, x: f32, y: f32, r: f32) {
        for ay in 0..AH {
            for ax in 0..AW {
                if ((wx(ax) - x) as f64).hypot((wy(ay) - y) as f64) <= r as f64 {
                    self.floor[(ay * AW + ax) as usize] = false;
                }
            }
        }
    }

    // ================================================================== JUNKYARD

    fn junkyard(&mut self) {
        for ay in 0..AH {
            for ax in 0..AW {
                let n = noise(ax, ay);
                let mut c = if n > 0.62 {
                    rgb(0x8a6a48)
                } else if n > 0.35 {
                    rgb(0x7c5e40)
                } else {
                    rgb(0x6f5338)
                };
                if (ax * 7 + ay * 3) % 23 == 0 {
                    c = rgb(0x5e4630);
                }
                if (ax * 5 + ay * 11) % 41 == 0 {
                    c = rgb(0x9a7a56);
                }
                self.set(ax, ay, c);
            }
        }
        // oil stains and tyre marks on the floor
        for _ in 0..30 {
            let ax = self.rnd.next_int(AW);
            let ay = self.rnd.next_int(AH);
            let r = 3 + self.rnd.next_int(6);
            for dy in -r..=r {
                for dx in -r * 2..=r * 2 {
                    if dx * dx / 4 + dy * dy <= r * r && self.rnd.next_int(3) > 0 {
                        let c = blend(self.get(ax + dx, ay + dy), BLACK, 0.35);
                        self.set(ax + dx, ay + dy, c);
                    }
                }
            }
        }
        // scrap piles in the arena to dodge round (and ram people into)
        let piles: [[f32; 3]; 5] = [[700.0, 560.0, 70.0], [1220.0, 880.0, 80.0], [500.0, 900.0, 55.0], [1430.0, 560.0, 55.0], [960.0, 720.0, 40.0]];
        for p in piles {
            self.obstacle(p[0], p[1], p[2]);
        }
        // the wall: crushed cars stacked all the way round
        for ay in 0..AH {
            for ax in 0..AW {
                let o = outside(wx(ax), wy(ay));
                if o > 0.0 && o < 70.0 {
                    let (bx, by) = (ax / 7, ay / 4);
                    let mut c = blend(PAINT[((bx * 3 + by * 5) % PAINT.len() as i32) as usize], rgb(0x5a4a3e), 0.45);
                    if ax % 7 == 0 || ay % 4 == 0 {
                        c = OUTLINE;
                    } else if (ax + ay) % 9 == 0 {
                        c = rgb(0x8a5a36); // rust
                    }
                    self.set(ax, ay, c);
                } else if o >= 70.0 {
                    self.set(ax, ay, if (ax + ay) % 5 == 0 { rgb(0x5a4a3a) } else { rgb(0x4f4132) });
                }
            }
        }
        for p in piles {
            self.scrap_pile(p[0], p[1], p[2]);
        }
        // tyre stacks along the top and bottom walls, and a crane in the corner
        for i in 0..14 {
            let x = (X0 + 40 + self.rnd.next_int(X1 - X0 - 80)) as f32;
            let y = if i >= 7 { (Y1 - 30) as f32 } else { (Y0 + 30) as f32 };
            self.tyres((x / S as f32) as i32, ((H as f32 - y) / S as f32) as i32);
        }
        self.crane(1600.0, 1180.0);
        self.floodlight(170, 170);
        self.floodlight(1750, 170);
        self.floodlight(170, 1270);
    }

    fn scrap_pile(&mut self, x: f32, y: f32, r: f32) {
        let (cx, cy, rr) = ((x / S as f32) as i32, ((H as f32 - y) / S as f32) as i32, (r / S as f32) as i32);
        let metal = [rgb(0x8a7a6a), rgb(0x6f6458), rgb(0x9a5a3a), rgb(0x5a6470), rgb(0x7a4a32)];
        for dy in -rr - 2..=rr + 2 {
            for dx in -rr - 2..=rr + 2 {
                let d = (dx as f64).hypot(dy as f64) / rr as f64;
                if d <= 1.0 {
                    // chunks of scrap: blocks of rusty metal in a few muted colours, lit from the top left
                    let (bx, by) = ((dx + 64).div_euclid(4), (dy + 64).div_euclid(3));
                    let hash = bx.wrapping_mul(73856093) ^ by.wrapping_mul(19349663) ^ cx;
                    let mut c = metal[(((hash as u32) >> 3) as i32).rem_euclid(metal.len() as i32) as usize];
                    if (dx + 64).rem_euclid(4) == 0 || (dy + 64).rem_euclid(3) == 0 {
                        c = blend(c, OUTLINE, 0.5);
                    }
                    let t = 0.35f64.min(0.25 * (dx + dy).abs() as f64 / rr.max(1) as f64);
                    c = blend(c, if dx + dy < 0 { WHITE } else { BLACK }, t);
                    self.set(cx + dx, cy + dy, c);
                } else if d <= 1.12 {
                    self.set(cx + dx, cy + dy, OUTLINE);
                } else if d < 1.4 && dx + dy > 0 {
                    self.shade(cx + dx, cy + dy, 0.75);
                }
            }
        }
        // a crushed car on top
        for dy in -2..=2 {
            for dx in -5..=5 {
                let c = if dy == 0 { rgb(0x26344e) } else { PAINT[((cx + cy) % PAINT.len() as i32) as usize] };
                self.set(cx + dx - 2, cy + dy - 3, c);
            }
        }
    }

    fn tyres(&mut self, ax: i32, ay: i32) {
        for k in 0..3 {
            self.disc(ax + k * 2, ay - k, 3, OUTLINE);
            self.disc(ax + k * 2, ay - k, 2, rgb(0x2a2a2e));
            self.set(ax + k * 2, ay - k, rgb(0x4a4a50));
        }
    }

    fn crane(&mut self, x: f32, y: f32) {
        let (ax, ay) = ((x / S as f32) as i32, ((H as f32 - y) / S as f32) as i32);
        for dy in 0..12 {
            for dx in 0..12 {
                self.set(ax + dx, ay + dy, if dy < 2 { rgb(0xffd25a) } else { YELLOW });
            }
        }
        self.outline(ax, ay, 12, 12);
        for k in 0..50 {
            self.set(ax + 6 - k, ay + 6 - k / 3, if k % 4 == 0 { OUTLINE } else { YELLOW });
            self.set(ax + 6 - k, ay + 7 - k / 3, if k % 4 == 0 { OUTLINE } else { rgb(0xc99a12) });
        }
        self.disc(ax - 44, ay - 10, 4, rgb(0x5a5c62)); // the magnet
        self.circle(ax - 44, ay - 10, 4, OUTLINE);
    }

    // ================================================================== STADIUM

    fn stadium(&mut self) {
        let red_team = [RED, rgb(0xff8a7a), WHITE, rgb(0xb02a22)];
        let blue_team = [rgb(0x3b6fd9), rgb(0x8fb8e8), WHITE, rgb(0x24489a)];
        // the crowd in the stands, all round
        for ay in 0..AH {
            for ax in 0..AW {
                let o = outside(wx(ax), wy(ay));
                let c = if o <= 0.0 {
                    let n = noise(ax, ay);
                    let mut c = if n > 0.55 { rgb(0xa9744a) } else { rgb(0x9c6a42) }; // clay
                    if (ax * 7 + ay * 3) % 19 == 0 {
                        c = rgb(0x8a5c38);
                    }
                    c
                } else if o < 30.0 {
                    // the barrier
                    let mut c = if o < 6.0 {
                        WHITE
                    } else if (ax / 4 + ay / 4) % 2 == 0 {
                        RED
                    } else {
                        WHITE
                    };
                    if (6.0..9.0).contains(&o) {
                        c = OUTLINE;
                    }
                    c
                } else if o < 46.0 {
                    rgb(0x5a5c62) // the walkway
                } else {
                    // spectators in team colours, red one end and blue the other
                    crowd(ax, ay, if ax < AW / 2 { &red_team } else { &blue_team })
                };
                self.set(ax, ay, c);
            }
        }
        // hay bales in the middle, and a big VAR logo painted on the dirt
        let bales: [[f32; 3]; 4] = [[760.0, 720.0, 36.0], [1160.0, 720.0, 36.0], [960.0, 500.0, 30.0], [960.0, 940.0, 30.0]];
        for b in bales {
            self.obstacle(b[0], b[1], b[2]);
        }
        self.logo(960.0, 720.0);
        for b in bales {
            self.hay(b[0], b[1], b[2]);
        }
        for i in 0..4 {
            self.floodlight(if i % 2 == 0 { 90 } else { 1830 }, if i < 2 { 90 } else { 1350 });
        }
        // the scoreboard over the top stand
        let (sx, sy) = (760 / S, (H - 1400) / S);
        for dy in 0..12 {
            for dx in 0..134 {
                let c = if dy == 0 || dy == 11 || dx == 0 || dx == 133 {
                    OUTLINE
                } else if dy > 3 && dy < 8 && dx % 6 < 4 {
                    LIT
                } else {
                    BLACK
                };
                self.set(sx + dx, sy + dy, c);
            }
        }
    }

    fn hay(&mut self, x: f32, y: f32, r: f32) {
        let (cx, cy, rr) = ((x / S as f32) as i32, ((H as f32 - y) / S as f32) as i32, (r / S as f32) as i32);
        for dy in -rr..=rr {
            for dx in -rr..=rr {
                let d = (dx as f64).hypot(dy as f64);
                if d <= rr as f64 {
                    let c = if d > (rr - 1) as f64 {
                        rgb(0x8a6a1e)
                    } else if dx.abs() % 4 == 0 {
                        rgb(0xd8a83a)
                    } else {
                        rgb(0xf2c84b)
                    };
                    self.set(cx + dx, cy + dy, c);
                }
            }
        }
        self.circle(cx, cy, rr, OUTLINE);
        for k in 1..4 {
            self.shade(cx + rr + k, cy + k, 0.7);
        }
    }

    /// "VAR" in big pale letters across the middle of the arena floor
    fn logo(&mut self, x: f32, y: f32) {
        let letters = ["#...#.###.####.", "#...#.#.#.#..#.", "#...#.###.####.", ".#.#..#.#.#.#..", "..#...#.#.#..#."];
        let cx = (x / S as f32) as i32 - 15 * 3 / 2;
        let cy = ((H as f32 - y) / S as f32) as i32 - 5 * 3 / 2;
        for (r, row) in letters.iter().enumerate() {
            for (c, ch) in row.chars().enumerate() {
                if ch == '#' {
                    for k in 0..9 {
                        let (px, py) = (cx + c as i32 * 3 + k % 3, cy + r as i32 * 3 + k / 3);
                        let col = blend(self.get(px, py), WHITE, 0.35);
                        self.set(px, py, col);
                    }
                }
            }
        }
    }

    // ================================================================== ICE RINK

    fn rink(&mut self) {
        for ay in 0..AH {
            for ax in 0..AW {
                let o = outside(wx(ax), wy(ay));
                let c = if o <= 0.0 {
                    let n = noise(ax, ay);
                    let mut c = if n > 0.6 {
                        rgb(0xeaf4fb)
                    } else if n > 0.3 {
                        rgb(0xdfeef8)
                    } else {
                        rgb(0xd2e6f3)
                    };
                    if (ax * 5 + ay * 3) % 37 == 0 {
                        c = WHITE; // scratches
                    }
                    c
                } else if o < 18.0 {
                    // the boards
                    let mut c = if o < 4.0 { rgb(0xf2c84b) } else { WHITE };
                    if (4.0..7.0).contains(&o) {
                        c = rgb(0x9fb6c8);
                    }
                    c
                } else if o < 26.0 {
                    rgb(0x8fb8e8) // glass
                } else {
                    crowd(ax, ay, &PAINT)
                };
                self.set(ax, ay, c);
            }
        }
        // hockey markings: centre line, blue lines, faceoff circles
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (wx(ax), wy(ay));
                if outside(x, y) > 0.0 {
                    continue;
                }
                if (x - 960.0).abs() < 6.0 {
                    self.set(ax, ay, RED);
                }
                if (x - 640.0).abs() < 7.0 || (x - 1280.0).abs() < 7.0 {
                    self.set(ax, ay, rgb(0x3b6fd9));
                }
                let dc = ((x - 960.0) as f64).hypot((y - 720.0) as f64);
                if (dc - 150.0).abs() < 4.0 {
                    self.set(ax, ay, rgb(0x3b6fd9));
                }
                for f in [[400.0f32, 420.0], [400.0, 1020.0], [1520.0, 420.0], [1520.0, 1020.0]] {
                    if (((x - f[0]) as f64).hypot((y - f[1]) as f64) - 110.0).abs() < 4.0 {
                        self.set(ax, ay, RED);
                    }
                }
            }
        }
        self.disc(960 / S, (H - 720) / S, 4, rgb(0x3b6fd9));
        // goals at both ends (things to ram people into) and pylons
        self.obstacle(250.0, 720.0, 40.0);
        self.obstacle(1670.0, 720.0, 40.0);
        self.goal(250.0, 720.0, 1);
        self.goal(1670.0, 720.0, -1);
        for p in [[640.0f32, 720.0], [1280.0, 720.0]] {
            self.obstacle(p[0], p[1], 22.0);
            self.pylon(p[0], p[1]);
        }
        for i in 0..4 {
            self.floodlight(if i % 2 == 0 { 90 } else { 1830 }, if i < 2 { 90 } else { 1350 });
        }
    }

    fn goal(&mut self, x: f32, y: f32, facing: i32) {
        let (cx, cy) = ((x / S as f32) as i32, ((H as f32 - y) / S as f32) as i32);
        for dy in -12i32..=12 {
            for dx in -9i32..=9 {
                let frame = dy.abs() >= 11 || if facing > 0 { dx <= -8 } else { dx >= 8 };
                let net = (dx + dy) % 3 == 0 || (dx - dy) % 3 == 0;
                if frame {
                    self.set(cx + dx, cy + dy, RED);
                } else if net && if facing > 0 { dx < 4 } else { dx > -4 } {
                    self.set(cx + dx, cy + dy, WHITE);
                }
            }
        }
    }

    fn pylon(&mut self, x: f32, y: f32) {
        let (cx, cy) = ((x / S as f32) as i32, ((H as f32 - y) / S as f32) as i32);
        self.disc(cx, cy, 7, OUTLINE);
        self.disc(cx, cy, 6, rgb(0xf08a2e));
        self.disc(cx, cy, 3, WHITE);
        self.disc(cx, cy, 1, rgb(0xf08a2e));
    }

    fn floodlight(&mut self, x: i32, y: i32) {
        let (cx, cy) = (x / S, (H - y) / S);
        for dy in 0..6 {
            for dx in 0..10 {
                let c = if dy == 0 || dy == 5 || dx == 0 || dx == 9 {
                    OUTLINE
                } else if dx % 3 == 1 {
                    LIT
                } else {
                    rgb(0xd8d4ca)
                };
                self.set(cx - 5 + dx, cy - 3 + dy, c);
            }
        }
    }

    fn save(&self, root: &Path, folder: &str) -> Result<(), String> {
        let dir = root.join(format!("assets/maps/{folder}"));
        let (aw, ah) = (AW as usize, AH as usize);
        let mut map = Picture::new(aw, ah);
        let mut mask = Picture::new(aw, ah);
        for i in 0..aw * ah {
            map.pixels[i] = self.art[i];
            mask.pixels[i] = if self.floor[i] { 0xffffffff } else { 0xff000000 };
        }
        map.save(dir.join("background.png"), true)?;
        mask.save(dir.join("road_mask.png"), false)?;
        // the picker's preview: the arena centred on a dark background
        let mut preview = Picture::new(384, 216);
        let scale = (384.0 / AW as f64).min(216.0 / AH as f64);
        let (pw, ph) = ((AW as f64 * scale) as i32, (AH as f64 * scale) as i32);
        let (ox, oy) = ((384 - pw) / 2, (216 - ph) / 2);
        for y in 0..216 {
            for x in 0..384 {
                let sx = ((x - ox) as f64 / scale) as i32;
                let sy = ((y - oy) as f64 / scale) as i32;
                let c = if sx >= 0 && sy >= 0 && sx < AW && sy < AH { self.art[(sy * AW + sx) as usize] } else { 0xff1c1714 };
                preview.set(x as usize, y as usize, c);
            }
        }
        preview.save(dir.join("preview.png"), false)?;
        println!("{folder}");
        Ok(())
    }
}
