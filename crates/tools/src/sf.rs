//! Paints the San Francisco map in the game's 16-bit pixel style, plus the Waymo car sprite,
//! and the classic map's preview for the map picker.
//!
//! The map is 1920 x 1080 like the classic one, painted at 1/3 size (640 x 360 art pixels) and
//! scaled up 3x so every art pixel is a crisp 3 x 3 block. Coordinates are world coordinates
//! (y up, like the game) unless they're called ax / ay (art pixels, y down).

use std::path::Path;

use crate::cities::{racing_line, sprite, AH, AW, H, ROAD, S, W, WALK};
use crate::image::{blend, rgb, Picture};
use crate::jrand::JRandom;
use crate::masks::read_rgba;

const ASPHALT: u32 = rgb(0x4b4d55);
const ASPHALT_SPECK: u32 = rgb(0x44464d);
const LANE: u32 = rgb(0xe9e4d6);
const CURB: u32 = rgb(0x7a766e);
const WALK_A: u32 = rgb(0xc9c2b4);
const WALK_B: u32 = rgb(0xbab2a3);
const WATER: u32 = rgb(0x2f6ea6);
const WAVE: u32 = rgb(0x4d8fc6);
const WAVE_DARK: u32 = rgb(0x275d8e);
const SAND: u32 = rgb(0xcdb98d);
const GRASS: u32 = rgb(0x6aa84f);
const GRASS_LIGHT: u32 = rgb(0x82bd5e);
const TREE: u32 = rgb(0x3b7537);
const TREE_LIGHT: u32 = rgb(0x58994a);
const TREE_DARK: u32 = rgb(0x2a5528);
const OUTLINE: u32 = rgb(0x2a1d17);
const ORANGE: u32 = rgb(0xc4402f);
const ORANGE_LIGHT: u32 = rgb(0xe0634c);
const ORANGE_DARK: u32 = rgb(0x8c2a20);
const DECK: u32 = rgb(0x8f8a83);
const ALLEY: u32 = rgb(0x6f6a62);
const BRICK: u32 = rgb(0xb5523b);
const WHITE: u32 = rgb(0xf2efe6);
const ROCK: u32 = rgb(0x8c7d6a);
const ROCK_DARK: u32 = rgb(0x6c5f50);
const ROOFS: [u32; 8] = [
    rgb(0xe8a0a8), rgb(0x9fd3c7), rgb(0xc3a6e0), rgb(0xf2d27a), rgb(0x8fb8e8), rgb(0xf0b07a), rgb(0xe6dccb), rgb(0xd98c7a),
];
const TOWERS: [u32; 5] = [rgb(0x9aa5b1), rgb(0x8796a6), rgb(0xb3b8bd), rgb(0x7f8a99), rgb(0xa9b4c4)];

struct Sf {
    path: Vec<[i32; 2]>,
    art: Vec<u32>,
    /// art pixels already taken by parks and landmarks, so houses go around them
    reserved: Vec<bool>,
    ignore_reserved: bool,
    rnd: JRandom,
}

pub fn paint(root: &Path) -> Result<(), String> {
    let mut sf = Sf {
        path: racing_line("sf"),
        art: vec![0; (AW * AH) as usize],
        reserved: vec![false; (AW * AH) as usize],
        ignore_reserved: false,
        rnd: JRandom::new(1906),
    };
    sf.ground();
    sf.city();
    sf.landmarks();
    sf.roads();
    sf.bridge();

    let dir = root.join("assets/maps/sf");
    let mut map = Picture::new(W as usize, H as usize);
    for y in 0..H {
        for x in 0..W {
            map.set(x as usize, y as usize, sf.art[((y / S) * AW + x / S) as usize]);
        }
    }
    map.save(dir.join("background.png"), true)?;
    let mut mask = Picture::new(W as usize, H as usize);
    for y in 0..H {
        for x in 0..W {
            mask.set(x as usize, y as usize, if sf.road_distance(x, H - y) <= ROAD { 0xffffffff } else { 0xff000000 });
        }
    }
    mask.save(dir.join("road_mask.png"), false)?;
    map.shrink(384, 216).save(dir.join("preview.png"), false)?;
    // the classic map's preview, from its hand-made picture
    let classic = read_rgba(&root.join("assets/maps/classic/background.png"))
        .or_else(|_| read_rgba(Path::new("assets/maps/classic/background.png")))?;
    let mut pic = Picture::new(classic.width, classic.height);
    for (i, px) in classic.pixels.chunks(4).enumerate() {
        pic.pixels[i] = (px[3] as u32) << 24 | (px[0] as u32) << 16 | (px[1] as u32) << 8 | px[2] as u32;
    }
    pic.shrink(384, 216).save(root.join("assets/maps/classic/preview.png"), false)?;
    waymo().save(root.join("assets/ui/waymo.png"), true)?;
    println!("San Francisco painted");
    Ok(())
}

impl Sf {
    fn set(&mut self, ax: i32, ay: i32, c: u32) {
        if ax >= 0 && ay >= 0 && ax < AW && ay < AH {
            self.art[(ay * AW + ax) as usize] = c;
        }
    }

    fn get(&self, ax: i32, ay: i32) -> u32 {
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

    fn frame_rect(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, c: u32) {
        for x in x0..=x1 {
            self.set(x, y0, c);
            self.set(x, y1, c);
        }
        for y in y0..=y1 {
            self.set(x0, y, c);
            self.set(x1, y, c);
        }
    }

    /// distance outside the straights' centre lines, measured as a box (square corners)
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

    /// the bay: across the top (under the bridge) and down the right edge
    fn water(x: i32, y: i32) -> bool {
        let (xf, yf) = (x as f64, y as f64);
        let bay = yf > 668.0 + 10.0 * (xf / 47.0).sin() + 6.0 * (xf / 13.0).sin()
            && xf > 380.0 + 14.0 * (yf / 31.0).sin()
            && xf < 1420.0 + 12.0 * (yf / 23.0).sin();
        let east = xf > 1846.0 + 6.0 * (yf / 37.0).sin();
        bay || east
    }

    fn near_water(x: i32, y: i32, d: i32) -> bool {
        Sf::water(x + d, y) || Sf::water(x - d, y) || Sf::water(x, y + d) || Sf::water(x, y - d)
    }

    /// true where nothing else should be painted: not road, sidewalk or water
    fn free(&self, x: i32, y: i32) -> bool {
        x >= 0
            && y >= 0
            && x < W
            && y < H
            && self.road_distance(x, y) > ROAD + WALK
            && !Sf::water(x, y)
            && !Sf::near_water(x, y, 6)
            && (self.ignore_reserved || !self.reserved[(((H - 1 - y) / S) * AW + (AW - 1).min(x / S)) as usize])
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

    fn ground(&mut self) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                let c = if Sf::water(x, y) {
                    // rows of little waves
                    let n = (ax * 7 + ay * 13 + (ay / 3) * 5) % 23;
                    if n == 0 {
                        WAVE
                    } else if n == 11 {
                        WAVE_DARK
                    } else {
                        WATER
                    }
                } else if Sf::near_water(x, y, 6) {
                    SAND
                } else if (ax + ay) % 9 == 0 {
                    GRASS_LIGHT
                } else {
                    GRASS
                };
                self.set(ax, ay, c);
            }
        }
        // the fog rolling in over the bay (dithered, the 16-bit way)
        self.fog(560, 1000, 160, 50);
        self.fog(820, 940, 110, 34);
        self.fog(1300, 1030, 140, 40);
    }

    fn fog(&mut self, cx: i32, cy: i32, rx: i32, ry: i32) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S, H - ay * S);
                let d = ((x - cx) as f64 / rx as f64).powi(2) + ((y - cy) as f64 / ry as f64).powi(2);
                if d < 1.0 && Sf::water(x, y) && ((ax + ay) % 2 == 0 || d < 0.35) {
                    let c = blend(self.get(ax, ay), WHITE, if d < 0.35 { 0.55 } else { 0.35 });
                    self.set(ax, ay, c);
                }
            }
        }
    }

    /// blocks of houses and towers everywhere that isn't road, sidewalk, water or a park
    fn city(&mut self) {
        // parks and landmark spots first so houses don't go there
        self.park(684, 0, 1036, 300); // Alamo Square
        self.park(0, 0, 136, 1080); // the Presidio along the left
        self.park(0, 0, 560, 160);
        self.park(1500, 470, 1676, 640); // Telegraph Hill (Coit Tower)
        self.reserve(1500, 470, 1676, 640);
        self.reserve(684, 0, 1036, 300);
        self.reserve(300, 590, 470, 810); // Lombard Street
        self.reserve(1380, 300, 1500, 440); // Transamerica
        // neighborhoods: small painted houses; downtown: big gray towers
        self.houses(264, 294, 556, 816, false);
        self.houses(556, 584, 1676, 816, false);
        self.houses(1164, 244, 1676, 600, true);
        self.houses(1036, 0, 1810, 116, false);
        self.houses(1420, 944, 1846, 1080, false);
        self.houses(684, 300, 1036, 456, false);
        self.houses(264, 944, 400, 1080, false);
    }

    fn park(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        let mut y = y0;
        while y < y1 {
            let mut x = x0;
            while x < x1 {
                if self.free(x, y) {
                    let (ax, ay) = (x / S, (H - y) / S);
                    self.set(ax, ay, if (ax * 3 + ay * 5) % 11 == 0 { GRASS_LIGHT } else { GRASS });
                }
                x += S;
            }
            y += S;
        }
        // trees, round with a lit top-left, scattered but not on top of each other
        for _ in 0..(x1 - x0) * (y1 - y0) / 2600 {
            let x = x0 + self.rnd.next_int((x1 - x0).max(1));
            let y = y0 + self.rnd.next_int((y1 - y0).max(1));
            if self.free(x, y) && self.free(x + 24, y) && self.free(x - 24, y) && self.free(x, y + 24) && self.free(x, y - 24) {
                let r = 3 + self.rnd.next_int(3);
                self.tree(x / S, (H - y) / S, r);
            }
        }
    }

    fn tree(&mut self, cx: i32, cy: i32, r: i32) {
        self.disc(cx + 1, cy + 1, r, TREE_DARK); // shadow
        self.disc(cx, cy, r, TREE);
        self.disc(cx - r / 3, cy - r / 3, (r / 2).max(1), TREE_LIGHT);
        self.set(cx + r / 3, cy - r / 2, TREE_LIGHT);
    }

    fn houses(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, downtown: bool) {
        // the ground between buildings is paved, not grass
        let mut y = y0;
        while y < y1 {
            let mut x = x0;
            while x < x1 {
                if self.free(x, y) {
                    let (ax, ay) = (x / S, (H - y) / S);
                    let c = if !downtown {
                        ALLEY
                    } else if ax % 6 == 0 || ay % 6 == 0 {
                        rgb(0xa9a296)
                    } else {
                        rgb(0xbdb6a8)
                    };
                    self.set(ax, ay, c);
                }
                x += S;
            }
            y += S;
        }
        if downtown {
            // towers of different sizes on a loose grid
            let mut by = y0;
            while by < y1 {
                let mut bx = x0;
                while bx < x1 - 30 {
                    let bw = 54 + 12 * self.rnd.next_int(4);
                    let color = TOWERS[self.rnd.next_int(TOWERS.len() as i32) as usize];
                    self.building(bx, by, bw.min(x1 - bx), 60.min(y1 - by), color, true);
                    bx += bw + 6;
                }
                by += 66;
            }
            return;
        }
        // city blocks: two rows of narrow, deep row houses back to back, little gardens between
        let (depth, garden, gap) = (33, 12, 6);
        let mut by = y0;
        while by < y1 - depth {
            for row in 0..2 {
                let ry = by + row * (depth + garden);
                if ry + depth > y1 {
                    break;
                }
                if row == 1 {
                    // back gardens behind the first row
                    let mut y = ry - garden;
                    while y < ry {
                        let mut x = x0;
                        while x < x1 {
                            if self.free(x, y) {
                                self.set(x / S, (H - y) / S, if (x / S + y / S) % 4 == 0 { GRASS_LIGHT } else { GRASS });
                            }
                            x += S;
                        }
                        y += S;
                    }
                }
                let mut x = x0;
                while x < x1 - 15 {
                    let w = 18 + 3 * self.rnd.next_int(4);
                    let color = ROOFS[self.rnd.next_int(ROOFS.len() as i32) as usize];
                    self.building(x, ry, w.min(x1 - x), depth, color, false);
                    x += w;
                }
            }
            by += depth * 2 + garden + gap;
        }
    }

    /// one roof seen from above: outline, lit and shaded halves, ridge, details; a shadow beside
    fn building(&mut self, x: i32, y: i32, w: i32, h: i32, color: u32, tower: bool) {
        let (ax0, ax1, ay0, ay1) = (x / S, (x + w) / S - 1, (H - (y + h)) / S, (H - y) / S - 1);
        if ax1 - ax0 < 2 || ay1 - ay0 < 2 {
            return;
        }
        // only where the whole building has room (never over sidewalks, road or water)
        for ay in ay0..=ay1 {
            for ax in ax0..=ax1 {
                if !self.free(ax * S + 1, H - ay * S - 1) {
                    return;
                }
            }
        }
        for ay in ay0..=ay1 + 1 {
            for ax in ax0 + 1..=ax1 + 1 {
                if ay > ay1 || ax > ax1 {
                    self.shade(ax, ay, 0.7); // shadow on the bottom right
                }
            }
        }
        let mid = (ax0 + ax1) / 2;
        for ay in ay0..=ay1 {
            for ax in ax0..=ax1 {
                let c = if ax == ax0 || ax == ax1 || ay == ay0 || ay == ay1 {
                    OUTLINE
                } else if !tower && ax == mid {
                    blend(color, OUTLINE, 0.4) // the ridge, running front to back
                } else if !tower && ay == ay0 + 1 {
                    blend(color, WHITE, 0.35) // the front edge (bay windows) catches the light
                } else if !tower {
                    if ax < mid { blend(color, WHITE, 0.16) } else { blend(color, OUTLINE, 0.14) }
                } else if ax == ax0 + 1 || ay == ay0 + 1 {
                    blend(color, WHITE, 0.25) // the lit edge of a flat roof
                } else {
                    color
                };
                self.set(ax, ay, c);
            }
        }
        if tower {
            // rooftop units
            for _ in 0..2 {
                let ux = ax0 + 2 + self.rnd.next_int((ax1 - ax0 - 4).max(1));
                let uy = ay0 + 2 + self.rnd.next_int((ay1 - ay0 - 4).max(1));
                self.set(ux, uy, rgb(0x5d636b));
                self.set(ux + 1, uy, rgb(0x5d636b));
            }
        } else if self.rnd.next_int(3) == 0 {
            self.set(mid + 1, ay1 - 2, OUTLINE); // a chimney
        }
    }

    fn landmarks(&mut self) {
        // Lombard Street: the crooked brick switchbacks between flower beds
        let (lx, ltop, lbottom) = (380 / S, (H - 790) / S, (H - 610) / S);
        for ay in ltop..=lbottom {
            for ax in lx - 13..=lx + 13 {
                let n = (ax * 7 + ay * 11) % 19;
                self.set(ax, ay, if n == 0 { rgb(0xe0607e) } else if n == 9 { rgb(0xf2c84b) } else { TREE_LIGHT });
            }
        }
        // the switchbacks: a brick zig-zag with a darker edge
        for ay in ltop..=lbottom {
            let t = (ay - ltop) % 20;
            let off = if t < 10 { -9 + t * 2 } else { 11 - (t - 10) * 2 };
            for k in -3i32..=3 {
                self.set(lx + off + k, ay, if k.abs() == 3 { rgb(0x7d3426) } else { BRICK });
            }
        }
        self.frame_rect(lx - 14, ltop - 1, lx + 14, lbottom + 1, OUTLINE);

        // the Painted Ladies facing Alamo Square
        let ladies = [rgb(0xf3b6c4), rgb(0x9fd3c7), rgb(0xf2d27a), rgb(0xc3a6e0), rgb(0x8fb8e8), rgb(0xf0b07a), rgb(0xe8a0a8)];
        self.ignore_reserved = true;
        for (i, &c) in ladies.iter().enumerate() {
            self.building(700 + i as i32 * 45, 252, 42, 45, c, false);
        }
        self.ignore_reserved = false;

        // the Transamerica Pyramid, seen from straight above
        self.pyramid(1440 / S, (H - 370) / S, 12);
        // Coit Tower on Telegraph Hill
        for i in 1..=2 {
            self.shade(1588 / S + 6 + i, (H - 555) / S + i, 0.7);
        }
        self.disc(1588 / S, (H - 555) / S, 6, OUTLINE);
        self.disc(1588 / S, (H - 555) / S, 5, WHITE);
        self.disc(1588 / S, (H - 555) / S, 2, rgb(0xcfc8b8));

        // the Ferry Building and its clock tower, out on the water at the Embarcadero
        let (fx0, fx1, fy0, fy1) = (1830 / S, 1890 / S, (H - 640) / S, (H - 380) / S);
        for ay in fy0 - 1..=fy1 + 1 {
            for ax in fx0 - 1..=fx1 + 1 {
                let edge = ax < fx0 || ax > fx1 || ay < fy0 || ay > fy1;
                self.set(ax, ay, if edge { OUTLINE } else if ax == fx0 { rgb(0xf1e6cf) } else { rgb(0xdccdb0) });
            }
        }
        let (tx, ty) = ((fx0 + fx1) / 2, (fy0 + fy1) / 2);
        for ay in ty - 4..=ty + 4 {
            for ax in tx - 4..=tx + 4 {
                let edge = (ax - tx).abs() == 4 || (ay - ty).abs() == 4;
                self.set(ax, ay, if edge { OUTLINE } else { rgb(0xc7b08a) });
            }
        }
        self.set(tx, ty, rgb(0x2f6f62));
        // piers along the Embarcadero
        for py in [120, 230, 760, 860, 980] {
            for ay in (H - py - 18) / S..=(H - py + 18) / S {
                for ax in 1852 / S..AW {
                    if Sf::water(ax * S, H - ay * S) {
                        let edge = ay == (H - py - 18) / S || ay == (H - py + 18) / S;
                        self.set(ax, ay, if edge { OUTLINE } else { rgb(0x8a6a4a) });
                    }
                }
            }
        }
        // Alcatraz, out in the bay
        let (ix, iy) = (1000 / S, (H - 1015) / S);
        for ay in iy - 9..=iy + 9 {
            for ax in ix - 22..=ix + 22 {
                let d = ((ax - ix) as f64 / 22.0).powi(2) + ((ay - iy) as f64 / 9.0).powi(2);
                if d < 1.0 {
                    self.set(ax, ay, if d > 0.75 { ROCK_DARK } else if (ax + ay) % 6 == 0 { GRASS } else { ROCK });
                }
            }
        }
        for ay in iy - 3..=iy + 2 {
            for ax in ix - 9..=ix + 7 {
                let edge = ay == iy - 3 || ay == iy + 2 || ax == ix - 9 || ax == ix + 7;
                self.set(ax, ay, if edge { OUTLINE } else { WHITE });
            }
        }
        self.set(ix + 12, iy - 4, OUTLINE);
        self.set(ix + 12, iy - 5, rgb(0xffe08a)); // the lighthouse
    }

    fn pyramid(&mut self, cx: i32, cy: i32, r: i32) {
        for ay in cy - r..=cy + r {
            for ax in cx - r..=cx + r {
                let (dx, dy) = (ax - cx, ay - cy);
                let c = if dx.abs() == r || dy.abs() == r {
                    OUTLINE
                } else if dx.abs() == dy.abs() {
                    rgb(0xb9b4aa) // the four ridges
                } else if dy.abs() > dx.abs() {
                    if dy < 0 { rgb(0xfbf8f0) } else { rgb(0xc9c3b6) }
                } else if dx < 0 {
                    rgb(0xeee9df)
                } else {
                    rgb(0xd8d2c5)
                };
                self.set(ax, ay, c);
            }
        }
        for i in 1..=3 {
            self.shade(cx + r + i, cy + i, 0.7);
        }
    }

    /// roads: asphalt with a few specks, a curb, the sidewalk, dashed centre lines, the start line
    fn roads(&mut self) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                let d = self.road_distance(x, y);
                let over_water = Sf::water(x, y) || Sf::near_water(x, y, 6);
                if d <= ROAD {
                    let c = if d > ROAD - S {
                        CURB
                    } else if (ax * 5 + ay * 3) % 17 == 0 {
                        ASPHALT_SPECK
                    } else {
                        ASPHALT
                    };
                    self.set(ax, ay, c);
                } else if d <= ROAD + WALK && !over_water {
                    self.set(ax, ay, if ax % 4 == 0 || ay % 4 == 0 { WALK_B } else { WALK_A }); // pavement squares
                } else if d <= ROAD + WALK + S && !over_water {
                    // a low curb with bollards along the back of the sidewalk: the edge of the track
                    self.set(ax, ay, if (ax + ay) % 4 == 0 { rgb(0xe8c547) } else { rgb(0x55514b) });
                }
            }
        }
        // a dashed lane line down the middle of every straight, stopping short of the corners
        for i in 0..self.path.len() - 1 {
            let (a, b) = (self.path[i], self.path[i + 1]);
            let len = (b[0] - a[0]).abs() + (b[1] - a[1]).abs();
            let mut t = ROAD + 12;
            while t < len - ROAD - 12 {
                let (x, y) = (a[0] + (b[0] - a[0]).signum() * t, a[1] + (b[1] - a[1]).signum() * t);
                if (t / 18) % 2 == 0 {
                    self.set(x / S, (H - y) / S, LANE);
                }
                t += S;
            }
        }
        // cable car tracks up California Street (the x = 1100 straight)
        let mut y = 180 + ROAD;
        while y < 520 - ROAD {
            for x in [1100 - 34, 1100 - 22, 1100 + 22, 1100 + 34] {
                self.set(x / S, (H - y) / S, rgb(0x34353a));
            }
            y += S;
        }
        // the checkered start line: y 300-324 across the road, like the classic map
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

    /// the Golden Gate: orange railings and towers where the road crosses the water
    fn bridge(&mut self) {
        for ay in 0..AH {
            for ax in 0..AW {
                let (x, y) = (ax * S + 1, H - ay * S - 1);
                if !(Sf::water(x, y) || Sf::near_water(x, y, 6)) || y < 700 {
                    continue;
                }
                let d = (y - 880).abs();
                if d > ROAD && d <= ROAD + WALK - S {
                    self.set(ax, ay, DECK); // the walkway
                } else if d > ROAD + WALK - S && d <= ROAD + WALK + S {
                    self.set(ax, ay, ORANGE); // the railing and main cable
                } else if d > ROAD + WALK + S && d <= ROAD + WALK + 3 * S && y < 880 {
                    self.set(ax, ay, WAVE_DARK); // the deck's shadow on the water
                }
            }
        }
        // suspender ropes as little dots along the railings
        let mut x = 400;
        while x < 1420 {
            self.set(x / S, (H - (880 + ROAD + WALK)) / S, ORANGE_LIGHT);
            self.set(x / S, (H - (880 - ROAD - WALK)) / S, ORANGE_LIGHT);
            x += 18;
        }
        // two towers: a leg each side of the deck and a beam across
        for tx in [640i32, 1160] {
            for side in [-1, 1] {
                let leg_y = 880 + side * (ROAD + WALK + 14);
                let mut y = leg_y - 14;
                while y <= leg_y + 14 {
                    let mut x = tx - 18;
                    while x <= tx + 18 {
                        let edge = (x - tx).abs() >= 15 || (y - leg_y).abs() >= 12;
                        self.set(x / S, (H - y) / S, if edge { ORANGE_DARK } else if x < tx { ORANGE_LIGHT } else { ORANGE });
                        x += S;
                    }
                    y += S;
                }
                for i in 1..=2 {
                    self.shade((tx + 18) / S + i, (H - (leg_y - 14)) / S + i, 0.65);
                }
            }
            let mut y = 880 - ROAD - WALK;
            while y <= 880 + ROAD + WALK {
                self.set((tx - 3) / S, (H - y) / S, ORANGE_DARK);
                self.set((tx + 3) / S, (H - y) / S, ORANGE);
                y += S;
            }
        }
    }
}

fn waymo() -> Picture {
    let rows = [
        "...kkkkkkkk...", "..kywwwwwwyk..", ".kwwwwwwwwwwk.", "skwwwwwwwwwwks", ".kwwwwwwwwwwk.", ".kgwwwwwwwwgk.",
        "kkgbbbbbbbbgkk", ".kgbbbbbbbbgk.", ".kwgbbbbbbgwk.", ".kwbbbbbbbbwk.", ".kwbbllllbbwk.", ".kwbllccllbwk.",
        ".kwbllccllbwk.", ".kwbbllllbbwk.", ".kwbbbbbbbbwk.", ".kwbbbbbbbbwk.", ".kwbbbbbbbbwk.", ".kwgbbbbbbgwk.",
        ".kwwwwwwwwwwk.", ".kgwwwwwwwwgk.", ".kgbbbbbbbbgk.", ".kgwbbbbbbwgk.", ".kwwwwwwwwwwk.", ".kwwwwccwwwwk.",
        ".kwwwwwwwwwwk.", "skwwwwwwwwwwks", "..krwwwwwwrk..", "...kkkkkkkk...",
    ];
    sprite(
        rows,
        "kwgblcsry",
        &[
            rgb(0x1c1c22), rgb(0xf6f6f3), rgb(0xc8cbd1), rgb(0x23262d), rgb(0x3d434d), rgb(0x5fd3c6), rgb(0x111114), rgb(0xd23a3a),
            rgb(0xfff0b0),
        ],
    )
}
