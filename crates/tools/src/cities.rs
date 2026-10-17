//! Paints the Tokyo, New York, Chicago, London, Paris and Taipei maps in a GBA Pokemon-style 3/4
//! view (buildings show their roof and their front, trees have round canopies and a trunk,
//! grass has tufts and flowers), plus each city's CPU car sprite and the map previews.
//!
//! Like San Francisco (sf.rs), each map is 1920 x 1080 painted at 1/3 size (640 x 360 art
//! pixels) and scaled up 3x. Every map starts the same way: the last straight comes in along
//! y = 230, turns up at x = 200 and crosses the checkered line at y = 300. Coordinates are world
//! coordinates (y up) unless they're called ax / ay (art pixels, y down).

use std::f64::consts::PI;
use std::path::Path;

use crate::image::{blend, rgb, Picture};
use crate::jrand::{round, JRandom};

pub const W: i32 = 1920;
pub const H: i32 = 1080;
pub const S: i32 = 3;
pub const AW: i32 = W / S;
pub const AH: i32 = H / S;
/// half the road's width, and the sidewalk beside it (the drivable run-off)
pub const ROAD: i32 = 64;
pub const WALK: i32 = 20;

// the shared palette
pub const OUTLINE: u32 = rgb(0x2a1d1a);
const ASPHALT: u32 = rgb(0x4d4f57);
const ASPHALT_SPECK: u32 = rgb(0x45474e);
const CURB: u32 = rgb(0x7c786f);
const WALK_A: u32 = rgb(0xd2cbbd);
const WALK_B: u32 = rgb(0xc1b9a9);
const PAVE: u32 = rgb(0xbdb6a7);
const PAVE_LINE: u32 = rgb(0xaaa395);
const GRASS: u32 = rgb(0x78c058);
const GRASS_DARK: u32 = rgb(0x5fa548);
const GRASS_LIGHT: u32 = rgb(0x98d870);
const TREE: u32 = rgb(0x3f8f3c);
const TREE_LIGHT: u32 = rgb(0x66b94f);
const TREE_DARK: u32 = rgb(0x2b6230);
const TRUNK: u32 = rgb(0x7a4a2a);
const WATER: u32 = rgb(0x3f8fd8);
const WATER_DARK: u32 = rgb(0x3378c0);
const SPARKLE: u32 = rgb(0xbfe6ff);
const SHORE: u32 = rgb(0xa6a29a);
const GLASS: u32 = rgb(0x9fd3f0);
const GLASS_DARK: u32 = rgb(0x6aa6cf);
const DOOR: u32 = rgb(0x5a3a22);
const WHITE: u32 = rgb(0xf6f3ea);
const LIT: u32 = rgb(0xffe58a);
const BLACK: u32 = rgb(0x1d1c20);

const TOKYO_ROOFS: [u32; 5] = [rgb(0x4f6fa8), rgb(0x5d6b7a), rgb(0x3f5f8f), rgb(0x8a4b3c), rgb(0x6b7d5a)];
const TOKYO_WALLS: [u32; 4] = [rgb(0xf3ecdc), rgb(0xe9e2d0), rgb(0xf6f1e6), rgb(0xe2d6bd)];
const TOWER_GLASS: [u32; 5] = [rgb(0x9cc4dc), rgb(0xb8c6d2), rgb(0x8fb0c8), rgb(0xc9d1d8), rgb(0xa7b8c4)];
const NEON: [u32; 6] = [rgb(0xff4fa3), rgb(0x3fe0ff), rgb(0xffe23f), rgb(0x8cff5a), rgb(0xff7a3f), rgb(0xb57cff)];
const CHI_GLASS: [u32; 5] = [rgb(0x5a5d66), rgb(0x8a7a6a), rgb(0x9cb0c0), rgb(0x6f7f8f), rgb(0xa08a6a)];
const TAIPEI_CONCRETE: [u32; 4] = [rgb(0xd8d2c6), rgb(0xc9c3b6), rgb(0xe3dccf), rgb(0xbfc6c9)];
const AWNINGS: [u32; 4] = [rgb(0xd9433b), rgb(0x2f8f6a), rgb(0x3b6fd9), rgb(0xe08a2e)];

/// the racing line of a map, from sim::maps (the last point is the start line)
pub fn racing_line(id: &str) -> Vec<[i32; 2]> {
    sim::maps::get(id).waypoints.iter().map(|&(x, y)| [x as i32, y as i32]).collect()
}

fn sin(v: f64) -> f64 {
    v.sin()
}

fn cos(v: f64) -> f64 {
    v.cos()
}

fn sq(v: f64) -> f64 {
    v * v
}

/// one map being painted
struct City {
    path: Vec<[i32; 2]>,
    water: Box<dyn Fn(i32, i32) -> bool>,
    art: Vec<u32>,
    reserved: Vec<bool>,
    rnd: JRandom,
    water_color: u32,
    water_dark: u32,
}

type Painter = fn() -> City;

pub fn paint_all(root: &Path) -> Result<(), String> {
    let maps: [(&str, Painter); 6] = [
        ("tokyo", paint_tokyo),
        ("nyc", paint_new_york),
        ("chicago", paint_chicago),
        ("london", paint_london),
        ("paris", paint_paris),
        ("taipei", paint_taipei),
    ];
    for (id, paint) in maps {
        paint().save(root, id)?;
    }
    for (name, rows, letters, colors) in sprites() {
        sprite(rows, letters, &colors).save(root.join(format!("assets/ui/{name}.png")), true)?;
    }
    println!("Tokyo, New York, Chicago, London, Paris and Taipei painted");
    Ok(())
}

impl City {
    fn new(id: &str, water: impl Fn(i32, i32) -> bool + 'static, seed: i64) -> City {
        City {
            path: racing_line(id),
            water: Box::new(water),
            art: vec![0; (AW * AH) as usize],
            reserved: vec![false; (AW * AH) as usize],
            rnd: JRandom::new(seed),
            water_color: WATER,
            water_dark: WATER_DARK,
        }
    }

    fn save(&self, root: &Path, folder: &str) -> Result<(), String> {
        let dir = root.join(format!("assets/maps/{folder}"));
        let mut map = Picture::new(W as usize, H as usize);
        for y in 0..H {
            for x in 0..W {
                map.set(x as usize, y as usize, self.art[((y / S) * AW + x / S) as usize]);
            }
        }
        map.save(dir.join("background.png"), true)?;
        let mut mask = Picture::new(W as usize, H as usize);
        for y in 0..H {
            for x in 0..W {
                mask.set(x as usize, y as usize, if self.road_distance(x, H - y) <= ROAD { 0xffffffff } else { 0xff000000 });
            }
        }
        mask.save(dir.join("road_mask.png"), false)?;
        map.shrink(384, 216).save(dir.join("preview.png"), false)
    }

    // ================================================================== pixels

    fn set(&mut self, ax: i32, ay: i32, c: u32) {
        if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
            self.art[(ay * AW + ax) as usize] = c;
        }
    }

    fn get(&self, ax: i32, ay: i32) -> u32 {
        if ax < 0 || ay < 0 || ax >= AW || ay >= AH {
            return 0;
        }
        self.art[(ay * AW + ax) as usize]
    }

    fn shade(&mut self, ax: i32, ay: i32, f: f64) {
        if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
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

    fn pick(&mut self, colors: &[u32]) -> u32 {
        colors[self.rnd.next_int(colors.len() as i32) as usize]
    }

    // ================================================================== geometry

    fn road_distance(&self, x: i32, y: i32) -> i32 {
        let mut best = i32::MAX;
        let n = self.path.len();
        for i in 0..n {
            let (a, b) = (self.path[i], self.path[(i + 1) % n]);
            let dx = (a[0].min(b[0]) - x).max(x - a[0].max(b[0])).max(0);
            let dy = (a[1].min(b[1]) - y).max(y - a[1].max(b[1])).max(0);
            best = best.min(dx.max(dy));
        }
        best
    }

    fn is_water(&self, x: i32, y: i32) -> bool {
        (self.water)(x, y)
    }

    fn near_water(&self, x: i32, y: i32, d: i32) -> bool {
        self.is_water(x + d, y) || self.is_water(x - d, y) || self.is_water(x, y + d) || self.is_water(x, y - d)
    }

    fn near_water_all(&self, x: i32, y: i32, d: i32) -> bool {
        self.is_water(x + d, y) && self.is_water(x - d, y) && self.is_water(x, y + d) && self.is_water(x, y - d)
    }

    /// room for scenery: not road, sidewalk, fence, water, shore or a reserved spot
    fn free(&self, x: i32, y: i32) -> bool {
        if x < 0 || y < 0 || x >= W || y >= H {
            return false;
        }
        self.road_distance(x, y) > ROAD + WALK + S
            && !self.is_water(x, y)
            && !self.near_water(x, y, 6)
            && !self.reserved[(((H - 1 - y) / S) * AW + x / S) as usize]
    }

    /// like free_art, but ignores reserved spots: for painting a landmark's own grounds
    fn open_art(&self, ax: i32, ay: i32) -> bool {
        if ax < 0 || ay < 0 || ax >= AW || ay >= AH {
            return false;
        }
        let (x, y) = (ax * S + 1, H - 1 - ay * S - 1);
        self.road_distance(x, y) > ROAD + WALK + S && !self.is_water(x, y) && !self.near_water(x, y, 6)
    }

    fn free_art(&self, ax: i32, ay: i32) -> bool {
        ax >= 0 && ay >= 0 && ax < AW && ay < AH && self.free(ax * S + 1, H - 1 - ay * S - 1)
    }

    fn reserve(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        for ay in (H - y1) / S..=(H - y0) / S {
            for ax in x0 / S..=x1 / S {
                if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
                    self.reserved[(ay * AW + ax) as usize] = true;
                }
            }
        }
    }

    fn reserve_art(&mut self, ax0: i32, ay0: i32, ax1: i32, ay1: i32) {
        for ay in ay0..=ay1 {
            for ax in ax0..=ax1 {
                if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
                    self.reserved[(ay * AW + ax) as usize] = true;
                }
            }
        }
    }

    // ================================================================== ground, water, parks

    fn ground(&mut self, grass: u32, grass_dark: u32) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                if self.is_water(x, y) {
                    // the darker band near the shore, wave sparkles in rows
                    let n = (ax * 5 + (ay / 2) * 11) % 29;
                    let c = if self.near_water(x, y, 10) && !self.near_water_all(x, y, 10) { self.water_dark } else { self.water_color };
                    self.set(ax, ay, if n == 0 { SPARKLE } else if n == 1 { rgb(0x7fbcef) } else { c });
                } else if self.near_water(x, y, 6) {
                    // stone embankment with a lit top edge
                    let c = if self.is_water(x, y - 9) { rgb(0x8d8a84) } else { SHORE };
                    self.set(ax, ay, c);
                } else {
                    // Pokemon-style grass: a checker of two greens with little tufts
                    let mut c = if (ax / 2 + ay / 2) % 2 == 0 { grass } else { blend(grass, grass_dark, 0.35) };
                    if (ax * 7 + ay * 3) % 23 == 0 {
                        c = grass_dark;
                    } else if (ax * 7 + ay * 3) % 23 == 1 {
                        c = GRASS_LIGHT;
                    }
                    self.set(ax, ay, c);
                }
            }
        }
    }

    /// a park: flowers, paths and lots of trees (cherry blossoms in Tokyo)
    fn park(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, flowers: bool, cherry: bool) {
        for ay in (H - y1) / S..(H - y0) / S {
            for ax in x0 / S..x1 / S {
                if self.free_art(ax, ay) && flowers && (ax * 13 + ay * 7) % 41 == 0 {
                    let c = if (ax + ay) % 3 == 0 { rgb(0xf05a6e) } else if (ax + ay) % 3 == 1 { rgb(0xffe066) } else { WHITE };
                    self.flower(ax, ay, c);
                }
            }
        }
        self.forest(x0, y0, x1, y1, if cherry { 0.5 } else { 0.0 }, 1300);
    }

    fn forest(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, cherry_share: f64, spacing: i32) {
        let count = (x1 - x0) * (y1 - y0) / spacing;
        for _ in 0..count {
            let x = x0 + self.rnd.next_int((x1 - x0).max(1));
            let y = y0 + self.rnd.next_int((y1 - y0).max(1));
            let (ax, ay) = (x / S, (H - y) / S);
            let mut room = true;
            let mut dy = -6;
            while dy <= 3 && room {
                let mut dx = -5;
                while dx <= 5 && room {
                    room = self.free_art(ax + dx, ay + dy) && !self.is_tree_or_building(ax + dx, ay + dy);
                    dx += 1;
                }
                dy += 1;
            }
            if room {
                let cherry = self.rnd.next_double() < cherry_share;
                self.tree(ax, ay, cherry);
            }
        }
    }

    fn is_tree_or_building(&self, ax: i32, ay: i32) -> bool {
        let c = self.get(ax, ay);
        c == OUTLINE || c == TREE || c == TREE_DARK || c == rgb(0xf4a7c0) || c == rgb(0xd97a9a)
    }

    /// a Pokemon-style tree: round two-tone canopy with an outline, short trunk, shadow
    fn tree(&mut self, ax: i32, ay: i32, cherry: bool) {
        let main = if cherry { rgb(0xf4a7c0) } else { TREE };
        let light = if cherry { rgb(0xfdd5e3) } else { TREE_LIGHT };
        let dark = if cherry { rgb(0xd97a9a) } else { TREE_DARK };
        for dx in -4i32..=4 {
            self.shade(ax + dx + 1, ay + 2, 0.7); // shadow on the ground
        }
        for dy in 0i32..=1 {
            self.set(ax, ay + dy, TRUNK);
            self.set(ax + 1, ay + dy, rgb(0x5e3820));
        }
        let cy = ay - 4;
        for dy in -4i32..=3 {
            for dx in -5i32..=5 {
                let d = (dx * dx) as f64 / 25.0 + (dy * dy) as f64 / 16.0;
                if d <= 1.0 {
                    let c = if d > 0.72 {
                        OUTLINE
                    } else if dy < -1 && dx < 1 {
                        light
                    } else if dy > 1 || dx > 2 {
                        dark
                    } else {
                        main
                    };
                    self.set(ax + dx, cy + dy, c);
                }
            }
        }
        if cherry {
            self.set(ax - 2, cy - 1, WHITE);
            self.set(ax + 2, cy + 1, WHITE);
        }
    }

    fn flower(&mut self, ax: i32, ay: i32, color: u32) {
        self.set(ax, ay, color);
        self.set(ax + 1, ay, blend(color, OUTLINE, 0.3));
        self.set(ax, ay + 1, GRASS_DARK);
    }

    // ================================================================== buildings

    /// fills an area with paving and the buildings of one style
    fn district(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, style: &str) {
        let (ax0, ax1, ay0, ay1) = (x0 / S, x1 / S, (H - y1) / S, (H - y0) / S);
        for ay in ay0..ay1 {
            for ax in ax0..ax1 {
                if self.free_art(ax, ay) {
                    self.set(ax, ay, if ax % 8 == 0 || ay % 8 == 0 { PAVE_LINE } else { PAVE });
                }
            }
        }
        let row_gap = 3;
        let mut ay = ay0 + 1;
        while ay < ay1 - 6 {
            let row_height = self.row_height(style);
            let mut ax = ax0 + 1;
            while ax < ax1 - 6 {
                let w = self.width(style);
                if self.fits(ax, ay, w, row_height) {
                    self.draw_building(ax, ay, w, row_height, style);
                    ax += w + 1 + self.rnd.next_int(2);
                } else if self.fits(ax, ay, 6, 6) && self.rnd.next_int(3) == 0 {
                    let cherry = style.starts_with("tokyo") && self.rnd.next_int(3) == 0;
                    self.tree(ax + 3, ay + 7, cherry);
                    ax += 8;
                } else {
                    ax += 2;
                }
            }
            ay += row_height + row_gap;
        }
    }

    fn row_height(&mut self, style: &str) -> i32 {
        match style {
            "tokyo-towers" | "nyc-towers" | "chi-towers" | "london-city" | "taipei-towers" => 22 + self.rnd.next_int(10),
            "paris-haussmann" => 18,
            "taipei-shops" => 17,
            "london-terraces" => 15,
            "nyc-brownstones" => 17,
            "tokyo-shops" => 13,
            _ => 14,
        }
    }

    fn width(&mut self, style: &str) -> i32 {
        match style {
            "tokyo-towers" | "nyc-towers" | "chi-towers" | "london-city" | "taipei-towers" => 13 + self.rnd.next_int(10),
            "paris-haussmann" => 16 + self.rnd.next_int(8),
            "taipei-shops" => 8 + self.rnd.next_int(3),
            "london-terraces" => 9 + self.rnd.next_int(2),
            "nyc-brownstones" => 9 + self.rnd.next_int(2),
            "tokyo-shops" => 12 + self.rnd.next_int(6),
            _ => 13 + self.rnd.next_int(5),
        }
    }

    fn fits(&self, ax: i32, ay: i32, w: i32, h: i32) -> bool {
        for y in ay - 1..=ay + h + 1 {
            for x in ax - 1..=ax + w + 1 {
                if !self.free_art(x, y) || self.is_tree_or_building(x, y) {
                    return false;
                }
            }
        }
        true
    }

    fn draw_building(&mut self, ax: i32, ay: i32, w: i32, h: i32, style: &str) {
        match style {
            "tokyo-houses" | "tokyo-shops" => {
                let roof = self.pick(&TOKYO_ROOFS);
                let wall = self.pick(&TOKYO_WALLS);
                self.house(ax, ay, w, h, roof, wall, style == "tokyo-shops");
            }
            "tokyo-towers" => {
                let glass = self.pick(&TOWER_GLASS);
                self.tower(ax, ay, w, h, glass, true, false);
            }
            "nyc-towers" => {
                let glass = self.pick(&TOWER_GLASS);
                self.tower(ax, ay, w, h, glass, false, true);
            }
            "chi-towers" => {
                let glass = self.pick(&CHI_GLASS);
                let water_tower = self.rnd.next_int(4) == 0;
                self.tower(ax, ay, w, h, glass, false, water_tower);
            }
            "chi-houses" => self.house(ax, ay, w, h, rgb(0x5d4a3e), rgb(0xb5674a), false),
            "london-city" => {
                let glass = self.pick(&TOWER_GLASS);
                self.tower(ax, ay, w, h, glass, false, false);
            }
            "london-terraces" => {
                let wall = if self.rnd.next_int(3) == 0 { rgb(0xe8e2d0) } else { rgb(0xa8583c) };
                self.house(ax, ay, w, h, rgb(0x55606b), wall, false);
                self.set(ax + 1, ay - 1, rgb(0x8a4a3a)); // chimney pots
                self.set(ax + w - 2, ay - 1, rgb(0x8a4a3a));
            }
            "paris-haussmann" => self.haussmann(ax, ay, w, h),
            "paris-cafes" => {
                self.house(ax, ay, w, h, rgb(0x6f7f93), rgb(0xefe3c8), true);
                let mut t = 1;
                while t < w - 1 {
                    if self.free_art(ax + t, ay + h + 2) {
                        self.set(ax + t, ay + h + 2, WHITE); // cafe tables
                        self.set(ax + t, ay + h + 3, rgb(0x6a4a32));
                    }
                    t += 3;
                }
            }
            "taipei-shops" => {
                let glass = self.pick(&TAIPEI_CONCRETE);
                self.tower(ax, ay, w, h, glass, true, false);
            }
            "taipei-towers" => {
                let glass = self.pick(&TOWER_GLASS);
                self.tower(ax, ay, w, h, glass, false, false);
            }
            "taipei-houses" => self.house(ax, ay, w, h, rgb(0xc8502f), rgb(0xf1ebe0), false),
            _ => self.brownstone(ax, ay, w, h),
        }
    }

    /// a small house or shop: tiled roof on top, front wall with windows and a door below
    #[allow(clippy::too_many_arguments)]
    fn house(&mut self, ax: i32, ay: i32, w: i32, h: i32, roof: u32, wall: u32, shop: bool) {
        let rh = h * 5 / 11;
        self.drop_shadow(ax, ay, w, h);
        for y in 0..rh {
            for x in 0..w {
                let c = if y == 0 {
                    blend(roof, WHITE, 0.3) // the ridge catches the light
                } else if y % 2 == 0 {
                    blend(roof, OUTLINE, 0.25) // rows of tiles
                } else if x % 3 == 0 {
                    blend(roof, OUTLINE, 0.1)
                } else {
                    roof
                };
                self.set(ax + x, ay + y, c);
            }
        }
        // the eave's shadow, then the wall
        for x in 0..w {
            self.set(ax + x, ay + rh, blend(roof, OUTLINE, 0.55));
        }
        for y in rh + 1..h {
            for x in 0..w {
                self.set(ax + x, ay + y, if x == w - 1 { blend(wall, OUTLINE, 0.2) } else { wall });
            }
        }
        if shop {
            // a striped awning and a big shop window
            let awning = self.pick(&AWNINGS);
            for x in 0..w {
                self.set(ax + x, ay + rh + 1, if x % 2 == 0 { awning } else { WHITE });
                self.set(ax + x, ay + rh + 2, blend(awning, OUTLINE, 0.3));
            }
            for x in 1..w - 4 {
                for y in rh + 3..h - 1 {
                    self.set(ax + x, ay + y, if y == rh + 3 { GLASS } else { GLASS_DARK });
                }
            }
            self.door(ax + w - 3, ay + h - 4);
            // a hanging sign
            let a = self.pick(&NEON);
            self.set(ax + 1, ay + rh - 1, a);
            let b = self.pick(&NEON);
            self.set(ax + 2, ay + rh - 1, b);
        } else {
            let mut x = 1;
            while x + 2 < w {
                if (x + 1 - w / 2).abs() > 1 {
                    self.window(ax + x, ay + rh + 2);
                }
                x += 4;
            }
            self.door(ax + w / 2 - 1, ay + h - 4);
        }
        self.outline(ax, ay, w, h);
    }

    /// a tall building seen from the front: flat roof (with neon or a water tower), window grid
    #[allow(clippy::too_many_arguments)]
    fn tower(&mut self, ax: i32, ay: i32, w: i32, h: i32, glass: u32, neon: bool, water_tower: bool) {
        let rh = 4;
        self.drop_shadow(ax, ay, w, h);
        for y in 0..rh {
            for x in 0..w {
                let c = if y == 0 { rgb(0xd7d3cb) } else if (x + y) % 5 == 0 { rgb(0x8f8b84) } else { rgb(0xb3aea5) };
                self.set(ax + x, ay + y, c);
            }
        }
        for x in 0..w {
            self.set(ax + x, ay + rh, rgb(0x6f6b65));
        }
        for y in rh + 1..h {
            for x in 0..w {
                let c = if x == 0 {
                    blend(glass, WHITE, 0.35) // the lit edge
                } else if x == w - 1 {
                    blend(glass, OUTLINE, 0.35) // the shaded edge
                } else if (y - rh) % 3 == 0 {
                    blend(glass, OUTLINE, 0.28) // floor lines
                } else if x % 3 == 0 {
                    blend(glass, OUTLINE, 0.12) // mullions
                } else if self.rnd.next_int(14) == 0 {
                    LIT // a lit window
                } else {
                    glass
                };
                self.set(ax + x, ay + y, c);
            }
        }
        // the lobby doors at street level
        for x in w / 2 - 2..=w / 2 + 1 {
            self.set(ax + x, ay + h - 2, GLASS_DARK);
            self.set(ax + x, ay + h - 3, GLASS_DARK);
        }
        if neon && w >= 10 {
            // vertical neon signs down the front and a billboard on the roof
            let c = self.pick(&NEON);
            for y in rh + 2..h - 4 {
                self.set(ax + 1, ay + y, if y % 2 == 0 { c } else { blend(c, WHITE, 0.4) });
            }
            let b = self.pick(&NEON);
            for x in 2..w - 2 {
                self.set(ax + x, ay + 1, b);
                self.set(ax + x, ay + 2, if x % 2 == 0 { WHITE } else { b });
            }
        }
        if water_tower && w >= 9 && self.rnd.next_int(2) == 0 {
            let at = ax + 2 + self.rnd.next_int((w - 7).max(1));
            self.water_tower(at, ay);
        }
        self.outline(ax, ay, w, h);
    }

    /// a Paris Haussmann building: blue-gray mansard roof with dormers, cream stone, balconies
    fn haussmann(&mut self, ax: i32, ay: i32, w: i32, h: i32) {
        self.drop_shadow(ax, ay, w, h);
        let rh = 5;
        for y in 0..rh {
            for x in 0..w {
                let c = if y == 0 {
                    rgb(0x9aa6b4)
                } else if x % 4 == 2 && y > 1 {
                    WHITE
                } else if y % 2 == 0 {
                    rgb(0x5f6f83)
                } else {
                    rgb(0x6f7f93)
                };
                self.set(ax + x, ay + y, c);
            }
        }
        for y in rh..h {
            for x in 0..w {
                let mut c = if x == w - 1 { rgb(0xcbbf9f) } else { rgb(0xefe3c8) };
                if y % 4 == 0 {
                    c = rgb(0x2a2a30); // wrought-iron balconies
                } else if x % 3 == 1 && y % 4 != 1 {
                    c = GLASS_DARK;
                }
                self.set(ax + x, ay + y, c);
            }
        }
        self.door(ax + w / 2 - 1, ay + h - 4);
        self.outline(ax, ay, w, h);
    }

    /// a New York water tower on its stilts, poking above the roof
    fn water_tower(&mut self, ax: i32, ay: i32) {
        let wood = rgb(0x8a5a36);
        for y in -5..=-2 {
            for x in 0..4 {
                let c = if x == 0 {
                    blend(wood, WHITE, 0.25)
                } else if y % 2 == 0 {
                    blend(wood, OUTLINE, 0.3)
                } else {
                    wood
                };
                self.set(ax + x, ay + y, c);
            }
        }
        self.set(ax + 1, ay - 6, OUTLINE);
        self.set(ax + 2, ay - 6, OUTLINE);
        self.set(ax, ay - 1, OUTLINE);
        self.set(ax + 3, ay - 1, OUTLINE);
        self.set(ax, ay, OUTLINE);
        self.set(ax + 3, ay, OUTLINE);
    }

    /// a brownstone: flat roof, brown front with tall windows, a stoop and a fire escape
    fn brownstone(&mut self, ax: i32, ay: i32, w: i32, h: i32) {
        let brick = if self.rnd.next_int(3) == 0 { rgb(0xa0583c) } else { rgb(0x8a5a3c) };
        self.drop_shadow(ax, ay, w, h);
        for y in 0..3 {
            for x in 0..w {
                self.set(ax + x, ay + y, if y == 0 { rgb(0xa49c90) } else { rgb(0x7f786e) });
            }
        }
        for x in 0..w {
            self.set(ax + x, ay + 3, rgb(0x5a4a3e)); // the cornice
        }
        for y in 4..h {
            for x in 0..w {
                let c = if y % 4 == 0 {
                    blend(brick, OUTLINE, 0.18)
                } else if x == w - 1 {
                    blend(brick, OUTLINE, 0.25)
                } else {
                    brick
                };
                self.set(ax + x, ay + y, c);
            }
        }
        let mut y = 5;
        while y < h - 5 {
            let mut x = 1;
            while x + 1 < w {
                self.set(ax + x, ay + y, GLASS);
                self.set(ax + x, ay + y + 1, GLASS_DARK);
                x += 3;
            }
            y += 4;
        }
        // the fire escape zig-zag
        for y in 5..h - 5 {
            self.set(ax + w - 3, ay + y, if (y / 2) % 2 == 0 { BLACK } else { rgb(0x3a3a40) });
        }
        // the stoop: steps up to the door
        self.door(ax + 1, ay + h - 4);
        for i in 0..3 {
            self.set(ax + 3 + i, ay + h - 1 - i, rgb(0xb8aa94));
        }
        if self.rnd.next_int(4) == 0 {
            self.water_tower(ax + 2, ay);
        }
        self.outline(ax, ay, w, h);
    }

    fn window(&mut self, ax: i32, ay: i32) {
        self.set(ax, ay, GLASS);
        self.set(ax + 1, ay, WHITE);
        self.set(ax, ay + 1, GLASS_DARK);
        self.set(ax + 1, ay + 1, GLASS);
    }

    fn door(&mut self, ax: i32, ay: i32) {
        for y in 0..3 {
            self.set(ax, ay + y, DOOR);
            self.set(ax + 1, ay + y, blend(DOOR, OUTLINE, 0.3));
        }
        self.set(ax + 1, ay + 1, LIT);
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

    fn drop_shadow(&mut self, ax: i32, ay: i32, w: i32, h: i32) {
        for y in 1..=h + 1 {
            self.shade(ax + w + 1, ay + y, 0.65);
        }
        for x in 1..=w + 1 {
            self.shade(ax + x, ay + h + 1, 0.65);
        }
    }

    // ================================================================== roads

    /// asphalt, curb, sidewalk, the fence line with street furniture, lane dashes
    fn roads(&mut self, lane_color: u32, tokyo: bool) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                let d = self.road_distance(x, y);
                let over_water = self.is_water(x, y) || self.near_water(x, y, 6);
                if d <= ROAD {
                    let c = if d > ROAD - S {
                        CURB
                    } else if (ax * 5 + ay * 3) % 17 == 0 {
                        ASPHALT_SPECK
                    } else {
                        ASPHALT
                    };
                    self.set(ax, ay, c);
                    // a white edge line just inside the curb
                    if d > ROAD - 3 * S && d <= ROAD - 2 * S && !over_water {
                        self.set(ax, ay, rgb(0xd8d4ca));
                    }
                } else if d <= ROAD + WALK && !over_water {
                    self.set(ax, ay, if ax % 4 == 0 || ay % 4 == 0 { WALK_B } else { WALK_A });
                } else if d <= ROAD + WALK + S && !over_water {
                    self.set(ax, ay, if (ax + ay) % 4 == 0 { rgb(0xe8c547) } else { rgb(0x55514b) }); // curb and bollards
                }
            }
        }
        // street furniture along the fence line: lamps, and vending machines or hydrants
        for i in 0..self.path.len() - 1 {
            let (a, b) = (self.path[i], self.path[i + 1]);
            let len = (b[0] - a[0]).abs() + (b[1] - a[1]).abs();
            let (dir_x, dir_y) = ((b[0] - a[0]).signum(), (b[1] - a[1]).signum());
            let mut t = ROAD + 40;
            while t < len - ROAD - 40 {
                for side in [-1, 1] {
                    let x = a[0] + dir_x * t + side * dir_y * (ROAD + WALK + 8);
                    let y = a[1] + dir_y * t - side * dir_x * (ROAD + WALK + 8);
                    if !self.free(x, y) {
                        continue;
                    }
                    let (ax, ay) = (x / S, (H - y) / S);
                    if (t / 66) % 3 == 0 {
                        self.lamp(ax, ay);
                    } else if tokyo && (t / 66) % 3 == 1 {
                        self.vending(ax, ay);
                    } else if !tokyo && (t / 66) % 3 == 1 {
                        self.hydrant(ax, ay);
                    }
                }
                t += 66;
            }
        }
        // a dashed lane line down the middle of every straight
        for i in 0..self.path.len() - 1 {
            let (a, b) = (self.path[i], self.path[i + 1]);
            let len = (b[0] - a[0]).abs() + (b[1] - a[1]).abs();
            let mut t = ROAD + 30;
            while t < len - ROAD - 30 {
                let x = a[0] + (b[0] - a[0]).signum() * t;
                let y = a[1] + (b[1] - a[1]).signum() * t;
                if (t / 18) % 2 == 0 {
                    self.set(x / S, (H - y) / S, lane_color);
                    if !tokyo {
                        // New York: a double yellow line
                        let vertical = a[0] == b[0];
                        self.set(x / S + vertical as i32, (H - y) / S + (!vertical) as i32, lane_color);
                    }
                }
                t += S;
            }
            // a manhole now and then
            let mut t = ROAD + 90;
            while t < len - ROAD - 30 {
                let x = a[0] + (b[0] - a[0]).signum() * t + 30;
                let y = a[1] + (b[1] - a[1]).signum() * t + 30;
                let (ax, ay) = (x / S, (H - y) / S);
                if self.road_distance(x, y) < ROAD - 12 && !self.is_water(x, y) {
                    self.set(ax, ay, rgb(0x3a3c42));
                    self.set(ax + 1, ay, rgb(0x5a5c63));
                    self.set(ax, ay + 1, rgb(0x5a5c63));
                    self.set(ax + 1, ay + 1, rgb(0x3a3c42));
                }
                t += 240;
            }
        }
    }

    fn lamp(&mut self, ax: i32, ay: i32) {
        for y in -5..=0 {
            self.set(ax, ay + y, rgb(0x3d3f46));
        }
        self.set(ax - 1, ay - 6, OUTLINE);
        self.set(ax, ay - 6, LIT);
        self.set(ax + 1, ay - 6, OUTLINE);
        self.shade(ax + 1, ay + 1, 0.7);
    }

    fn vending(&mut self, ax: i32, ay: i32) {
        let c = if NEON[self.rnd.next_int(3) as usize] == NEON[0] { rgb(0xd9433b) } else { rgb(0x3b6fd9) };
        for y in -4..=0 {
            for x in 0..3 {
                self.set(ax + x, ay + y, if y == -4 { WHITE } else if y == -2 { GLASS } else { c });
            }
        }
        self.outline(ax, ay - 4, 3, 5);
    }

    fn hydrant(&mut self, ax: i32, ay: i32) {
        self.set(ax, ay - 2, rgb(0xd9433b));
        self.set(ax - 1, ay - 1, rgb(0xa52f2a));
        self.set(ax, ay - 1, rgb(0xd9433b));
        self.set(ax + 1, ay - 1, rgb(0xa52f2a));
        self.set(ax, ay, rgb(0xa52f2a));
    }

    /// zebra crossings across the road just before every corner
    fn crosswalks(&mut self) {
        for i in 0..self.path.len() - 1 {
            let (a, b) = (self.path[i], self.path[i + 1]);
            let vertical = a[0] == b[0];
            let len = (b[0] - a[0]).abs() + (b[1] - a[1]).abs();
            if len < 3 * ROAD + 60 {
                continue;
            }
            for end in 0..2 {
                let t = if end == 0 { ROAD + 6 } else { len - ROAD - 30 };
                let mut k = 0;
                while k < 24 {
                    let mut across = -ROAD + 12;
                    while across <= ROAD - 12 {
                        if ((across + ROAD) / 9) % 2 != 0 {
                            across += S;
                            continue;
                        }
                        let x = if vertical { a[0] + across } else { a[0] + (b[0] - a[0]).signum() * (t + k) };
                        let y = if vertical { a[1] + (b[1] - a[1]).signum() * (t + k) } else { a[1] + across };
                        if !self.is_water(x, y) && !((x - 200).abs() < ROAD && y > 250 && y < 340) {
                            self.set(x / S, (H - y) / S, WHITE);
                        }
                        across += S;
                    }
                    k += S;
                }
            }
        }
    }

    /// the Shibuya scramble: stripes across all four sides and both diagonals
    fn scramble(&mut self, cx: i32, cy: i32) {
        let mut y = cy - ROAD + 6;
        while y <= cy + ROAD - 6 {
            let mut x = cx - 70;
            while x <= cx + 70 {
                let (dx, dy) = (x - cx, y - cy);
                let diagonal = (dx.abs() - dy.abs() * 70 / (ROAD - 6)).abs() < 9;
                let side = dx.abs() > 52;
                if (diagonal || side) && ((if side { y } else { x + y }) / 7) % 2 == 0 {
                    self.set(x / S, (H - y) / S, WHITE);
                }
                x += S;
            }
            y += S;
        }
        // the big screens looking down on the crossing
        for sc in [[870, 690], [1060, 700], [940, 560]] {
            let (ax, ay) = (sc[0] / S, (H - sc[1]) / S);
            for y in 0..6 {
                for x in 0..10 {
                    let c = NEON[((x / 3 + y / 2) % NEON.len() as i32) as usize];
                    self.set(ax + x, ay + y, if x == 0 || y == 0 || x == 9 || y == 5 { OUTLINE } else { c });
                }
            }
        }
    }

    /// where the road crosses water: a deck with railings (and towers for New York's bridges)
    fn bridges(&mut self, rail: u32, rail_dark: u32, suspension: bool) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                if !(self.is_water(x, y) || self.near_water(x, y, 6)) {
                    continue;
                }
                let d = self.road_distance(x, y);
                if d > ROAD && d <= ROAD + WALK - S {
                    self.set(ax, ay, rgb(0x9b958c));
                } else if d > ROAD + WALK - S && d <= ROAD + WALK + S {
                    self.set(ax, ay, if (ax + ay) % 3 == 0 { rail_dark } else { rail });
                } else if d > ROAD + WALK + S && d <= ROAD + WALK + 3 * S {
                    self.shade(ax, ay, 0.7);
                }
            }
        }
        if !suspension {
            return;
        }
        // stone towers with arches where each bridge meets the river's middle
        for i in 0..self.path.len() - 1 {
            let (a, b) = (self.path[i], self.path[i + 1]);
            if a[1] != b[1] {
                continue;
            }
            let mut x = a[0].min(b[0]);
            while x <= a[0].max(b[0]) {
                let probe = a[1] + ROAD + WALK + 20;
                if self.is_water(x, probe) && self.is_water(x - 60, probe) && !self.is_water(x - 120, probe) {
                    self.stone_tower(x / S, (H - a[1]) / S);
                    break;
                }
                x += S;
            }
        }
    }

    fn stone_tower(&mut self, ax: i32, cy: i32) {
        let top = cy - (ROAD + WALK) / S - 10;
        let bottom = cy + (ROAD + WALK) / S + 4;
        for y in top..=bottom {
            for x in ax - 4..=ax + 4 {
                let arch = (x - ax).abs() <= 1 && (y > top + 3 && y < cy - 4 || y > cy + 4 && y < bottom - 2);
                let c = if (x - ax).abs() == 4 || y == top || y == bottom {
                    OUTLINE
                } else if arch {
                    rgb(0x4a4136)
                } else if x < ax {
                    rgb(0xd6c6a3)
                } else {
                    rgb(0xb9a481)
                };
                if (y - cy).abs() > (ROAD - 4) / S || (x - ax).abs() == 4 {
                    self.set(x, y, c);
                }
            }
        }
        // cables fanning out from the tower tops
        let mut k = 4;
        while k < 30 {
            self.set(ax - k, top + 1 + k / 6, rgb(0x3d3a34));
            self.set(ax + k, top + 1 + k / 6, rgb(0x3d3a34));
            self.set(ax - k, bottom - 1 - k / 6, rgb(0x3d3a34));
            self.set(ax + k, bottom - 1 - k / 6, rgb(0x3d3a34));
            k += 2;
        }
    }

    fn start_line(&mut self) {
        let mut y = 300;
        while y < 324 {
            let mut x = 200 - ROAD + S;
            while x < 200 + ROAD - S {
                let (cx, cy) = ((x - (200 - ROAD)) / 6, (y - 300) / 6);
                self.set(x / S, (H - 1 - y) / S, if (cx + cy) % 2 == 0 { WHITE } else { rgb(0x1b1b1f) });
                x += S;
            }
            y += S;
        }
    }

    /// a boat on the water: a yakatabune with lanterns (Tokyo) or a ferry with a wake
    fn boat(&mut self, x: i32, y: i32, color: u32, lanterns: bool) {
        if !self.is_water(x, y) {
            return;
        }
        let (ax, ay) = (x / S, (H - y) / S);
        for k in 1i32..=6 {
            self.set(ax - k, ay + 2 + (k % 2), SPARKLE); // the wake
        }
        for yy in 0..5 {
            for xx in 0..16 {
                let c = if yy == 0 {
                    blend(color, OUTLINE, 0.4)
                } else if yy == 4 {
                    blend(color, OUTLINE, 0.55)
                } else if xx % 3 == 1 && yy == 2 {
                    if lanterns { rgb(0xffd36b) } else { GLASS }
                } else {
                    color
                };
                self.set(ax + xx, ay + yy, c);
            }
        }
        self.outline(ax, ay, 16, 5);
        if lanterns {
            let mut xx = 2;
            while xx < 16 {
                self.set(ax + xx, ay - 2, rgb(0xff5a4a));
                xx += 4;
            }
        } else {
            for xx in 5..11 {
                self.set(ax + xx, ay - 2, WHITE); // the cabin on deck
                self.set(ax + xx, ay - 1, GLASS_DARK);
            }
        }
    }

    /// a train on its tracks: silver cars with the line's stripe
    fn train(&mut self, x0: i32, y: i32, x1: i32, sleepers: u32, stripe: u32) {
        let ay = (H - y) / S;
        for ax in x0 / S..=x1 / S {
            if ax % 3 == 0 {
                for k in -3i32..=3 {
                    self.set(ax, ay + k, sleepers); // the sleepers
                }
            }
            self.set(ax, ay - 2, rgb(0x8d8f96));
            self.set(ax, ay + 2, rgb(0x8d8f96));
        }
        let (cars, car_w) = (4, 22);
        let start = (x0 + x1) / 2 / S - cars * (car_w + 1) / 2;
        for c in 0..cars {
            let cx = start + c * (car_w + 1);
            for yy in -4..=3 {
                for xx in 0..car_w {
                    let col = if yy == -4 {
                        rgb(0xc9ccd2)
                    } else if yy == 0 {
                        stripe
                    } else if yy == -2 && xx % 4 != 0 {
                        GLASS_DARK
                    } else if yy == 3 {
                        rgb(0x7a7d84)
                    } else {
                        rgb(0xe3e5e9)
                    };
                    self.set(cx + xx, ay + yy, col);
                }
            }
            self.outline(cx, ay - 4, car_w, 8);
            for xx in 0..car_w {
                self.shade(cx + xx + 1, ay + 5, 0.7);
            }
        }
    }

    /// little people walking around the plazas
    fn people(&mut self, count: i32) {
        let clothes = [rgb(0xd9433b), rgb(0x3b6fd9), rgb(0xf2c84b), rgb(0x3fae49), rgb(0xffffff), rgb(0x2a2a30), rgb(0xff8ac0)];
        let mut placed = 0;
        let mut tries = 0;
        while tries < count * 40 && placed < count {
            let ax = self.rnd.next_int(AW);
            let ay = 1 + self.rnd.next_int(AH - 2);
            if self.get(ax, ay) == PAVE && self.get(ax, ay - 1) == PAVE && self.get(ax, ay + 1) == PAVE && self.free_art(ax, ay) {
                self.set(ax, ay - 1, rgb(0xf0c8a0)); // the head
                let c = self.pick(&clothes);
                self.set(ax, ay, c);
                self.shade(ax + 1, ay + 1, 0.7);
                placed += 1;
            }
            tries += 1;
        }
    }

    // ================================================================== Tokyo landmarks

    /// a shrine: a path of red torii gates with lanterns up to a shrine with a big roof
    fn shrine(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S, (H - y) / S);
        for py in ay - 22..=ay + 30 {
            for px in ax - 3..=ax + 3 {
                self.set(px, py, if (px + py) % 3 == 0 { rgb(0xd9d0bd) } else { rgb(0xe7dfcd) }); // gravel path
            }
        }
        let mut gy = ay - 4;
        while gy <= ay + 26 {
            for px in ax - 6..=ax + 6 {
                self.set(px, gy, rgb(0xd8342c));
                self.set(px, gy - 1, BLACK);
            }
            for py in gy + 1..=gy + 4 {
                self.set(ax - 5, py, rgb(0xb22a24));
                self.set(ax + 5, py, rgb(0xb22a24));
            }
            self.set(ax - 7, gy + 2, rgb(0xff5a4a)); // lanterns
            self.set(ax + 7, gy + 2, rgb(0xff5a4a));
            gy += 8;
        }
        // the shrine hall: wide dark roof with upturned corners, red pillars
        let (hx, hy) = (ax - 12, ay - 40);
        for ry in 0..10 {
            let inset = (3 - ry).max(0);
            for rx in inset..24 - inset {
                let c = if ry == 0 { rgb(0x7f8f86) } else if ry % 2 == 0 { rgb(0x3f4f48) } else { rgb(0x52645b) };
                self.set(hx + rx, hy + ry, c);
            }
        }
        self.set(hx, hy + 9, rgb(0x3f4f48));
        self.set(hx + 23, hy + 9, rgb(0x3f4f48));
        for ry in 10..18 {
            for rx in 2..22 {
                self.set(hx + rx, hy + ry, if rx % 5 == 2 { rgb(0xc8322a) } else { rgb(0xf1e7d0) });
            }
        }
        for rx in 9..15 {
            for ry in 13..18 {
                self.set(hx + rx, hy + ry, rgb(0x6a3a22));
            }
        }
        self.outline(hx + 2, hy + 10, 20, 8);
        // the big rope bell and a gold crest
        self.set(hx + 12, hy + 4, rgb(0xf2c84b));
    }

    /// Tokyo Tower: an orange and white lattice tower rising up the picture
    fn tokyo_tower(&mut self, x: i32, y: i32) {
        let (ax, base, height) = (x / S, (H - y) / S, 78);
        for dy in 0i32..height {
            let t = dy as f64 / height as f64;
            let half = round(16.0 * (1.0 - t).powf(1.6)) + 1;
            let band = (dy / 9) % 2;
            for dx in -half..=half {
                let edge = dx.abs() == half;
                let lattice = (dx + dy) % 4 == 0 || (dx - dy) % 4 == 0;
                let c = if edge {
                    OUTLINE
                } else if band == 0 {
                    if lattice { rgb(0xb83a1e) } else { rgb(0xf05a28) }
                } else if lattice {
                    rgb(0xc9c4ba)
                } else {
                    WHITE
                };
                if edge || lattice || half < 6 || dy > height - 30 {
                    self.set(ax + dx, base - dy, c);
                }
            }
            // the two observation decks
            if dy == 26 || dy == 46 {
                for dx in -half - 3..=half + 3 {
                    self.set(ax + dx, base - dy, OUTLINE);
                    self.set(ax + dx, base - dy - 1, rgb(0xd8d3c8));
                    self.set(ax + dx, base - dy - 2, GLASS);
                    self.set(ax + dx, base - dy - 3, OUTLINE);
                }
            }
        }
        for dy in height..height + 10 {
            self.set(ax, base - dy, if dy % 2 == 0 { rgb(0xf05a28) } else { WHITE }); // the antenna
        }
        for dx in -18i32..=18 {
            self.shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    // ================================================================== New York landmarks

    fn central_park(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        // a stone wall around it
        for ay in (H - y1) / S..=(H - y0) / S {
            for ax in x0 / S..=x1 / S {
                if !self.free_art(ax, ay) {
                    continue;
                }
                let edge = ay == (H - y1) / S || ay == (H - y0) / S || ax == x0 / S || ax == x1 / S;
                if edge {
                    self.set(ax, ay, rgb(0x8f877a));
                }
            }
        }
        // the lake, with the Bow Bridge and rowboats
        let (lx, ly) = ((x0 + x1) / 2 / S + 20, (H - (y0 + y1) / 2) / S);
        for ay in ly - 14..=ly + 14 {
            for ax in lx - 40..=lx + 40 {
                let d = sq((ax - lx) as f64 / 40.0) + sq((ay - ly) as f64 / 14.0) + 0.15 * sin(ax as f64 / 4.0) * cos(ay as f64 / 3.0);
                if d < 1.0 {
                    let c = if d > 0.86 {
                        rgb(0x8d8a84)
                    } else if (ax * 5 + ay * 7) % 23 == 0 {
                        SPARKLE
                    } else {
                        WATER
                    };
                    self.set(ax, ay, c);
                }
            }
        }
        for ax in lx - 6..=lx + 6 {
            self.set(ax, ly - 1, OUTLINE);
            self.set(ax, ly, rgb(0xe9e2d2));
            self.set(ax, ly + 1, OUTLINE);
        }
        for b in [[-26, -6], [20, 5], [-14, 7]] {
            self.set(lx + b[0], ly + b[1], rgb(0xd9433b));
            self.set(lx + b[0] + 1, ly + b[1], rgb(0xa52f2a));
        }
        // Bethesda Fountain on its terrace, west of the lake
        let (fx, fy) = (lx - 62, ly + 2);
        for ay in fy - 8..=fy + 8 {
            for ax in fx - 12..=fx + 12 {
                self.set(ax, ay, if (ax + ay) % 4 == 0 { rgb(0xc9b99a) } else { rgb(0xddd0b4) });
            }
        }
        self.disc(fx, fy, 5, OUTLINE);
        self.disc(fx, fy, 4, WATER);
        self.disc(fx, fy, 1, rgb(0x5f8f7a));
        self.set(fx, fy - 2, rgb(0x6faa8c));
        // winding paths
        for t in 0..700 {
            let px = x0 / S + 4 + t * ((x1 - x0) / S - 8) / 700;
            let py = (H - y0) / S - 10 - (8.0 * sin(t as f64 / 40.0)) as i32 - 6;
            for k in 0i32..2 {
                if self.free_art(px, py + k) && self.get(px, py + k) != WATER {
                    self.set(px, py + k, rgb(0xe5d8b8));
                }
            }
            let qy = (H - y1) / S + 8 + (5.0 * cos(t as f64 / 33.0)) as i32;
            if self.free_art(px, qy) && self.get(px, qy) != WATER {
                self.set(px, qy, rgb(0xe5d8b8));
            }
        }
        // a ball field in the east
        let (bx, by) = (x1 / S - 30, (H - y0) / S - 24);
        for ay in by - 10..=by + 10 {
            for ax in bx - 10..=bx + 10 {
                let d = (ax - bx).abs() + (ay - by).abs();
                if d <= 10 {
                    self.set(ax, ay, if d >= 8 { rgb(0xc98f5a) } else { GRASS_LIGHT });
                }
            }
        }
        self.reserve_art(lx - 42, ly - 16, lx + 42, ly + 16);
        self.reserve_art(fx - 13, fy - 9, fx + 13, fy + 9);
        self.reserve_art(bx - 11, by - 11, bx + 11, by + 11);
        self.forest(x0, y0, x1, y1, 0.0, 900);
    }

    /// Times Square: billboards everywhere, the red TKTS steps, a crowd
    fn times_square(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        let (ax0, ax1, ay0, ay1) = (x0 / S, x1 / S, (H - y1) / S, (H - y0) / S);
        for ay in ay0..=ay1 {
            for ax in ax0..=ax1 {
                if self.free_art(ax, ay) {
                    self.set(ax, ay, if (ax + ay) % 7 == 0 { rgb(0xa79f92) } else { rgb(0xc4bcae) });
                }
            }
        }
        // buildings around the square, every one covered in screens
        let mut ay = ay0 + 2;
        while ay < ay1 - 20 {
            for side in 0..2 {
                let ax = if side == 0 { ax0 + 4 } else { ax1 - 25 };
                if !self.fits(ax, ay, 20, 24) {
                    continue;
                }
                self.tower(ax, ay, 20, 24, rgb(0x8f98a6), false, false);
                for b in 0..3 {
                    let c = self.pick(&NEON);
                    let by = ay + 6 + b * 6;
                    for y in 0..5 {
                        for x in 2..18 {
                            let col = if y == 0 || y == 4 { OUTLINE } else if (x + y) % 5 == 0 { WHITE } else { c };
                            self.set(ax + x, by + y, col);
                        }
                    }
                }
            }
            ay += 28;
        }
        // the red steps in the middle and the crowd
        let (sx, sy) = ((ax0 + ax1) / 2, (ay0 + ay1) / 2);
        for y in 0..12 {
            for x in -7..=7 {
                self.set(sx + x, sy + y, if y % 2 == 0 { rgb(0xd8342c) } else { rgb(0xa8241f) });
            }
        }
        self.outline(sx - 7, sy, 15, 12);
        for _ in 0..140 {
            let px = ax0 + 4 + self.rnd.next_int((ax1 - ax0 - 8).max(1));
            let py = ay0 + self.rnd.next_int((ay1 - ay0).max(1));
            if self.free_art(px, py) && !self.is_tree_or_building(px, py) && self.get(px, py) != OUTLINE {
                let c = self.pick(&NEON);
                self.set(px, py, c);
                self.set(px, py + 1, rgb(0x3a3a48));
            }
        }
        // a hot dog cart
        self.set(sx + 11, sy + 4, rgb(0xd9d9d9));
        self.set(sx + 12, sy + 4, rgb(0xf2c84b));
        self.set(sx + 11, sy + 3, rgb(0xd9433b));
        self.set(sx + 12, sy + 3, rgb(0x3b6fd9));
    }

    /// the Empire State Building: stepped tower and spire rising up the picture
    fn empire_state(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        let mut top = base;
        for (width, h) in [(26i32, 34i32), (18, 22), (12, 16), (6, 8)] {
            let half = width / 2;
            for dy in 0i32..h {
                for dx in -half..=half {
                    let edge = dx.abs() == half || dy == h - 1;
                    let mut c = if edge {
                        OUTLINE
                    } else if dx == -half + 1 {
                        rgb(0xe8e2d4)
                    } else if (dx + 64) % 3 == 0 {
                        rgb(0x8f8a80)
                    } else if dy % 3 == 0 {
                        rgb(0xb7b0a2)
                    } else {
                        rgb(0xcfc8b8)
                    };
                    if !edge && self.rnd.next_int(18) == 0 {
                        c = LIT;
                    }
                    self.set(ax + dx, top - dy, c);
                }
            }
            top -= h;
        }
        for dy in 0i32..14 {
            self.set(ax, top - dy, if dy < 4 { rgb(0xcfc8b8) } else { rgb(0x9aa0a8) }); // the spire
        }
        for dx in -14i32..=14 {
            self.shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    /// the Flatiron: a wedge-shaped building
    fn flatiron(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S, (H - y) / S);
        for dy in 0i32..22 {
            let w = 2 + dy * 12 / 22;
            for dx in 0i32..=w {
                let c = if dx == 0 || dx == w || dy == 21 {
                    OUTLINE
                } else if dy % 3 == 0 {
                    rgb(0xbfae8f)
                } else {
                    rgb(0xdccaa6)
                };
                self.set(ax + dx, ay + dy, c);
            }
        }
    }

    /// the Statue of Liberty on her island in the harbor
    fn liberty(&mut self, x: i32, y: i32) {
        let (ax, ay) = (x / S, (H - y) / S);
        for dy in -9i32..=9 {
            for dx in -16i32..=16 {
                let d = (dx * dx) as f64 / 256.0 + (dy * dy) as f64 / 81.0;
                if d < 1.0 {
                    let c = if d > 0.8 {
                        SHORE
                    } else if (dx + dy) % 5 == 0 {
                        GRASS_DARK
                    } else {
                        GRASS
                    };
                    self.set(ax + dx, ay + dy, c);
                }
            }
        }
        // the star-shaped fort
        for dy in -5i32..=5 {
            for dx in -5i32..=5 {
                let d = dx.abs() + dy.abs();
                if d <= 6 {
                    self.set(ax + dx, ay + dy, if d == 6 { OUTLINE } else { rgb(0xc9bfa9) });
                }
            }
        }
        // the pedestal, then the statue in green copper raising her torch
        for dy in -9i32..=0 {
            for dx in -3i32..=3 {
                let c = if dx.abs() == 3 || dy == -9 {
                    OUTLINE
                } else if dx < 0 {
                    rgb(0xd8ceb6)
                } else {
                    rgb(0xb9ae96)
                };
                self.set(ax + dx, ay + dy, c);
            }
        }
        let (green, dark) = (rgb(0x6fb59a), rgb(0x4f8f78));
        for dy in -21i32..=-10 {
            let half = if dy < -18 { 1 } else { 2 };
            for dx in -half..=half {
                self.set(ax + dx, ay + dy, if dx > 0 { dark } else { green });
            }
        }
        for dy in -26i32..=-19 {
            self.set(ax + 2, ay + dy, green); // her raised arm
        }
        self.set(ax + 2, ay - 27, rgb(0xffd23f)); // the torch flame
        self.set(ax + 2, ay - 28, rgb(0xff8a3f));
        self.set(ax + 1, ay - 27, OUTLINE);
        self.set(ax + 3, ay - 27, OUTLINE);
        self.set(ax - 2, ay - 15, rgb(0x5f9f88)); // the tablet
        self.set(ax - 3, ay - 15, rgb(0x5f9f88));
        for dx in -2i32..=2 {
            self.set(ax + dx, ay - 23, green); // the crown's spikes
        }
        self.set(ax - 1, ay - 22, green);
        self.set(ax, ay - 22, green);
        self.set(ax + 1, ay - 22, green);
        for dx in -4i32..=4 {
            self.shade(ax + dx + 3, ay + 1, 0.6);
        }
    }

    // ================================================================== Chicago landmarks

    /// Willis Tower: black bundled tubes stepping up, two white antennas on top
    fn willis_tower(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for t in [[-12, -4, 40], [-4, 4, 70], [4, 12, 52], [-12, -4, 56], [4, 12, 34]] {
            for dy in 0i32..t[2] {
                for dx in t[0]..=t[1] {
                    let edge = dx == t[0] || dx == t[1] || dy == t[2] - 1;
                    let mut c = if edge {
                        OUTLINE
                    } else if dx == t[0] + 1 {
                        rgb(0x5a5d66)
                    } else if dy % 3 == 0 {
                        rgb(0x26282e)
                    } else {
                        rgb(0x34373f)
                    };
                    if !edge && self.rnd.next_int(16) == 0 {
                        c = LIT;
                    }
                    self.set(ax + dx, base - dy, c);
                }
            }
        }
        for dy in 70i32..84 {
            self.set(ax - 2, base - dy, WHITE);
            self.set(ax + 2, base - dy, WHITE);
        }
        for dx in -14i32..=14 {
            self.shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    /// Millennium Park: lawns, the Bean (Cloud Gate) and Buckingham Fountain
    fn millennium_park(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        self.forest(x0, y0, x1, y1, 0.0, 1800);
        let (bx, by) = (1300 / S, (H - 440) / S);
        for ay in by - 9..=by + 9 {
            for ax in bx - 14..=bx + 14 {
                self.set(ax, ay, if (ax + ay) % 6 == 0 { rgb(0xc9c1b1) } else { rgb(0xdcd4c4) }); // the plaza
            }
        }
        for ay in by - 5..=by + 4 {
            for ax in bx - 9..=bx + 9 {
                let d = sq((ax - bx) as f64 / 9.0) + sq((ay - by) as f64 / 5.0);
                if d < 1.0 {
                    // shiny steel, the sky reflected
                    let c = if d > 0.8 {
                        OUTLINE
                    } else if ay < by - 2 && ax < bx {
                        WHITE
                    } else if ay > by + 1 {
                        rgb(0x8f97a3)
                    } else if (ax + ay) % 5 == 0 {
                        rgb(0x7fc0ff)
                    } else {
                        rgb(0xc3cbd6)
                    };
                    self.set(ax, ay, c);
                }
            }
        }
        // Buckingham Fountain: pink stone basins in a pool
        let (fx, fy) = (1300 / S, (H - 300) / S);
        let wc = self.water_color;
        self.disc(fx, fy, 9, rgb(0xd9c3b5));
        self.disc(fx, fy, 8, wc);
        self.disc(fx, fy, 5, rgb(0xe8b9a8));
        self.disc(fx, fy, 4, wc);
        self.disc(fx, fy, 2, rgb(0xe8b9a8));
        self.set(fx, fy - 3, SPARKLE);
        self.set(fx, fy - 4, WHITE);
    }

    /// Navy Pier sticking out into the lake, with its Ferris wheel
    fn navy_pier(&mut self, x: i32, y: i32) {
        let (ax0, ay) = (x / S, (H - y) / S);
        for ax in ax0..AW {
            for k in -6i32..=6 {
                if self.is_water(ax * S, H - (ay + k) * S) {
                    let c = if k.abs() == 6 { OUTLINE } else if k.abs() == 5 { rgb(0x8a6a4a) } else { rgb(0xd8ceb6) };
                    self.set(ax, ay + k, c);
                }
            }
        }
        let (cx, cy) = (ax0 + 30, ay - 10);
        let mut a = 0;
        while a < 360 {
            let r = (a as f64).to_radians();
            self.set(cx + round(11.0 * cos(r)), cy + round(11.0 * sin(r)), OUTLINE);
            if a % 30 == 0 {
                for k in 0i32..11 {
                    self.set(cx + round(k as f64 * cos(r)), cy + round(k as f64 * sin(r)), rgb(0xd9433b));
                }
                self.set(cx + round(12.0 * cos(r)), cy + round(12.0 * sin(r)), NEON[((a / 30) % NEON.len() as i32) as usize]);
            }
            a += 4;
        }
        for k in 0i32..12 {
            self.set(cx - k / 2, cy + k, OUTLINE);
            self.set(cx + k / 2, cy + k, OUTLINE);
        }
    }

    // ================================================================== London landmarks

    fn parliament(&mut self, x: i32, y: i32) {
        let (ax0, base) = (x / S, (H - y) / S);
        for dx in 0i32..170 {
            let h = 16 + if dx % 12 < 2 { 4 } else { 0 };
            for dy in 0i32..h {
                let edge = dy == h - 1 || dx == 0 || dx == 169;
                let mut c = if edge {
                    OUTLINE
                } else if dy % 4 == 0 {
                    rgb(0xb79f6c)
                } else if dx % 3 == 0 {
                    rgb(0xbfa874)
                } else {
                    rgb(0xd8c48e)
                };
                if !edge && dy > 3 && dy < 12 && dx % 3 == 1 {
                    c = GLASS_DARK;
                }
                self.set(ax0 + dx, base - dy, c);
            }
        }
        for dx in 0i32..170 {
            self.shade(ax0 + dx + 1, base + 1, 0.65);
        }
        // Victoria Tower at the far end
        for dy in 0i32..34 {
            for dx in 156i32..168 {
                let edge = dx == 156 || dx == 167 || dy == 33;
                let c = if edge { OUTLINE } else if dx == 157 { rgb(0xe8d8a8) } else { rgb(0xc9b27c) };
                self.set(ax0 + dx, base - dy, c);
            }
        }
    }

    /// Big Ben: the clock tower, with its clock face and spire
    fn big_ben(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for dy in 0i32..56 {
            for dx in -5i32..=5 {
                let edge = dx.abs() == 5;
                let c = if edge {
                    OUTLINE
                } else if dx < -2 {
                    rgb(0xe9d9a6)
                } else if dy % 4 == 0 {
                    rgb(0xb79f6c)
                } else {
                    rgb(0xd2bd86)
                };
                self.set(ax + dx, base - dy, c);
            }
        }
        self.disc(ax, base - 42, 4, OUTLINE);
        self.disc(ax, base - 42, 3, WHITE);
        self.set(ax, base - 42, OUTLINE);
        self.set(ax, base - 43, OUTLINE);
        self.set(ax + 1, base - 42, OUTLINE);
        for dy in 56i32..70 {
            let half = (5 - (dy - 56) / 2).max(0);
            for dx in -half..=half {
                self.set(ax + dx, base - dy, if dx.abs() == half { OUTLINE } else { rgb(0x4a5a5a) });
            }
        }
        self.set(ax, base - 71, rgb(0xd8b84a));
    }

    fn buckingham(&mut self, x: i32, y: i32) {
        let (ax0, ay0) = (x / S - 30, (H - y) / S);
        for dy in 0i32..16 {
            for dx in 0i32..60 {
                let edge = dy == 0 || dy == 15 || dx == 0 || dx == 59;
                let c = if edge {
                    OUTLINE
                } else if dy < 4 {
                    rgb(0xa8a296)
                } else if dx % 4 == 1 && dy > 6 && dy < 12 {
                    GLASS_DARK
                } else {
                    rgb(0xe8e2d0)
                };
                self.set(ax0 + dx, ay0 + dy, c);
            }
        }
        // the flag, the gates and the Victoria Memorial in gold
        self.set(ax0 + 30, ay0 - 3, OUTLINE);
        self.set(ax0 + 30, ay0 - 2, OUTLINE);
        self.set(ax0 + 31, ay0 - 3, rgb(0xd9433b));
        self.set(ax0 + 32, ay0 - 3, rgb(0x3b4fbf));
        let mut dx = 0;
        while dx < 60 {
            self.set(ax0 + dx, ay0 + 19, rgb(0x2a2a30));
            self.set(ax0 + dx, ay0 + 18, rgb(0xd8b84a));
            dx += 2;
        }
        self.disc(ax0 + 30, ay0 + 28, 5, rgb(0xe8e2d0));
        self.disc(ax0 + 30, ay0 + 28, 2, rgb(0xd8b84a));
        // the guards in red with bearskins
        for g in 0..4 {
            self.set(ax0 + 14 + g * 10, ay0 + 16, OUTLINE);
            self.set(ax0 + 14 + g * 10, ay0 + 17, rgb(0xd9433b));
        }
    }

    fn london_eye(&mut self, x: i32, y: i32) {
        let (cx, cy) = (x / S, (H - y) / S);
        let mut a = 0;
        while a < 360 {
            let r = (a as f64).to_radians();
            self.set(cx + round(22.0 * cos(r)), cy + round(22.0 * sin(r)), rgb(0xd9dde3));
            self.set(cx + round(21.0 * cos(r)), cy + round(21.0 * sin(r)), OUTLINE);
            if a % 20 == 0 {
                let mut k = 0;
                while k < 21 {
                    self.set(cx + round(k as f64 * cos(r)), cy + round(k as f64 * sin(r)), rgb(0xb7bec8));
                    k += 2;
                }
                // a pod
                let (px, py) = (cx + round(23.0 * cos(r)), cy + round(23.0 * sin(r)));
                self.set(px, py, GLASS);
                self.set(px + 1, py, GLASS_DARK);
            }
            a += 2;
        }
        self.disc(cx, cy, 2, OUTLINE);
        for k in 0i32..24 {
            self.set(cx - k / 3, cy + k, OUTLINE); // the A-frame leg
        }
    }

    fn st_pauls(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for dy in 0i32..14 {
            for dx in -14i32..=14 {
                let c = if dx.abs() == 14 || dy == 13 {
                    OUTLINE
                } else if dx % 3 == 0 {
                    rgb(0xc9c3b6)
                } else {
                    rgb(0xe3ded2)
                };
                self.set(ax + dx, base - dy, c);
            }
        }
        for dy in 0i32..10 {
            let half = round(((81 - dy * dy).max(0) as f64).sqrt());
            for dx in -half..=half {
                let c = if dx.abs() == half {
                    OUTLINE
                } else if dx < -2 {
                    rgb(0xb7bfc0)
                } else {
                    rgb(0x8d989a)
                };
                self.set(ax + dx, base - 14 - dy, c);
            }
        }
        self.set(ax, base - 25, rgb(0xd8b84a));
        self.set(ax, base - 26, rgb(0xd8b84a));
    }

    fn gherkin(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for dy in 0i32..40 {
            let half = round(7.0 * sin(PI * (1.0f64).min((dy + 6) as f64 / 46.0)));
            for dx in -half..=half {
                let edge = dx.abs() == half;
                let c = if edge {
                    OUTLINE
                } else if (dx + dy) % 4 == 0 {
                    rgb(0x2f5a6a)
                } else if dx < 0 {
                    rgb(0x6fa3b8)
                } else {
                    rgb(0x4f8197)
                };
                self.set(ax + dx, base - dy, c);
            }
        }
    }

    /// Tower Bridge on the vertical crossing: two stone towers with blue walkways between
    fn tower_bridge(&mut self, x: i32, _y: i32) {
        let ax = x / S;
        let (bank_low, bank_high) = ((H - 640) / S, (H - 745) / S);
        let reach = (ROAD + WALK) / S;
        for ty in [bank_low - 4, bank_high + 4] {
            for dy in -7i32..=7 {
                for dx in -reach - 4..=reach + 4 {
                    let over_road = dx.abs() < (ROAD - 6) / S && dy.abs() < 4;
                    if over_road {
                        continue; // the archway the road runs through
                    }
                    let edge = dy.abs() == 7 || dx.abs() == reach + 4;
                    let c = if edge {
                        OUTLINE
                    } else if dy < -4 {
                        rgb(0x4f7fbf)
                    } else if dx < 0 {
                        rgb(0xd6c6a3)
                    } else {
                        rgb(0xb9a481)
                    };
                    self.set(ax + dx, ty + dy, c);
                }
            }
            // the turrets at the corners
            for side in [-1, 1] {
                let tx = ax + side * (reach + 3);
                self.set(tx, ty - 8, rgb(0x3f6fae));
                self.set(tx, ty - 9, OUTLINE);
            }
        }
        // the high walkways, pale blue, on both sides
        for ay in bank_high + 4..=bank_low - 4 {
            for side in [-1, 1] {
                self.set(ax + side * (reach + 2), ay, rgb(0x7fb2e0));
                self.set(ax + side * (reach + 3), ay, OUTLINE);
            }
        }
    }

    /// red telephone boxes along the fence lines
    fn phone_boxes(&mut self) {
        for _ in 0..40 {
            let ax = self.rnd.next_int(AW);
            let ay = 4 + self.rnd.next_int(AH - 8);
            let (x, y) = (ax * S + 1, H - ay * S - 1);
            let d = self.road_distance(x, y);
            if d > ROAD + WALK + S + 3 && d < ROAD + WALK + 3 * S + 4 && self.free_art(ax, ay) && !self.is_tree_or_building(ax, ay) {
                for dy in -4i32..=0 {
                    for dx in 0i32..3 {
                        let c = if dy == -4 {
                            rgb(0xa8241f)
                        } else if dx == 1 && dy > -4 && dy < -1 {
                            GLASS
                        } else {
                            rgb(0xd8342c)
                        };
                        self.set(ax + dx, ay + dy, c);
                    }
                }
                self.outline(ax, ay - 4, 3, 5);
            }
        }
    }

    // ================================================================== Paris landmarks

    /// the Eiffel Tower: four arched legs, three levels, rising to a needle
    fn eiffel(&mut self, x: i32, y: i32) {
        let (ax, base, height) = (x / S, (H - y) / S, 74);
        let (iron, iron_light) = (rgb(0x7a6550), rgb(0xa58b6e));
        for dy in 0i32..height {
            let t = dy as f64 / height as f64;
            let half = round(20.0 * (1.0 - t).powf(2.2)) + 1;
            for dx in -half..=half {
                let edge = dx.abs() == half;
                let lattice = (dx + dy) % 3 == 0 || (dx - dy) % 3 == 0;
                let arch_hole = dy < 10
                    && dx.abs() < half - 3
                    && (dx * dx) as f64 / ((half - 3) * (half - 3) + 1) as f64 + dy as f64 / 10.0 < 1.0;
                if arch_hole {
                    continue;
                }
                if edge || lattice || half < 4 {
                    self.set(ax + dx, base - dy, if edge { OUTLINE } else if dx < 0 { iron_light } else { iron });
                }
            }
            if dy == 12 || dy == 34 || dy == 56 {
                for dx in -half - 2..=half + 2 {
                    self.set(ax + dx, base - dy, OUTLINE);
                    self.set(ax + dx, base - dy - 1, iron_light);
                    self.set(ax + dx, base - dy - 2, OUTLINE);
                }
            }
        }
        for dy in height..height + 8 {
            self.set(ax, base - dy, iron);
        }
        for dx in -22i32..=22 {
            self.shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    /// the Champ de Mars: long lawns and paths stretching out from the tower
    fn champ_de_mars(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        for ay in (H - y1) / S..=(H - y0) / S {
            for ax in x0 / S..=x1 / S {
                if !self.open_art(ax, ay) {
                    continue;
                }
                let path = (ay - (H - y1) / S) % 14 < 2;
                let c = if path {
                    rgb(0xe5d8b8)
                } else if (ax / 3 + ay / 3) % 2 == 0 {
                    rgb(0x7fc25e)
                } else {
                    rgb(0x74b856)
                };
                self.set(ax, ay, c);
            }
        }
        let mut x = x0 + 20;
        while x < x1 {
            self.tree(x / S, (H - y1) / S + 6, false);
            self.tree(x / S, (H - y0) / S - 2, false);
            x += 46;
        }
    }

    fn arc_de_triomphe(&mut self, x: i32, y: i32) {
        let (cx, cy) = (x / S, (H - y) / S);
        // the Place de l'Etoile: a round plaza with avenues like a star
        let mut a = 0;
        while a < 360 {
            let r = (a as f64).to_radians();
            for k in 0i32..40 {
                for w in -1..=1 {
                    let px = cx + round(k as f64 * cos(r) - w as f64 * sin(r));
                    let py = cy + round(k as f64 * sin(r) + w as f64 * cos(r));
                    if self.open_art(px, py) {
                        self.set(px, py, rgb(0xcfc7b7));
                    }
                }
            }
            a += 30;
        }
        self.disc(cx, cy, 16, rgb(0xcfc7b7));
        self.disc(cx, cy, 13, rgb(0xb9b2a4));
        // the arch itself
        for dy in -10i32..=10 {
            for dx in -10i32..=10 {
                let edge = dx.abs() == 10 || dy == -10 || dy == 10;
                let hole = dx.abs() < 4 && dy > -2;
                let c = if edge {
                    OUTLINE
                } else if hole {
                    rgb(0x5a5246)
                } else if dy < -6 {
                    rgb(0xf3ead6)
                } else if dx < -6 {
                    rgb(0xf1e7cf)
                } else {
                    rgb(0xdccfb1)
                };
                self.set(cx + dx, cy + dy, c);
            }
        }
        self.set(cx, cy - 12, rgb(0x3b4fbf));
        self.set(cx + 1, cy - 12, WHITE);
        self.set(cx + 2, cy - 12, rgb(0xd9433b));
    }

    fn louvre(&mut self, x: i32, y: i32) {
        let (ax0, ay0) = (x / S - 30, (H - y) / S - 10);
        for dy in 0i32..30 {
            for dx in 0i32..64 {
                let wing = dy < 8 || !(8..56).contains(&dx);
                if !wing {
                    self.set(ax0 + dx, ay0 + dy, if (dx + dy) % 6 == 0 { rgb(0xcfc7b7) } else { rgb(0xe0d8c8) }); // the courtyard
                    continue;
                }
                let edge = dy == 0
                    || dx == 0
                    || dx == 63
                    || (dy == 7 && (8..56).contains(&dx))
                    || (dx == 7 && dy >= 8)
                    || (dx == 56 && dy >= 8)
                    || dy == 29;
                let c = if edge {
                    OUTLINE
                } else if dy < 3 || (dx < 8 && dx > 4) || (56..59).contains(&dx) {
                    rgb(0x6f7f93)
                } else if dx % 3 == 1 {
                    GLASS_DARK
                } else {
                    rgb(0xefe3c8)
                };
                self.set(ax0 + dx, ay0 + dy, c);
            }
        }
        // the glass pyramid
        let (px, py) = (ax0 + 32, ay0 + 20);
        for dy in 0i32..8 {
            for dx in -dy..=dy {
                let c = if dx.abs() == dy { OUTLINE } else if dx < 0 { rgb(0xc8e6f6) } else { GLASS };
                self.set(px + dx, py - 7 + dy, c);
            }
        }
    }

    fn notre_dame(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for dy in 0i32..20 {
            for dx in -18i32..=18 {
                let edge = dx.abs() == 18 || dy == 19;
                self.set(ax + dx, base - dy, if edge { OUTLINE } else if dy % 4 == 0 { rgb(0xbfb39a) } else { rgb(0xd8ceb6) });
            }
        }
        for side in [-1, 1] {
            for dy in 20i32..34 {
                for dx in 0i32..9 {
                    let tx = ax + if side < 0 { -18 + dx } else { 10 + dx };
                    let c = if dx == 0 || dx == 8 || dy == 33 {
                        OUTLINE
                    } else if dx % 3 == 1 && dy > 24 {
                        rgb(0x4a4136)
                    } else {
                        rgb(0xd8ceb6)
                    };
                    self.set(tx, base - dy, c);
                }
            }
        }
        self.disc(ax, base - 12, 4, OUTLINE);
        self.disc(ax, base - 12, 3, rgb(0x5f4fae)); // the rose window
        self.set(ax, base - 12, rgb(0xd9433b));
        for dy in 20i32..32 {
            self.set(ax, base - dy, rgb(0x4a5a5a)); // the spire
        }
    }

    fn sacre_coeur(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        // Montmartre hill
        for dy in -24i32..=20 {
            for dx in -50i32..=50 {
                let d = (dx * dx) as f64 / 2500.0 + (dy * dy) as f64 / 576.0;
                if d < 1.0 && self.open_art(ax + dx, base + dy) {
                    let c = if d > 0.85 {
                        GRASS_DARK
                    } else if (dx + dy) % 7 == 0 {
                        GRASS_LIGHT
                    } else {
                        rgb(0x84c463)
                    };
                    self.set(ax + dx, base + dy, c);
                }
            }
        }
        for _ in 0..20 {
            let tx = ax - 44 + self.rnd.next_int(88);
            let ty = base + 6 + self.rnd.next_int(10);
            self.tree(tx, ty, false);
        }
        // white domes
        for dy in 0i32..12 {
            for dx in -14i32..=14 {
                self.set(ax + dx, base - dy, if dx.abs() == 14 || dy == 11 { OUTLINE } else { rgb(0xf6f3ea) });
            }
        }
        for d in [[0, 9, 22], [-9, 5, 15], [9, 5, 15]] {
            for dy in 0i32..d[1] {
                let half = round(d[1] as f64 * (1.0 - sq(dy as f64 / d[1] as f64)).sqrt());
                for dx in -half..=half {
                    let c = if dx.abs() == half {
                        OUTLINE
                    } else if dx < 0 {
                        WHITE
                    } else {
                        rgb(0xe3ded2)
                    };
                    self.set(ax + d[0] + dx, base - 12 - dy - (d[2] - 15), c);
                }
            }
        }
        self.set(ax, base - 12 - 9 - 7 - 1, rgb(0xd8b84a));
    }

    // ================================================================== Taipei landmarks

    /// Taipei 101: eight flared green-glass sections stacked like bamboo, and a spire
    fn taipei101(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for dy in 0i32..16 {
            for dx in -9i32..=9 {
                let c = if dx.abs() == 9 || dy == 15 {
                    OUTLINE
                } else if dx < -6 {
                    rgb(0x9fc7bd)
                } else {
                    rgb(0x5f9a8e)
                };
                self.set(ax + dx, base - dy, c);
            }
        }
        let mut top = base - 16;
        for _ in 0..8 {
            for dy in 0i32..8 {
                let half = 5 + dy / 2; // each section flares out toward its top
                for dx in -half..=half {
                    let edge = dx.abs() == half || dy == 7;
                    let mut c = if edge {
                        OUTLINE
                    } else if dx < -half + 2 {
                        rgb(0xb7ddd2)
                    } else if dy % 2 == 0 {
                        rgb(0x4f8a7f)
                    } else {
                        rgb(0x6aa89a)
                    };
                    if !edge && self.rnd.next_int(10) == 0 {
                        c = LIT;
                    }
                    self.set(ax + dx, top - dy, c);
                }
            }
            top -= 8;
        }
        for dy in 0i32..6 {
            for dx in -3i32..=3 {
                self.set(ax + dx, top - dy, if dx.abs() == 3 { OUTLINE } else { rgb(0x6aa89a) });
            }
        }
        for dy in 6i32..18 {
            self.set(ax, top - dy, rgb(0xc9ccd2));
        }
        for dx in -12i32..=12 {
            self.shade(ax + dx + 3, base + 1, 0.6);
        }
    }

    /// Chiang Kai-shek Memorial Hall: white hall with a blue octagonal roof, plaza and gate
    fn cks_memorial(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for ay in base - 38..=base + 48 {
            for dx in -66i32..=66 {
                if self.open_art(ax + dx, ay) {
                    self.set(ax + dx, ay, if (dx + ay) % 8 == 0 { rgb(0xd4cdbf) } else { rgb(0xe6e0d4) });
                }
            }
        }
        // the steps and the white hall
        for dy in 0i32..14 {
            let half = 20 - dy / 2;
            for dx in -half..=half {
                let c = if dx.abs() == half {
                    OUTLINE
                } else if dy < 5 && dy % 2 == 0 {
                    rgb(0xd9d3c6)
                } else {
                    WHITE
                };
                self.set(ax + dx, base - dy, c);
            }
        }
        for dy in 14i32..24 {
            for dx in -11i32..=11 {
                let c = if dx.abs() == 11 {
                    OUTLINE
                } else if dx.abs() < 3 && dy < 20 {
                    rgb(0x4a4136)
                } else {
                    WHITE
                };
                self.set(ax + dx, base - dy, c);
            }
        }
        // the double blue roof
        for tier in 0..2 {
            let (y0, half) = (base - 24 - tier * 7, 15 - tier * 5);
            for dy in 0i32..6 {
                let h2 = half - dy;
                for dx in -h2..=h2 {
                    let c = if dx.abs() == h2 || dy == 5 {
                        OUTLINE
                    } else if dy % 2 == 0 {
                        rgb(0x2f5fb8)
                    } else {
                        rgb(0x3f72d0)
                    };
                    self.set(ax + dx, y0 - dy, c);
                }
            }
        }
        self.set(ax, base - 39, rgb(0xd8b84a));
        // the gate at the front of the plaza
        let gy = base + 40;
        for dx in -14i32..=14 {
            self.set(ax + dx, gy - 6, rgb(0x2f5fb8));
            self.set(ax + dx, gy - 7, OUTLINE);
        }
        for gx in [-12, -5, 4, 11] {
            for dy in -5i32..=0 {
                self.set(ax + gx, gy + dy, WHITE);
                self.set(ax + gx + 1, gy + dy, rgb(0xd9d3c6));
            }
        }
        for i in 0..6 {
            self.tree(ax - 60 + i * 24, base + 30, false);
        }
    }

    /// Longshan Temple: red and orange roofs with upturned eaves and dragons on the ridges
    fn longshan(&mut self, x: i32, y: i32) {
        let (ax, base) = (x / S, (H - y) / S);
        for ay in base - 36..=base + 16 {
            for dx in -50i32..=50 {
                if self.open_art(ax + dx, ay) {
                    self.set(ax + dx, ay, if (dx + ay) % 5 == 0 { rgb(0xc9b08a) } else { rgb(0xd8c29c) });
                }
            }
        }
        for hall in [[0, 0, 26], [-30, 6, 14], [30, 6, 14]] {
            let (hx, hy, half) = (ax + hall[0], base - hall[1], hall[2]);
            for dy in 0i32..9 {
                for dx in -half + 3..=half - 3 {
                    let c = if dx.abs() == half - 3 {
                        OUTLINE
                    } else if dx % 4 == 0 {
                        rgb(0xb8322a)
                    } else {
                        rgb(0xd8b48a)
                    };
                    self.set(hx + dx, hy - dy, c);
                }
            }
            for dy in 9i32..17 {
                let h2 = half - (dy - 9) / 2;
                for dx in -h2..=h2 {
                    let c = if dx.abs() == h2 || dy == 16 {
                        OUTLINE
                    } else if dy % 2 == 0 {
                        rgb(0xe0602e)
                    } else {
                        rgb(0xf08a3e)
                    };
                    self.set(hx + dx, hy - dy, c);
                }
            }
            self.set(hx - half - 1, hy - 10, rgb(0xe0602e)); // upturned eaves
            self.set(hx + half + 1, hy - 10, rgb(0xe0602e));
            let mut dx = -(half - 4);
            while dx <= half - 4 {
                // the colorful dragons on the ridge
                self.set(hx + dx, hy - 17, NEON[if (dx + 40) % 3 == 0 { 3 } else { 4 }]);
                dx += 2;
            }
        }
        // the incense burner
        self.set(ax, base + 6, rgb(0x8a6a4a));
        self.set(ax, base + 5, rgb(0xd8b84a));
        self.set(ax, base + 3, rgb(0xd9d9d9));
    }

    /// Shilin Night Market: rows of stalls with colored awnings, red lanterns and a crowd
    fn night_market(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        let (ax0, ax1, ay0, ay1) = (x0 / S, x1 / S, (H - y1) / S, (H - y0) / S);
        for ay in ay0..=ay1 {
            for ax in ax0..=ax1 {
                if self.open_art(ax, ay) {
                    self.set(ax, ay, if (ax + ay) % 6 == 0 { rgb(0x9a958c) } else { rgb(0xb3ada2) });
                }
            }
        }
        let mut ay = ay0 + 2;
        while ay < ay1 - 6 {
            let mut ax = ax0 + 2;
            while ax < ax1 - 8 {
                let c = self.pick(&AWNINGS);
                for dy in 0i32..6 {
                    for dx in 0i32..7 {
                        let col = if dy < 3 {
                            if dx % 2 == 0 { c } else { WHITE }
                        } else if dy == 3 {
                            blend(c, OUTLINE, 0.4)
                        } else if dx % 2 == 0 {
                            rgb(0xf2c84b) // food on the counter
                        } else {
                            rgb(0xd9a14a)
                        };
                        self.set(ax + dx, ay + dy, col);
                    }
                }
                self.outline(ax, ay, 7, 6);
                self.set(ax + 3, ay - 2, rgb(0xff5a4a)); // a lantern
                ax += 9;
            }
            ay += 11;
        }
        for _ in 0..120 {
            let px = ax0 + self.rnd.next_int((ax1 - ax0).max(1));
            let py = ay0 + self.rnd.next_int((ay1 - ay0).max(1));
            if self.get(px, py) == rgb(0xb3ada2) && self.get(px, py - 1) == rgb(0xb3ada2) {
                self.set(px, py - 1, rgb(0xf0c8a0));
                let c = self.pick(&NEON);
                self.set(px, py, c);
            }
        }
    }

    /// green mountains behind the city
    fn hills(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        for ay in (H - y1) / S..=(H - y0) / S {
            for ax in x0 / S..=x1 / S {
                let top = ((H - y1) / S + 6) as f64 + 8.0 * sin(ax as f64 / 13.0) + 4.0 * sin(ax as f64 / 5.0);
                if ay as f64 > top && self.open_art(ax, ay) {
                    let c = if (ay as f64) < top + 2.0 {
                        rgb(0x2f6a34)
                    } else if (ax + ay) % 5 == 0 {
                        rgb(0x3f8a42)
                    } else {
                        rgb(0x357a3a)
                    };
                    self.set(ax, ay, c);
                }
            }
        }
    }

    /// rows of scooters parked along the fence lines, Taipei-style
    fn scooters(&mut self) {
        let colors = [rgb(0xd9433b), WHITE, rgb(0x3b6fd9), rgb(0x2a2a30), rgb(0xf2c84b), rgb(0x8fdcc2)];
        for ay in 2..AH - 2 {
            let mut ax = 2;
            while ax < AW - 2 {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                let d = self.road_distance(x, y);
                if d > ROAD + WALK + S + 2
                    && d <= ROAD + WALK + S + 5
                    && self.free_art(ax, ay)
                    && !self.is_tree_or_building(ax, ay)
                    && (ax + ay) % 3 != 0
                    && self.rnd.next_int(3) > 0
                {
                    let c = self.pick(&colors);
                    self.set(ax, ay, c);
                    self.set(ax, ay + 1, BLACK);
                }
                ax += 2;
            }
        }
    }
}

// ================================================================== the maps

fn paint_tokyo() -> City {
    // the Sumida river runs across the top half; the route crosses it on three bridges
    let mut c = City::new(
        "tokyo",
        |x, y| {
            let (x, y) = (x as f64, y as f64);
            y > 720.0 + 8.0 * sin(x / 61.0) && y < 800.0 + 8.0 * sin(x / 61.0) || x > 1850.0 + 5.0 * sin(y / 29.0)
        },
        1964,
    );
    c.ground(rgb(0x7cc25a), rgb(0x67ad4b));
    // Shiba park with Tokyo Tower, a shrine in a cherry grove, a garden by the river
    c.park(1300, 280, 1690, 560, true, false);
    c.reserve(1300, 280, 1690, 560);
    c.park(270, 480, 460, 700, true, true);
    c.reserve(270, 480, 460, 700);
    c.park(0, 0, 130, 1080, false, true);
    c.park(0, 0, 450, 150, false, true);
    // the neighborhoods
    c.district(270, 300, 450, 470, "tokyo-houses");
    c.district(590, 300, 930, 350, "tokyo-houses");
    c.district(590, 490, 1130, 550, "tokyo-shops");
    c.district(780, 690, 1130, 710, "tokyo-shops");
    c.district(270, 810, 630, 1080, "tokyo-houses");
    c.district(1270, 600, 1690, 830, "tokyo-towers");
    c.district(1270, 960, 1840, 1080, "tokyo-shops");
    c.district(1130, 960, 1270, 1080, "tokyo-houses");
    c.train(600, 235, 925, rgb(0x6b4a32), rgb(0x3fae49)); // the Yamanote line
    c.reserve(590, 200, 935, 275);
    c.district(780, 820, 1130, 1080, "tokyo-towers");
    c.district(1070, 270, 1270, 550, "tokyo-towers");
    c.district(1070, 0, 1840, 130, "tokyo-shops");
    c.district(500, 0, 930, 160, "tokyo-houses");
    c.district(580, 820, 640, 1080, "tokyo-houses");
    c.roads(rgb(0xf2f0ea), true);
    // the Shibuya scramble: zebra stripes across the whole crossing, both diagonals too
    c.scramble(950, 620);
    c.crosswalks();
    c.bridges(rgb(0xd8423a), rgb(0xa52f2a), false);
    c.shrine(365, 590);
    c.tokyo_tower(1495, 300);
    c.boat(430, 760, rgb(0xb98a5a), true);
    c.boat(1000, 752, rgb(0xb98a5a), true);
    c.boat(1500, 765, rgb(0xb98a5a), true);
    c.people(220);
    c.start_line();
    c
}

fn paint_new_york() -> City {
    // the East River down the right side (two bridges), the harbor in the bottom left
    let mut c = City::new(
        "nyc",
        |x, y| {
            let (xf, yf) = (x as f64, y as f64);
            (xf > 1420.0 + 10.0 * sin(yf / 53.0) && xf < 1600.0 + 10.0 * sin(yf / 53.0))
                || (yf < 140.0 + 12.0 * sin(xf / 37.0) && x < 760)
        },
        1898,
    );
    c.ground(rgb(0x74b956), rgb(0x60a447));
    // Central Park: lawns, the lake with rowboats, Bethesda Fountain, paths
    c.central_park(640, 600, 1330, 830);
    c.reserve(640, 600, 1330, 830);
    c.times_square(940, 0, 1170, 450);
    c.reserve(940, 0, 1170, 450);
    // room for the Empire State Building
    c.reserve(470, 290, 650, 660);
    c.district(270, 300, 790, 630, "nyc-towers");
    c.district(270, 770, 490, 1080, "nyc-brownstones");
    c.district(640, 970, 1410, 1080, "nyc-brownstones");
    c.district(1620, 270, 1690, 830, "nyc-brownstones");
    c.district(1830, 0, 1920, 1080, "nyc-brownstones");
    c.district(1620, 970, 1830, 1080, "nyc-brownstones");
    c.district(1310, 270, 1400, 830, "nyc-towers");
    c.district(940, 590, 1170, 600, "nyc-towers");
    c.district(1610, 0, 1700, 130, "nyc-brownstones");
    c.district(0, 0, 130, 1080, "nyc-brownstones");
    c.district(770, 140, 930, 450, "nyc-towers");
    c.district(1250, 0, 1400, 130, "nyc-towers");
    c.district(130, 770, 270, 1080, "nyc-brownstones");
    // a plaza around the Empire State Building
    for ay in (H - 660) / S..=(H - 290) / S {
        for ax in 470 / S..=650 / S {
            if c.road_distance(ax * S + 1, H - 1 - ay * S) > ROAD + WALK + S {
                c.set(ax, ay, if ax % 8 == 0 || ay % 8 == 0 { PAVE_LINE } else { PAVE });
            }
        }
    }
    c.empire_state(560, 420);
    c.flatiron(1355, 320);
    c.roads(rgb(0xf6d24a), false);
    c.crosswalks();
    c.bridges(rgb(0xb9a481), rgb(0x8a7a5e), true);
    c.liberty(330, 60);
    c.boat(620, 70, rgb(0xf08a2e), false); // the Staten Island Ferry
    c.boat(1470, 600, rgb(0xd9433b), false);
    c.boat(1500, 330, rgb(0xe9e2d2), false);
    c.people(260);
    c.start_line();
    c
}

fn paint_chicago() -> City {
    // the Chicago River across the middle (two bridges), Lake Michigan down the right
    let mut c = City::new(
        "chicago",
        |x, y| {
            let (xf, yf) = (x as f64, y as f64);
            (yf > 548.0 + 3.0 * sin(xf / 41.0) && yf < 606.0 + 3.0 * sin(xf / 41.0) && x < 1640)
                || xf > 1680.0 + 8.0 * sin(yf / 47.0)
        },
        1871,
    );
    c.water_color = rgb(0x3f9a9c);
    c.water_dark = rgb(0x337f84);
    c.ground(rgb(0x76bb57), rgb(0x62a748));
    c.millennium_park(1190, 250, 1410, 520);
    c.reserve(1190, 250, 1410, 520);
    c.reserve(270, 300, 560, 530); // Willis Tower's plaza
    c.district(270, 300, 560, 530, "chi-towers");
    c.district(580, 290, 1030, 360, "chi-towers");
    c.district(270, 630, 830, 830, "chi-towers");
    c.district(970, 770, 1630, 1080, "chi-towers");
    c.district(270, 970, 960, 1080, "chi-houses");
    c.district(0, 0, 130, 1080, "chi-houses");
    c.district(0, 0, 1040, 160, "chi-houses");
    c.district(1170, 0, 1640, 90, "chi-houses");
    c.district(970, 630, 1430, 640, "chi-towers");
    c.district(1570, 0, 1640, 1080, "chi-towers");
    c.district(730, 160, 1010, 290, "chi-houses");
    c.train(980, 800, 1620, rgb(0x8a4a2a), rgb(0x3f6fbf)); // the L on its elevated tracks
    c.reserve(970, 780, 1630, 830);
    c.willis_tower(420, 320);
    c.roads(rgb(0xf6d24a), false);
    c.crosswalks();
    c.bridges(rgb(0xc0453a), rgb(0x7a2a24), false);
    c.navy_pier(1700, 820);
    c.boat(700, 575, rgb(0xe9e2d2), false); // an architecture tour boat
    c.boat(1780, 400, rgb(0xd9433b), false);
    c.people(240);
    c.start_line();
    c
}

fn paint_london() -> City {
    // the Thames through the middle: Westminster Bridge on the left, Tower Bridge on the right
    let mut c = City::new(
        "london",
        |x, y| {
            let (x, y) = (x as f64, y as f64);
            y > 630.0 + 10.0 * sin(x / 90.0) && y < 752.0 + 8.0 * sin(x / 70.0 + 1.0)
        },
        1666,
    );
    c.water_color = rgb(0x4f86a8);
    c.water_dark = rgb(0x416f8f);
    c.ground(rgb(0x72b755), rgb(0x5ea246));
    c.park(0, 0, 130, 1080, true, false); // Hyde Park
    c.park(830, 250, 1230, 620, true, false); // St James's Park
    c.forest(1070, 770, 1690, 930, 0.0, 1400); // the South Bank
    c.reserve(270, 500, 930, 625); // Parliament and Big Ben
    c.district(270, 300, 420, 400, "london-terraces");
    c.district(270, 940, 930, 1080, "london-terraces");
    c.district(1370, 250, 1690, 450, "london-city");
    c.district(1370, 0, 1920, 120, "london-terraces");
    c.district(700, 0, 1240, 110, "london-terraces");
    c.district(0, 0, 690, 160, "london-terraces");
    c.district(1840, 0, 1920, 1080, "london-terraces");
    c.district(1070, 1040, 1700, 1080, "london-terraces");
    c.district(560, 280, 690, 380, "london-terraces");
    c.district(1380, 120, 1830, 250, "london-terraces");
    c.parliament(360, 520);
    c.big_ben(330, 515);
    c.buckingham(1030, 400);
    c.reserve(830, 250, 1230, 620);
    c.london_eye(1380, 830);
    c.reserve(1300, 760, 1470, 930);
    c.st_pauls(1450, 380);
    c.gherkin(1610, 300);
    c.roads(rgb(0xf2f0ea), false);
    c.crosswalks();
    c.bridges(rgb(0x3f7f5a), rgb(0x2a5a40), false);
    c.tower_bridge(1760, 690);
    c.boat(800, 700, rgb(0xe9e2d2), false);
    c.boat(1400, 690, rgb(0xd9433b), false);
    c.phone_boxes();
    c.people(240);
    c.start_line();
    c
}

fn paint_paris() -> City {
    // the Seine across the middle, with stone bridges on both crossings
    let mut c = City::new(
        "paris",
        |x, y| {
            let (x, y) = (x as f64, y as f64);
            y > 512.0 + 5.0 * sin(x / 70.0) && y < 604.0 + 5.0 * sin(x / 70.0 + 2.0)
        },
        1889,
    );
    c.water_color = rgb(0x4d8db8);
    c.water_dark = rgb(0x3f769e);
    c.ground(rgb(0x7abb5c), rgb(0x66a74c));
    c.champ_de_mars(780, 620, 1420, 850);
    c.reserve(780, 620, 1420, 860);
    c.arc_de_triomphe(1430, 370);
    c.reserve(1300, 240, 1560, 500);
    c.louvre(830, 300);
    c.reserve(620, 250, 1040, 350);
    c.notre_dame(380, 400);
    c.reserve(310, 390, 450, 510);
    c.sacre_coeur(465, 900);
    c.reserve(300, 820, 630, 1000);
    c.district(1180, 250, 1690, 500, "paris-haussmann");
    c.district(270, 300, 490, 495, "paris-cafes");
    c.district(140, 790, 650, 1080, "paris-haussmann");
    c.district(0, 0, 130, 1080, "paris-haussmann");
    c.district(0, 0, 1030, 160, "paris-cafes");
    c.district(1170, 0, 1920, 110, "paris-haussmann");
    c.district(770, 1000, 1920, 1080, "paris-haussmann");
    c.district(1570, 770, 1690, 1080, "paris-haussmann");
    c.district(1830, 0, 1920, 1080, "paris-haussmann");
    c.district(620, 350, 1040, 360, "paris-cafes");
    c.eiffel(1100, 650);
    c.roads(rgb(0xf2f0ea), true);
    c.crosswalks();
    c.bridges(rgb(0xd8ceb6), rgb(0xa89c82), false);
    c.boat(800, 560, rgb(0xe9e2d2), false); // bateaux mouches
    c.boat(1450, 555, rgb(0xe9e2d2), false);
    c.people(260);
    c.start_line();
    c
}

fn paint_taipei() -> City {
    // the Keelung river across the left and up to the top, crossed twice
    let mut c = City::new(
        "taipei",
        |x, y| {
            let (xf, yf) = (x as f64, y as f64);
            (yf > 726.0 + 4.0 * sin(xf / 37.0) && yf < 788.0 + 4.0 * sin(xf / 37.0) && x < 1150)
                || (xf > 1080.0 + 4.0 * sin(yf / 29.0) && xf < 1150.0 + 4.0 * sin(yf / 29.0) && y > 726)
        },
        1949,
    );
    c.water_color = rgb(0x4a93b8);
    c.water_dark = rgb(0x3e7d9e);
    c.ground(rgb(0x6fb653), rgb(0x5aa044));
    c.hills(0, 960, 1060, 1080); // Yangmingshan behind the city
    c.park(0, 0, 130, 960, true, false);
    c.reserve(270, 300, 740, 650); // Chiang Kai-shek Memorial Hall
    c.reserve(1310, 250, 1690, 570); // Longshan Temple
    c.reserve(870, 250, 1170, 360); // Shilin Night Market
    c.taipei101(1540, 760);
    c.reserve(1480, 740, 1600, 1080); // Taipei 101
    c.district(1170, 960, 1920, 1080, "taipei-shops");
    c.district(1380, 720, 1700, 1080, "taipei-towers");
    c.district(0, 0, 1250, 160, "taipei-shops");
    c.district(860, 490, 1700, 570, "taipei-shops");
    c.district(1170, 720, 1240, 830, "taipei-shops");
    c.district(270, 820, 1010, 830, "taipei-shops");
    c.district(1830, 0, 1920, 1080, "taipei-shops");
    c.district(270, 800, 1000, 840, "taipei-houses");
    c.district(1250, 0, 1920, 110, "taipei-houses");
    c.district(760, 590, 1210, 710, "taipei-shops");
    c.district(290, 660, 760, 710, "taipei-houses");
    c.district(880, 170, 1160, 250, "taipei-shops");
    c.cks_memorial(500, 470);
    c.longshan(1500, 420);
    c.night_market(880, 260, 1160, 350);
    c.roads(rgb(0xf2f0ea), true);
    c.crosswalks();
    c.bridges(rgb(0xd8423a), rgb(0xa52f2a), false);
    c.scooters();
    c.people(300);
    c.start_line();
    c
}

// ================================================================== the CPU car sprites

type SpriteDef = (&'static str, [&'static str; 28], &'static str, Vec<u32>);

/// each city's CPU car: 28 rows of 14 letters, the front at the top ('.' = see-through)
fn sprites() -> Vec<SpriteDef> {
    vec![
        // Chicago police cruiser: white with blue stripes and a red/blue light bar
        (
            "cop",
            [
                "...kkkkkkkk...", "..kywwwwwwyk..", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.", ".kssssssssssk.",
                ".kwbbbbbbbbwk.", "kkwbbbbbbbbwkk", ".kwbbbbbbbbwk.", ".kwwwwwwwwwwk.", ".kwrrrrllllwk.",
                ".kwrrrrllllwk.", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.",
                ".kwwwwwwwwwwk.", ".kwbbbbbbbbwk.", ".kwbbbbbbbbwk.", ".kwwwwwwwwwwk.", ".kssssssssssk.",
                ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.", ".kwwwwwwwwwwk.",
                "..ktwwwwwwtk..", "...kkkkkkkk...", "..............",
            ],
            "kwbsrlyt",
            vec![rgb(0x1c1c22), rgb(0xf6f6f3), rgb(0x23262d), rgb(0x2f5fb8), rgb(0xe8322a), rgb(0x3b7bff), rgb(0xfff0b0), rgb(0xd23a3a)],
        ),
        // London double-decker bus: long and red, seen from above
        (
            "bus",
            [
                ".kkkkkkkkkkkk.", "kbbbbbbbbbbbbk", "krrrrrrrrrrrrk", "kwwwwwwwwwwwwk", "kwrrrrrrrrrrwk",
                "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrddrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk",
                "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrddrrrrwk", "kwrrrrrrrrrrwk",
                "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrddrrrrwk",
                "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwrrrrrrrrrrwk", "kwwwwwwwwwwwwk", "krrrrrrrrrrrrk",
                "kddrrrrrrrrddk", ".kkkkkkkkkkkk.", "..............",
            ],
            "krdwb",
            vec![rgb(0x1c1c22), rgb(0xd8342c), rgb(0xa8241f), rgb(0xe9e2d2), rgb(0x23262d)],
        ),
        // Citroen 2CV: rounded, pale blue with a rolled-back canvas roof
        (
            "2cv",
            [
                "....kkkkkk....", "..kkyppppykk..", ".kppppppppppp.", ".kppppppppppk.", ".kppppppppppk.",
                ".kpbbbbbbbbpk.", "kkpbbbbbbbbpkk", ".kpbbbbbbbbpk.", ".kppppppppppk.", ".kpccccccccpk.",
                ".kpcCcCcCcCpk.", ".kpccccccccpk.", ".kpcCcCcCcCpk.", ".kpccccccccpk.", ".kpcCcCcCcCpk.",
                ".kpccccccccpk.", ".kppppppppppk.", ".kpbbbbbbbbpk.", ".kppppppppppk.", ".kppppppppppk.",
                ".kppppppppppk.", "..kppppppppk..", "..krppppppprk.", "...kkkkkkkk...", "..............",
                "..............", "..............", "..............",
            ],
            "kpcCbyr",
            vec![rgb(0x1c1c22), rgb(0x9fc4e8), rgb(0x3a3f48), rgb(0x2a2e36), rgb(0x23262d), rgb(0xfff0b0), rgb(0xd23a3a)],
        ),
        // a Taipei scooter with its rider in a helmet
        (
            "moto",
            [
                "..............", "......kk......", ".....kyyk.....", ".....kmmk.....", "....kmmmmk....",
                "...akmmmmka...", "...akmmmmka...", "....kmmmmk....", "....kssssk....", "...kssssssk...",
                "...kshhhhsk...", "...khhhhhhk...", "...khhhhhhk...", "...kshhhhsk...", "...kssssssk...",
                "....kssssk....", "....kmmmmk....", "....kmmmmk....", "....kmmmmk....", "....kmmmmk....",
                ".....kmmk.....", ".....kwwk.....", ".....kwwk.....", "......kk......", "..............",
                "..............", "..............", "..............",
            ],
            "kmhsawy",
            vec![rgb(0x1c1c22), rgb(0xe8442e), rgb(0xf2c84b), rgb(0x3b6fd9), rgb(0xf0c8a0), rgb(0x2a2a30), rgb(0xfff0b0)],
        ),
        // a boxy little kei car, mint with a white roof
        (
            "kei",
            [
                "...kkkkkkkk...", "..kymmmmmmyk..", ".kmmmmmmmmmmk.", ".kmmmmmmmmmmk.", ".kmbbbbbbbbmk.",
                ".kmbbbbbbbbmk.", "kkmbbbbbbbbmkk", ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.",
                ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.",
                ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.", ".kmwwwwwwwwmk.", ".kmbbbbbbbbmk.", ".kmbbbbbbbbmk.",
                ".kmmmmmmmmmmk.", ".kmmmmmmmmmmk.", ".krmmmmmmmmrk.", "..kkkkkkkkkk..", "..............",
                "..............", "..............", "..............",
            ],
            "kmwbyr",
            vec![rgb(0x1c1c22), rgb(0x8fdcc2), rgb(0xf6f6f3), rgb(0x23262d), rgb(0xfff0b0), rgb(0xd23a3a)],
        ),
        // a New York cab: yellow, a roof light and a checker stripe
        (
            "cab",
            [
                "...kkkkkkkk...", "..kybbbbbbyk..", ".kbbbbbbbbbbk.", ".kbbbbbbbbbbk.", ".kbbbbbbbbbbk.",
                ".kggggggggggk.", "kkgwwwwwwwwgkk", ".kgwwwwwwwwgk.", ".kgwwwwwwwwgk.", ".kbbbbbbbbbbk.",
                ".kbbbbbbbbbbk.", ".kbbbllllbbbk.", ".kbbbllllbbbk.", ".kbbbbbbbbbbk.", ".kbbbbbbbbbbk.",
                ".kcbcbcbcbcbk.", ".kbcbcbcbcbck.", ".kbbbbbbbbbbk.", ".kgwwwwwwwwgk.", ".kgwwwwwwwwgk.",
                ".kggggggggggk.", ".kbbbbbbbbbbk.", ".kbbbbbbbbbbk.", ".kbbbbbbbbbbk.", ".kbbbbbbbbbbk.",
                "..krbbbbbbrk..", "...kkkkkkkk...", "..............",
            ],
            "kbwglcyr",
            vec![rgb(0x1c1c22), rgb(0xf7c51e), rgb(0x2a2d35), rgb(0xc99a12), rgb(0xfdfbf0), rgb(0x1c1c22), rgb(0xfff0b0), rgb(0xd23a3a)],
        ),
    ]
}

/// a car sprite from its rows of letters
pub fn sprite(rows: [&str; 28], letters: &str, colors: &[u32]) -> Picture {
    let mut img = Picture::new(14, 28);
    for (y, row) in rows.iter().enumerate() {
        assert_eq!(row.len(), 14, "sprite row {y} is {} wide: {row}", row.len());
        for (x, ch) in row.chars().enumerate() {
            img.set(x, y, letters.find(ch).map_or(0, |i| colors[i]));
        }
    }
    img
}
