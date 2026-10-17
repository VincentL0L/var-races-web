//! The road: which pixels are road, and where the walls are.
//!
//! The road mask comes from each map's road_mask.png (white = road), packed by
//! `cargo run -p tools -- pack-masks` into data/masks/<id>.bin: a small header and then every
//! row as run lengths, alternating off-road and road, starting with off-road.
//!
//! On maps with barriers, next to the road there's a strip you can drive on (slowly, it's
//! grass) and past that a wall. The wall follows the road's shape: every spot gets its
//! distance to the nearest road, and anything further than RUNOFF is behind the wall.

use crate::math::{cos, deg, sin, Vec2};

/// how far from the road a car's center can go, in map pixels
pub const RUNOFF: f32 = 12.0;
/// cars can't drive closer than this to the edge of the map
pub const MAP_MARGIN: f32 = 8.0;
/// distances are worked out on a grid of this many mask pixels per cell (full-size masks)
const CELL: usize = 2;
/// chamfer distance steps (straight and diagonal, about 3 : 4.24)
const STRAIGHT: u32 = 3;
const DIAGONAL: u32 = 4;
const FAR: u32 = u32::MAX / 2;

pub const MASK_MAGIC: &[u8; 4] = b"VRM1";

/// one road mask pixel per bit, rows top to bottom like the picture
#[derive(Clone, Debug)]
pub struct Mask {
    pub width: usize,
    pub height: usize,
    bits: Vec<u64>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum MaskError {
    BadHeader,
    Truncated,
    BadRuns,
}

impl Mask {
    /// builds a mask from a road test per pixel (row 0 is the top of the picture)
    pub fn from_fn(width: usize, height: usize, road: impl Fn(usize, usize) -> bool) -> Mask {
        let mut mask = Mask { width, height, bits: vec![0; (width * height).div_ceil(64)] };
        for y in 0..height {
            for x in 0..width {
                if road(x, y) {
                    let i = y * width + x;
                    mask.bits[i / 64] |= 1 << (i % 64);
                }
            }
        }
        mask
    }

    pub fn get(&self, x: i64, y: i64) -> bool {
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return false;
        }
        let i = y as usize * self.width + x as usize;
        self.bits[i / 64] >> (i % 64) & 1 == 1
    }

    /// the packed .bin form (see the module comment)
    pub fn encode(&self) -> Vec<u8> {
        let mut out = MASK_MAGIC.to_vec();
        out.extend_from_slice(&(self.width as u32).to_le_bytes());
        out.extend_from_slice(&(self.height as u32).to_le_bytes());
        for y in 0..self.height {
            let mut value = false;
            let mut run = 0u32;
            for x in 0..self.width {
                let road = self.get(x as i64, y as i64);
                if road != value {
                    write_varint(&mut out, run);
                    value = road;
                    run = 0;
                }
                run += 1;
            }
            write_varint(&mut out, run);
        }
        out
    }

    pub fn decode(data: &[u8]) -> Result<Mask, MaskError> {
        if data.len() < 12 || &data[..4] != MASK_MAGIC {
            return Err(MaskError::BadHeader);
        }
        let width = u32::from_le_bytes(data[4..8].try_into().unwrap()) as usize;
        let height = u32::from_le_bytes(data[8..12].try_into().unwrap()) as usize;
        if width == 0 || height == 0 || width > 16384 || height > 16384 {
            return Err(MaskError::BadHeader);
        }
        let mut mask = Mask { width, height, bits: vec![0; (width * height).div_ceil(64)] };
        let mut pos = 12;
        for y in 0..height {
            let mut x = 0usize;
            let mut value = false;
            while x < width {
                let run = read_varint(data, &mut pos)? as usize;
                if x + run > width {
                    return Err(MaskError::BadRuns);
                }
                if value {
                    for i in x..x + run {
                        let bit = y * width + i;
                        mask.bits[bit / 64] |= 1 << (bit % 64);
                    }
                }
                x += run;
                value = !value;
            }
        }
        Ok(mask)
    }
}

fn write_varint(out: &mut Vec<u8>, mut v: u32) {
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn read_varint(data: &[u8], pos: &mut usize) -> Result<u32, MaskError> {
    let mut v = 0u32;
    for shift in (0..35).step_by(7) {
        let byte = *data.get(*pos).ok_or(MaskError::Truncated)?;
        *pos += 1;
        v |= ((byte & 0x7f) as u32) << shift;
        if byte & 0x80 == 0 {
            return Ok(v);
        }
    }
    Err(MaskError::BadRuns)
}

/// A straight section of the wall; (nx, ny) points toward the road.
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize)]
pub struct Wall {
    pub ax: f32,
    pub ay: f32,
    pub bx: f32,
    pub by: f32,
    pub nx: f32,
    pub ny: f32,
}

/// A turn board on the outside wall of a corner, facing drivers coming along the racing line.
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize)]
pub struct Sign {
    pub x: f32,
    pub y: f32,
    /// the board's direction to the driver's right
    pub right_x: f32,
    pub right_y: f32,
    pub turn_right: bool,
}

/// the 16 directions checked round a car's center by `drivable`
fn ring() -> [Vec2; 16] {
    let mut dirs = [Vec2::ZERO; 16];
    for (i, d) in dirs.iter_mut().enumerate() {
        let a = deg(22.5 * i as f32);
        *d = Vec2::new(cos(a), sin(a));
    }
    dirs
}

/// One map's road and walls. Built once per race from the packed mask.
#[derive(Clone, Debug)]
pub struct Track {
    mask: Mask,
    /// map pixels per mask pixel
    scale: u32,
    pub width: f32,
    pub height: f32,
    barriers: bool,
    grid_width: usize,
    grid_height: usize,
    /// a distance cell's size in map pixels
    cell_size: f32,
    /// distance from each cell to the road, in chamfer steps (3 per cell)
    distance: Vec<u32>,
    ring: [Vec2; 16],
}

impl Track {
    pub fn new(mask: Mask, scale: u32, barriers: bool) -> Track {
        let cell_pixels = if scale > 1 { 1 } else { CELL };
        let grid_width = mask.width / cell_pixels;
        let grid_height = mask.height / cell_pixels;
        let mut track = Track {
            width: (mask.width as u32 * scale) as f32,
            height: (mask.height as u32 * scale) as f32,
            scale,
            barriers,
            grid_width,
            grid_height,
            cell_size: (cell_pixels as u32 * scale) as f32,
            distance: Vec::new(),
            ring: ring(),
            mask,
        };
        if barriers {
            track.build_distances(cell_pixels);
        }
        track
    }

    /// a track for a map from its packed mask
    pub fn for_map(map: &crate::maps::MapDef, mask_bin: &[u8]) -> Result<Track, MaskError> {
        Ok(Track::new(Mask::decode(mask_bin)?, map.scale, map.has_barriers()))
    }

    fn build_distances(&mut self, cell_pixels: usize) {
        let (w, h) = (self.grid_width, self.grid_height);
        let mut d = vec![FAR; w * h];
        for gy in 0..h {
            for gx in 0..w {
                let px = (gx * cell_pixels + cell_pixels / 2) as i64;
                let py = (gy * cell_pixels + cell_pixels / 2) as i64;
                if self.mask.get(px, py) {
                    d[gy * w + gx] = 0;
                }
            }
        }
        // two sweeps (top-left to bottom-right, then back) spread the distances out
        for gy in 0..h {
            for gx in 0..w {
                let i = gy * w + gx;
                let mut v = d[i];
                if gx > 0 {
                    v = v.min(d[i - 1] + STRAIGHT);
                }
                if gy > 0 {
                    v = v.min(d[i - w] + STRAIGHT);
                    if gx > 0 {
                        v = v.min(d[i - w - 1] + DIAGONAL);
                    }
                    if gx < w - 1 {
                        v = v.min(d[i - w + 1] + DIAGONAL);
                    }
                }
                d[i] = v;
            }
        }
        for gy in (0..h).rev() {
            for gx in (0..w).rev() {
                let i = gy * w + gx;
                let mut v = d[i];
                if gx < w - 1 {
                    v = v.min(d[i + 1] + STRAIGHT);
                }
                if gy < h - 1 {
                    v = v.min(d[i + w] + STRAIGHT);
                    if gx < w - 1 {
                        v = v.min(d[i + w + 1] + DIAGONAL);
                    }
                    if gx > 0 {
                        v = v.min(d[i + w - 1] + DIAGONAL);
                    }
                }
                d[i] = v;
            }
        }
        self.distance = d;
    }

    pub fn has_barriers(&self) -> bool {
        self.barriers
    }

    /// true if the map point `p` is on the road (not grass)
    pub fn on_road(&self, p: Vec2) -> bool {
        let s = self.scale as f32;
        let x = (p.x / s) as i64;
        let y = ((self.height - p.y) / s) as i64;
        // a point left of or below the map truncates toward 0, like the original; reject it
        if p.x < 0.0 || p.y > self.height {
            return false;
        }
        self.mask.get(x, y)
    }

    /// how far the map point is from the road, in map pixels (only on maps with barriers)
    pub fn distance_to_road(&self, p: Vec2) -> f32 {
        if p.x < 0.0 || p.y < 0.0 || self.distance.is_empty() {
            return f32::MAX;
        }
        let gx = (p.x / self.cell_size) as i64;
        let gy = ((self.height - p.y) / self.cell_size) as i64;
        if gx < 0 || gy < 0 || gx as usize >= self.grid_width || gy as usize >= self.grid_height {
            return f32::MAX;
        }
        self.distance[gy as usize * self.grid_width + gx as usize] as f32 * self.cell_size / STRAIGHT as f32
    }

    fn center_drivable(&self, p: Vec2) -> bool {
        self.distance_to_road(p) <= RUNOFF
    }

    /// true if a car of this radius can have its center at p: inside the map edge and, on
    /// maps with barriers, nowhere touching a wall
    pub fn in_bounds(&self, p: Vec2, radius: f32) -> bool {
        if p.x < MAP_MARGIN || p.y < MAP_MARGIN || p.x >= self.width - MAP_MARGIN || p.y >= self.height - MAP_MARGIN {
            return false;
        }
        if !self.barriers {
            return true;
        }
        self.center_drivable(p) && self.ring.iter().all(|&d| self.center_drivable(p + d * radius))
    }

    fn cell_drivable(&self, x: i64, y: i64) -> bool {
        x >= 0
            && y >= 0
            && (x as usize) < self.grid_width
            && (y as usize) < self.grid_height
            && self.distance[y as usize * self.grid_width + x as usize] as f32 * self.cell_size / STRAIGHT as f32
                <= RUNOFF
    }

    /// the turn boards for a map's corners (courses with barriers only)
    pub fn signs(&self, map: &crate::maps::MapDef) -> Vec<Sign> {
        let mut signs = Vec::new();
        if !self.barriers {
            return signs;
        }
        let points = map.waypoint_list();
        let n = points.len();
        let sprint = map.kind == crate::maps::MapKind::Sprint;
        for i in 0..n {
            if sprint && (i == 0 || i == n - 1) {
                continue;
            }
            let corner = points[i];
            let incoming = corner - points[(i + n - 1) % n];
            let outgoing = points[(i + 1) % n] - corner;
            if incoming.len2() < 1.0 || outgoing.len2() < 1.0 {
                continue;
            }
            let (incoming, outgoing) = (incoming.norm(), outgoing.norm());
            let cross = incoming.cross(outgoing);
            // ignore the straight start/finish waypoint and very gentle bends
            if incoming.dot(outgoing) > 0.9 || cross.abs() < 0.2 || !self.center_drivable(corner) {
                continue;
            }
            // carry on straight past the turn to find the outside wall, rather than putting
            // signs on the road or blocking the exit of the corner
            let mut distance = 0.0;
            while distance < 300.0 && self.center_drivable(corner + incoming * distance) {
                distance += 2.0;
            }
            if distance >= 300.0 {
                continue;
            }
            let at = corner + incoming * (distance - 2.0);
            signs.push(Sign { x: at.x, y: at.y, right_x: incoming.y, right_y: -incoming.x, turn_right: cross < 0.0 });
        }
        signs
    }

    /// the walls as straight segments, merged along the distance grid (for drawing them)
    pub fn walls(&self) -> Vec<Wall> {
        let mut walls = Vec::new();
        if self.distance.is_empty() {
            return walls;
        }
        let (gw, gh) = (self.grid_width as i64, self.grid_height as i64);
        let c = self.cell_size;
        let h = self.height;
        for x in 0..=gw {
            let (mut start, mut previous) = (0i64, 0i32);
            for y in 0..=gh {
                let side = if y == gh {
                    0
                } else {
                    self.cell_drivable(x, y) as i32 - self.cell_drivable(x - 1, y) as i32
                };
                if side != previous {
                    if previous != 0 {
                        walls.push(Wall {
                            ax: x as f32 * c,
                            ay: h - start as f32 * c,
                            bx: x as f32 * c,
                            by: h - y as f32 * c,
                            nx: previous as f32,
                            ny: 0.0,
                        });
                    }
                    start = y;
                    previous = side;
                }
            }
        }
        for y in 0..=gh {
            let (mut start, mut previous) = (0i64, 0i32);
            for x in 0..=gw {
                let side = if x == gw {
                    0
                } else {
                    self.cell_drivable(x, y - 1) as i32 - self.cell_drivable(x, y) as i32
                };
                if side != previous {
                    if previous != 0 {
                        walls.push(Wall {
                            ax: start as f32 * c,
                            ay: h - y as f32 * c,
                            bx: x as f32 * c,
                            by: h - y as f32 * c,
                            nx: 0.0,
                            ny: previous as f32,
                        });
                    }
                    start = x;
                    previous = side;
                }
            }
        }
        walls
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// a 100 x 60 mask with a horizontal road 20 pixels tall through the middle
    fn strip() -> Mask {
        Mask::from_fn(100, 60, |_, y| (20..40).contains(&y))
    }

    #[test]
    fn mask_round_trips_through_the_packed_form() {
        let mask = strip();
        let back = Mask::decode(&mask.encode()).unwrap();
        for y in 0..60 {
            for x in 0..100 {
                assert_eq!(mask.get(x, y), back.get(x, y));
            }
        }
        assert_eq!(Mask::decode(b"nope").unwrap_err(), MaskError::BadHeader);
    }

    #[test]
    fn road_and_walls_follow_the_mask() {
        let track = Track::new(strip(), 1, true);
        // y is flipped: mask rows 20..40 are map y 20..40 measured from the bottom too
        assert!(track.on_road(Vec2::new(50.0, 30.0)));
        assert!(!track.on_road(Vec2::new(50.0, 5.0)));
        assert!(track.in_bounds(Vec2::new(50.0, 30.0), 10.0));
        // 10 pixels off the road: the center is fine but the car's edge hits the wall
        assert!(!track.in_bounds(Vec2::new(50.0, 50.0), 10.0));
        let walls = track.walls();
        assert!(walls.iter().any(|w| w.ny == 1.0) && walls.iter().any(|w| w.ny == -1.0));
    }
}

#[cfg(test)]
mod map_tests {
    use crate::car::WALL_RADIUS;
    use crate::maps::{self, MapKind};

    /// every car on every map starts somewhere it can drive (inside the walls, on the road)
    #[test]
    fn every_starting_spot_is_drivable() {
        for map in maps::ALL.iter() {
            let track = maps::track(map);
            for i in 0..12 {
                let spawn = if i < 3 { maps::cpu_spawn(map, i) } else { maps::player_spawn(map, i - 3) };
                assert!(track.in_bounds(spawn.pos, WALL_RADIUS), "{} spawn {i} at {:?} hits a wall", map.id, spawn.pos);
                if map.kind != MapKind::Battle {
                    assert!(track.on_road(spawn.pos), "{} spawn {i} is off the road", map.id);
                }
            }
        }
    }

    /// the racing line runs along the road everywhere, so the CPUs can follow it
    #[test]
    fn waypoints_are_on_the_road() {
        for map in maps::ALL.iter().filter(|m| m.kind != MapKind::Battle) {
            let track = maps::track(map);
            for (i, &(x, y)) in map.waypoints.iter().enumerate() {
                assert!(track.on_road(crate::math::Vec2::new(x, y)), "{} waypoint {i} ({x}, {y}) is off the road", map.id);
            }
        }
    }

    #[test]
    fn every_course_has_turn_signs() {
        for map in maps::ALL.iter().filter(|m| m.kind != MapKind::Battle) {
            assert!(!maps::track(map).signs(map).is_empty(), "{}", map.id);
        }
    }

    #[test]
    fn walls_surround_every_course() {
        for map in maps::ALL.iter() {
            let walls = maps::track(map).walls();
            assert_eq!(walls.is_empty(), map.kind == MapKind::Battle, "{}", map.id);
        }
    }
}
