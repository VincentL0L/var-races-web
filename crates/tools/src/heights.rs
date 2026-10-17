//! Works out what stands up off each map, for the first-person view: trees and buildings,
//! read from the colours of the map picture. Roads, pavements, grass and water stay flat. The
//! result is a small picture, one pixel per CELL x CELL patch of the map, whose red channel is
//! the height (map pixels) of whatever stands there and green its kind (1 tree, 2 building).
//! Writes heights.png next to each map's background.png.

use std::collections::VecDeque;
use std::path::Path;

use crate::image::Picture;
use crate::masks::{read_rgba, Rgba};

/// map pixels per height cell
const CELL: i32 = 12;
/// keep this far (map pixels) from the road clear, so the pavements stay flat
const ROAD_CLEAR: i32 = 26;
const GROUND_SHARE: f32 = 0.6;

pub fn make_all(root: &Path) -> Result<(), String> {
    // map, what to raise (T trees, B buildings)
    for map in sim::maps::ALL.iter() {
        let kinds = match map.id {
            "classic" | "summit" => "T",
            "moon" => "",
            "junkyard" | "stadium" | "rink" => "B",
            _ => "TB",
        };
        make(root, map.id, map.scale as i32, kinds)?;
    }
    Ok(())
}

fn argb(img: &Rgba, x: i32, y: i32) -> u32 {
    let p = &img.pixels[(y as usize * img.width + x as usize) * 4..][..4];
    (p[3] as u32) << 24 | (p[0] as u32) << 16 | (p[1] as u32) << 8 | p[2] as u32
}

fn is_road(mask: &Rgba, x: f32, y: f32) -> bool {
    let mx = (mask.width as i32 - 1).min(x as i32);
    let my = (mask.height as i32 - 1).min(y as i32);
    (argb(mask, mx, my) >> 8) & 0xff > 128
}

fn quant(c: u32) -> u32 {
    c & 0xf0f0f0
}

fn near_any(c: u32, ground: &[u32]) -> bool {
    let (r, g, b) = ((c >> 16 & 0xff) as i32, (c >> 8 & 0xff) as i32, (c & 0xff) as i32);
    ground.iter().any(|&q| {
        let dr = r - ((q >> 16 & 0xff) as i32 + 8);
        let dg = g - ((q >> 8 & 0xff) as i32 + 8);
        let db = b - ((q & 0xff) as i32 + 8);
        dr * dr + dg * dg + db * db < 26 * 26
    })
}

/// colour counts in the order a java.util.HashMap<Integer, Integer> would list them: by bucket
/// in the final table, then in the order they were first added (ties in the count sort keep
/// this order, so it decides which colours count as ground)
struct JavaCounts {
    entries: Vec<(u32, u32)>,
    index: std::collections::HashMap<u32, usize>,
    capacity: usize,
}

impl JavaCounts {
    fn new() -> JavaCounts {
        JavaCounts { entries: Vec::new(), index: Default::default(), capacity: 16 }
    }

    fn add(&mut self, key: u32) {
        if let Some(&i) = self.index.get(&key) {
            self.entries[i].1 += 1;
            return;
        }
        self.index.insert(key, self.entries.len());
        self.entries.push((key, 1));
        if self.entries.len() > self.capacity * 3 / 4 {
            self.capacity *= 2;
        }
    }

    /// commonest first, ties in HashMap order
    fn sorted(self) -> Vec<(u32, u32)> {
        let cap = self.capacity as u32;
        let mut list: Vec<(usize, (u32, u32))> = self.entries.into_iter().enumerate().collect();
        list.sort_by_key(|&(i, (k, _))| (((k ^ (k >> 16)) & (cap - 1)), i));
        let mut list: Vec<(u32, u32)> = list.into_iter().map(|(_, e)| e).collect();
        list.sort_by_key(|&(_, count)| std::cmp::Reverse(count));
        list
    }
}

fn dilate(a: &[Vec<bool>], w: usize, h: usize) -> Vec<Vec<bool>> {
    let mut o = vec![vec![false; h]; w];
    for x in 0..w as i32 {
        for y in 0..h as i32 {
            o[x as usize][y as usize] = (-1..=1).any(|dx| {
                (-1..=1).any(|dy| {
                    let (xx, yy) = (x + dx, y + dy);
                    xx >= 0 && yy >= 0 && xx < w as i32 && yy < h as i32 && a[xx as usize][yy as usize]
                })
            });
        }
    }
    o
}

fn erode(a: &[Vec<bool>], w: usize, h: usize) -> Vec<Vec<bool>> {
    let mut o = vec![vec![false; h]; w];
    for x in 0..w as i32 {
        for y in 0..h as i32 {
            o[x as usize][y as usize] = (-1..=1).all(|dx| {
                (-1..=1).all(|dy| {
                    let (xx, yy) = (x + dx, y + dy);
                    !(xx >= 0 && yy >= 0 && xx < w as i32 && yy < h as i32 && !a[xx as usize][yy as usize])
                })
            });
        }
    }
    o
}

const DIRS: [(i32, i32); 4] = [(1, 0), (-1, 0), (0, 1), (0, -1)];

/// cell distance (4-way steps) from the nearest road cell
fn distance_from(road: &[Vec<bool>], gw: usize, gh: usize) -> Vec<Vec<i32>> {
    let mut d = vec![vec![i32::MAX; gh]; gw];
    let mut q = VecDeque::new();
    for x in 0..gw {
        for y in 0..gh {
            if road[x][y] {
                d[x][y] = 0;
                q.push_back((x as i32, y as i32));
            }
        }
    }
    while let Some((px, py)) = q.pop_front() {
        let here = d[px as usize][py as usize];
        for (dx, dy) in DIRS {
            let (x, y) = (px + dx, py + dy);
            if x >= 0 && y >= 0 && x < gw as i32 && y < gh as i32 && d[x as usize][y as usize] > here + 1 {
                d[x as usize][y as usize] = here + 1;
                q.push_back((x, y));
            }
        }
    }
    d
}

fn make(root: &Path, id: &str, scale: i32, kinds: &str) -> Result<(), String> {
    let dir = root.join(format!("assets/maps/{id}"));
    let img = read_rgba(&dir.join("background.png"))?;
    let mask = read_rgba(&dir.join("road_mask.png"))?;
    let (world_w, world_h) = (img.width as i32 * scale, img.height as i32 * scale);
    let (gw, gh) = ((world_w + CELL - 1) / CELL, (world_h + CELL - 1) / CELL);
    let (gwu, ghu) = (gw as usize, gh as usize);
    // the mask may be stored at its own size: map world -> mask pixels
    let (mx, my) = (mask.width as f32 / world_w as f32, mask.height as f32 / world_h as f32);

    // the ground's own colours: the commonest colours off the road (grass, pavement, water...)
    let mut counts = JavaCounts::new();
    let mut total = 0;
    for x in (0..img.width as i32).step_by(2) {
        for y in (0..img.height as i32).step_by(2) {
            if is_road(&mask, (x * scale) as f32 * mx, (y * scale) as f32 * my) {
                continue;
            }
            counts.add(quant(argb(&img, x, y)));
            total += 1;
        }
    }
    let mut ground = Vec::new();
    let mut covered = 0;
    for (key, count) in counts.sorted() {
        if covered as f32 > total as f32 * GROUND_SHARE || ground.len() >= 14 {
            break;
        }
        ground.push(key);
        covered += count;
    }

    let mut object = vec![vec![false; ghu]; gwu];
    let mut kind = vec![vec![0u32; ghu]; gwu]; // 0 flat, 1 tree, 2 building
    let mut road = vec![vec![false; ghu]; gwu];
    let mut green = vec![vec![0f32; ghu]; gwu];
    let step = scale.max(1) as usize;
    for gx in 0..gw {
        for gy in 0..gh {
            let (mut odd, mut n, mut greenish, mut leaves) = (0, 0, 0, 0);
            let mut any_road = false;
            for wx in (gx * CELL..world_w.min(gx * CELL + CELL)).step_by(step) {
                for wy in (gy * CELL..world_h.min(gy * CELL + CELL)).step_by(step) {
                    if is_road(&mask, wx as f32 * mx, wy as f32 * my) {
                        any_road = true;
                    }
                    let c = argb(&img, wx / scale, wy / scale);
                    let (r, g, b) = ((c >> 16 & 0xff) as i32, (c >> 8 & 0xff) as i32, (c & 0xff) as i32);
                    n += 1;
                    if g > r + 30 && r < 95 && b > 30 && b < 100 {
                        leaves += 1; // the dark green of tree tops
                    }
                    if !near_any(c, &ground) {
                        odd += 1;
                        if g > r + 8 && g > b + 8 {
                            greenish += 1;
                        }
                    }
                }
            }
            let (x, y) = (gx as usize, gy as usize);
            road[x][y] = any_road;
            object[x][y] = !any_road && (odd as f32 > n as f32 * 0.5 || leaves as f32 > n as f32 * 0.45);
            green[x][y] = if leaves as f32 > n as f32 * 0.45 {
                1.0
            } else if odd == 0 {
                0.0
            } else {
                greenish as f32 / odd as f32
            };
        }
    }
    // close small gaps, then drop specks
    let object = erode(&dilate(&object, gwu, ghu), gwu, ghu);
    for x in 0..gwu {
        for y in 0..ghu {
            if object[x][y] && !road[x][y] {
                kind[x][y] = if green[x][y] > 0.5 { 1 } else { 2 };
            }
        }
    }
    let (trees, buildings) = (kinds.contains('T'), kinds.contains('B'));
    for column in kind.iter_mut() {
        for k in column.iter_mut() {
            if *k == 1 && !trees {
                *k = if buildings { 2 } else { 0 };
            } else if *k == 2 && !buildings {
                *k = if trees { 1 } else { 0 };
            }
        }
    }
    // flatten everything close to the road
    let clear = (ROAD_CLEAR + CELL - 1) / CELL;
    let near = distance_from(&road, gwu, ghu);
    for x in 0..gwu {
        for y in 0..ghu {
            if near[x][y] <= clear {
                kind[x][y] = 0;
            }
        }
    }
    // buildings: whole blocks get one height; tiny scraps are dropped
    let mut height = vec![vec![0i32; ghu]; gwu];
    let mut seen = vec![vec![false; ghu]; gwu];
    let mut part = Vec::new();
    for gx in 0..gw {
        for gy in 0..gh {
            let (sx, sy) = (gx as usize, gy as usize);
            if seen[sx][sy] || kind[sx][sy] == 0 {
                continue;
            }
            let k = kind[sx][sy];
            part.clear();
            let mut queue = VecDeque::from([(gx, gy)]);
            seen[sx][sy] = true;
            while let Some((px, py)) = queue.pop_front() {
                part.push((px, py));
                for (dx, dy) in DIRS {
                    let (x, y) = (px + dx, py + dy);
                    if x >= 0 && y >= 0 && x < gw && y < gh && !seen[x as usize][y as usize] && kind[x as usize][y as usize] == k {
                        seen[x as usize][y as usize] = true;
                        queue.push_back((x, y));
                    }
                }
            }
            if part.len() < if k == 2 { 4 } else { 2 } {
                continue;
            }
            let hash = gx.wrapping_mul(73856093) ^ gy.wrapping_mul(19349663);
            for &(px, py) in &part {
                let h = if k == 1 {
                    // trees: a little uneven, cell by cell
                    16 + (px.wrapping_mul(92821) ^ py.wrapping_mul(68917)).rem_euclid(9)
                } else {
                    26 + hash.rem_euclid(5) * 9
                };
                height[px as usize][py as usize] = h;
            }
        }
    }
    // the picture is stored top row first; the game's y runs up
    let mut out = Picture::new(gwu, ghu);
    let mut raised = 0;
    for x in 0..gwu {
        for y in 0..ghu {
            let h = height[x][y].min(255) as u32;
            if h > 0 {
                raised += 1;
            }
            out.set(x, y, 0xff00_0000 | h << 16 | kind[x][y] << 8);
        }
    }
    out.save(dir.join("heights.png"), false)?;
    println!("{id}: {gw}x{gh} cells, {raised} raised");
    Ok(())
}
