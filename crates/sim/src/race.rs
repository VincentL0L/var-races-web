//! Race positions.
//!
//! The course is the line of waypoints, starting at the start line. Every car's progress is
//! how far along that line it has driven (plus a full lap length per lap), so cars are compared
//! fairly however they take the corners. A lap counts when a car crosses the start/finish line
//! going forward. Finished cars are ranked by finish time, everyone else by progress.

use serde::{Deserialize, Serialize};

use crate::maps::{MapDef, MapKind};
use crate::math::Vec2;

/// lap counts a race can be set to in the lobby
pub const LAP_CHOICES: [u8; 4] = [1, 2, 3, 5];

/// where one car is in the race
#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Standing {
    /// laps completed
    pub laps: u8,
    /// distance driven along the course since the start line, including whole laps
    pub progress: f32,
    /// race time when the car crossed the finish line
    pub finish_time: Option<f32>,
    /// the stretch of course the car is on (-1 until first seen)
    segment: i32,
    /// laps started (-1 while still behind the start line on a circuit)
    lap: i32,
    seen: bool,
}

impl Standing {
    pub fn finished(&self) -> bool {
        self.finish_time.is_some()
    }
}

/// The course of one map, ready to measure progress along.
#[derive(Clone, Debug)]
pub struct Course {
    /// the course line: point 0 is the start line
    path: Vec<Vec2>,
    segment_start: Vec<f32>,
    segment_length: Vec<f32>,
    /// length of one lap (or the whole sprint)
    pub length: f32,
    /// The corner bisector at each path point: a line through the point, halfway between the
    /// directions in and out. Stretch i runs from bisector i to bisector i+1, so neighbouring
    /// stretches meet exactly and progress never jumps at a corner.
    bisector: Vec<Vec2>,
    /// true on a sprint: the path runs once from the start line to the finish line
    open: bool,
    laps: i32,
}

impl Course {
    pub fn new(map: &MapDef) -> Course {
        let waypoints = map.waypoint_list();
        let open = map.kind == MapKind::Sprint;
        let path: Vec<Vec2> = if open {
            // the start line first, the finish line last (the run-off isn't part of the race)
            waypoints[..waypoints.len() - 1].to_vec()
        } else {
            // the start/finish line (the last waypoint) first
            let mut p = vec![*waypoints.last().unwrap()];
            p.extend_from_slice(&waypoints[..waypoints.len() - 1]);
            p
        };
        let n = path.len();
        let mut segment_start = vec![0.0; n];
        let mut segment_length = vec![0.0; n];
        let mut total = 0.0;
        for i in 0..n {
            segment_start[i] = total;
            segment_length[i] = if open && i == n - 1 { 0.0 } else { path[i].dist(path[(i + 1) % n]) };
            total += segment_length[i];
        }
        let mut bisector: Vec<Vec2> = (0..n)
            .map(|i| {
                let (prev, here, next) = (path[(i + n - 1) % n], path[i], path[(i + 1) % n]);
                ((here - prev).norm() + (next - here).norm()).norm()
            })
            .collect();
        // at the start the "bisector" is the painted line itself: straight across at y = 300
        bisector[0] = Vec2::new(0.0, 1.0);
        if open {
            // a sprint's finish line is straight across the last stretch
            bisector[n - 1] = (path[n - 1] - path[n - 2]).norm();
        }
        Course { path, segment_start, segment_length, length: total, bisector, open, laps: 1 }
    }

    pub fn set_laps(&mut self, laps: u8) {
        self.laps = if self.open { 1 } else { laps.max(1) as i32 };
    }

    pub fn laps(&self) -> u8 {
        self.laps as u8
    }

    /// Updates a car's standing from its position. `race_time` becomes its finish time.
    pub fn update(&self, s: &mut Standing, pos: Vec2, race_time: f32) {
        let n = self.path.len();
        if self.open {
            self.update_sprint(s, pos, race_time);
            return;
        }
        if !s.seen {
            s.seen = true;
            // first sighting: closest stretch anywhere. Cars lined up behind the start line are
            // at the very end of the loop, so they haven't started lap 1 yet.
            s.segment = self.closest_segment(pos, 0, n as i32);
            s.lap = if self.along(pos, s.segment) > self.length / 2.0 { -1 } else { 0 };
        } else if !s.finished() {
            // only look at the stretch the car was on and its neighbours, so cutting a corner
            // or driving near another part of the track can't make it jump
            let best = self.closest_segment(pos, s.segment - 1, 4);
            let mut moved = best - s.segment;
            if moved > n as i32 / 2 {
                moved -= n as i32;
            }
            if moved < -(n as i32) / 2 {
                moved += n as i32;
            }
            let unwrapped = s.segment + moved;
            if unwrapped >= n as i32 {
                s.lap += 1; // crossed the start/finish line going forward
            } else if unwrapped < 0 {
                s.lap -= 1; // backed over it
            }
            s.segment = best;
        }
        if !s.finished() {
            s.progress = s.lap as f32 * self.length + self.along(pos, s.segment);
            s.laps = s.lap.clamp(0, self.laps) as u8;
            if s.lap >= self.laps {
                s.finish_time = Some(race_time);
                s.progress = self.laps as f32 * self.length;
            }
        }
    }

    fn update_sprint(&self, s: &mut Standing, pos: Vec2, race_time: f32) {
        let n = self.path.len();
        let segments = n - 1;
        if !s.seen {
            s.seen = true;
            // first sighting: simply the nearest stretch (the bisector test can match a stretch
            // far away for a car still behind the start line)
            let mut best = f32::MAX;
            for i in 0..segments {
                let d = self.distance_to_segment(pos, i as i32);
                if d < best {
                    best = d;
                    s.segment = i as i32;
                }
            }
        } else if !s.finished() {
            let from = (s.segment - 1).max(0);
            let count = (segments as i32 - from).min(4);
            s.segment = self.closest_segment(pos, from, count).max(0);
        }
        if s.finished() {
            return;
        }
        s.lap = 0;
        s.laps = 0;
        // behind the start line counts as negative, so the starting grid is in order
        let behind = self.past_bisector(pos, 0);
        s.progress = if s.segment == 0 && behind < 0.0 { behind } else { self.along(pos, s.segment) };
        if s.segment == segments as i32 - 1 && self.past_bisector(pos, n - 1) >= 0.0 {
            s.finish_time = Some(race_time);
            s.laps = 1;
            s.progress = self.length;
        }
    }

    /// index of the stretch closest to p, among `count` stretches starting at `first`
    fn closest_segment(&self, p: Vec2, first: i32, count: i32) -> i32 {
        let n = self.path.len() as i32;
        let mut best = -1;
        let mut best_dist = f32::MAX;
        // first choice: stretches the car is actually between the bisectors of
        for pass in 0..2 {
            if best >= 0 {
                break;
            }
            for k in 0..count {
                let i = (first + k).rem_euclid(n);
                if self.open && i >= n - 1 {
                    continue; // a sprint has no stretch from the finish back to the start
                }
                if pass == 0
                    && !(self.past_bisector(p, i as usize) >= 0.0
                        && self.past_bisector(p, ((i + 1) % n) as usize) < 0.0)
                {
                    continue;
                }
                let d = self.distance_to_segment(p, i);
                if d < best_dist {
                    best_dist = d;
                    best = i;
                }
            }
        }
        best
    }

    /// how far p is past the bisector line at path point i (negative = before it)
    fn past_bisector(&self, p: Vec2, i: usize) -> f32 {
        (p - self.path[i]).dot(self.bisector[i])
    }

    fn distance_to_segment(&self, p: Vec2, i: i32) -> f32 {
        let (a, b) = self.segment(i);
        p.dist(a.lerp(b, projection(p, a, b)))
    }

    fn segment(&self, i: i32) -> (Vec2, Vec2) {
        let n = self.path.len();
        let i = i.rem_euclid(n as i32) as usize;
        (self.path[i], self.path[(i + 1) % n])
    }

    /// distance from the start line to p's spot on stretch i
    fn along(&self, p: Vec2, i: i32) -> f32 {
        let n = self.path.len();
        let iu = i.rem_euclid(n as i32) as usize;
        // fraction of the way from this stretch's start bisector to its end bisector
        let from_start = self.past_bisector(p, iu);
        let to_end = -self.past_bisector(p, (iu + 1) % n);
        let t = if from_start >= 0.0 && to_end >= 0.0 && from_start + to_end > 1e-4 {
            from_start / (from_start + to_end)
        } else {
            let (a, b) = self.segment(i);
            projection(p, a, b)
        };
        self.segment_start[iu] + t.clamp(0.0, 1.0) * self.segment_length[iu]
    }

    /// How far a car is from the stretch of course it's supposed to be on: large when it's out
    /// on the grass, or when it cut across and skipped part of the course.
    pub fn distance_from_course(&self, s: &Standing, pos: Vec2) -> f32 {
        if !s.seen || s.segment < 0 {
            return 0.0;
        }
        self.distance_to_segment(pos, s.segment)
    }
}

/// how far along a-b the point p is, from 0 (at a) to 1 (at b)
fn projection(p: Vec2, a: Vec2, b: Vec2) -> f32 {
    let d = b - a;
    let len2 = d.len2();
    if len2 < 1e-6 {
        return 0.0;
    }
    ((p - a).dot(d) / len2).clamp(0.0, 1.0)
}

/// Race order: finished cars first in the order they finished, then everyone else by distance.
/// Ties keep the given order. Returns indices into `standings`.
pub fn order(standings: &[&Standing]) -> Vec<usize> {
    let mut idx: Vec<usize> = (0..standings.len()).collect();
    idx.sort_by(|&a, &b| {
        let (ra, rb) = (standings[a], standings[b]);
        match (ra.finish_time, rb.finish_time) {
            (Some(x), Some(y)) => x.total_cmp(&y),
            (Some(_), None) => core::cmp::Ordering::Less,
            (None, Some(_)) => core::cmp::Ordering::Greater,
            (None, None) => rb.progress.total_cmp(&ra.progress),
        }
    });
    idx
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::maps;

    /// drives a point along the waypoints and checks progress only ever grows
    fn drive_course(map: &MapDef, laps: u8) -> Standing {
        let mut course = Course::new(map);
        course.set_laps(laps);
        let mut s = Standing::default();
        let start = maps::grid_slot(4).pos;
        course.update(&mut s, start, 0.0);
        let mut last = s.progress;
        let pts = map.waypoint_list();
        let mut route = Vec::new();
        for _ in 0..laps {
            route.extend(pts.iter().copied());
        }
        let mut pos = start;
        let mut t = 0.0;
        for target in route {
            while pos.dist(target) > 4.0 {
                pos = pos + (target - pos).norm() * 4.0;
                t += 0.01;
                course.update(&mut s, pos, t);
                assert!(s.progress >= last - 0.5, "{}: progress went back from {last} to {}", map.id, s.progress);
                last = s.progress;
            }
        }
        // a little past the line
        course.update(&mut s, pos + Vec2::new(0.0, 10.0), t);
        s
    }

    #[test]
    fn a_full_circuit_counts_laps_and_finishes() {
        for map in maps::ALL.iter().filter(|m| m.kind == MapKind::Circuit) {
            let s = drive_course(map, 2);
            assert!(s.finished(), "{} not finished: {:?}", map.id, s);
            assert_eq!(s.laps, 2);
        }
    }

    #[test]
    fn a_sprint_finishes_at_the_line() {
        for map in maps::ALL.iter().filter(|m| m.kind == MapKind::Sprint) {
            let s = drive_course(map, 1);
            assert!(s.finished(), "{} not finished", map.id);
        }
    }

    #[test]
    fn grid_starts_behind_the_line() {
        let map = maps::get("classic");
        let course = Course::new(map);
        let mut s = Standing::default();
        course.update(&mut s, maps::grid_slot(4).pos, 0.0);
        assert!(s.progress < 0.0 && s.lap == -1);
    }

    #[test]
    fn finished_cars_lead_then_by_progress() {
        let a = Standing { progress: 100.0, ..Standing::default() };
        let b = Standing { progress: 500.0, ..Standing::default() };
        let c = Standing { finish_time: Some(30.0), ..Standing::default() };
        let d = Standing { finish_time: Some(20.0), ..Standing::default() };
        assert_eq!(order(&[&a, &b, &c, &d]), vec![3, 2, 1, 0]);
    }
}
