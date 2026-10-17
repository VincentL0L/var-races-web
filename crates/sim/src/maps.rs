//! Every map: its name, size, racing line and rules. This is the single source of truth,
//! used by the simulation, the server, the browser (through the wasm bindings) and the
//! asset generators in crates/tools.
//!
//! Coordinates are map pixels with y pointing up. Circuits all start the same way: the last
//! stretch comes in along the bottom left, turns up at x = 200 and crosses the checkered line
//! at y = 300, so the starting grid and the finish line work the same on all of them.

use core::f32::consts::FRAC_PI_2;

use serde::Serialize;

use crate::math::{deg, Vec2};

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum MapKind {
    Circuit,
    Sprint,
    Battle,
}

#[cfg_attr(feature = "ts", derive(tsify::Tsify))]
#[derive(Debug, Serialize)]
pub struct MapDef {
    /// short id sent between the game and the server; also the asset folder (assets/maps/<id>)
    pub id: &'static str,
    pub name: &'static str,
    /// one line under the name on the map picker
    pub tagline: &'static str,
    pub kind: MapKind,
    /// size of the map in map pixels
    pub width: f32,
    pub height: f32,
    /// the picture and road mask are stored this many times smaller than the map
    pub scale: u32,
    /// tire grip and launch traction on this surface (1 = normal; the moon is slippery)
    pub grip: f32,
    pub traction: f32,
    /// sprite for the CPU cars (assets/ui/<name>.png), or None for the usual three colors
    pub cpu_sprite: Option<&'static str>,
    /// what the CPUs are called in the standings: "CPU", "WAYMO"...
    pub cpu_name: &'static str,
    /// The racing line. Circuit: the last point is on the start/finish line. Sprint: the start
    /// line first, the finish line second to last, and a run-off point where the CPUs pull up.
    /// Battle: a loop round the arena, only used for the minimap and the item boxes.
    pub waypoints: &'static [(f32, f32)],
}

impl MapDef {
    /// walls a little way off the road so nobody can cut across (see Track)
    pub fn has_barriers(&self) -> bool {
        self.kind != MapKind::Battle
    }

    /// true if the lap count can be set (circuits only)
    pub fn has_laps(&self) -> bool {
        self.kind == MapKind::Circuit
    }

    pub fn waypoint(&self, i: usize) -> Vec2 {
        let (x, y) = self.waypoints[i];
        Vec2::new(x, y)
    }

    pub fn waypoint_list(&self) -> Vec<Vec2> {
        self.waypoints.iter().map(|&(x, y)| Vec2::new(x, y)).collect()
    }

    /// the name shown for CPU number `n` (1-based): "WAYMO 1"
    pub fn cpu_display_name(&self, n: usize) -> String {
        format!("{} {}", self.cpu_name, n)
    }
}

/// a starting spot: the car's center and the way it faces (radians, 0 = +x)
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Spawn {
    pub pos: Vec2,
    pub heading: f32,
}

/// The starting grid behind the checkered line (y 300): 3 columns across the road, 4 rows
/// back, with the middle column set back a little like a real staggered grid. The CPUs take
/// the front row (slots 0-2).
pub fn grid_slot(slot: usize) -> Spawn {
    let row = (slot / 3) as f32;
    let column = slot % 3;
    // the bottom left corner of a 10 x 20 car, moved to its center
    let x = 165.0 + column as f32 * 36.0 + 5.0;
    let y = 276.0 - row * 30.0 - if column == 1 { 12.0 } else { 0.0 } + 10.0;
    Spawn { pos: Vec2::new(x, y), heading: FRAC_PI_2 }
}

/// grid slots players take, in join order: middle of row 2 first, then its sides, then row 3
const PLAYER_GRID: [usize; 9] = [4, 3, 5, 7, 6, 8, 10, 9, 11];

/// arena slots for the three CPUs, and for players in join order
const CPU_ARENA: [usize; 3] = [1, 4, 7];
const PLAYER_ARENA: [usize; 7] = [0, 5, 2, 8, 3, 6, 9];

/// arena starting spots: a ring of 10 round the middle, everyone facing in
fn arena_spawn(slot: usize) -> Spawn {
    let a = deg(18.0 + 36.0 * (slot % 10) as f32);
    let center = Vec2::new(960.0, 720.0);
    let pos = Vec2::new(center.x + 620.0 * crate::math::cos(a), center.y + 400.0 * crate::math::sin(a));
    Spawn { pos, heading: a + core::f32::consts::PI }
}

/// where CPU number `index` (0-2) starts
pub fn cpu_spawn(map: &MapDef, index: usize) -> Spawn {
    if map.kind == MapKind::Battle {
        arena_spawn(CPU_ARENA[index % CPU_ARENA.len()])
    } else {
        grid_slot(index)
    }
}

/// where the player who joined `index`-th (0 = first) starts, behind the CPUs
pub fn player_spawn(map: &MapDef, index: usize) -> Spawn {
    if map.kind == MapKind::Battle {
        arena_spawn(PLAYER_ARENA[index % PLAYER_ARENA.len()])
    } else {
        grid_slot(PLAYER_GRID[index % PLAYER_GRID.len()])
    }
}

/// the map with this id (anything unknown gives the classic map)
pub fn get(id: &str) -> &'static MapDef {
    ALL.iter().find(|m| m.id == id).unwrap_or(&ALL[0])
}

/// the index of a map in ALL, for compact network messages
pub fn index_of(id: &str) -> Option<usize> {
    ALL.iter().position(|m| m.id == id)
}

const fn city(
    id: &'static str,
    name: &'static str,
    tagline: &'static str,
    waypoints: &'static [(f32, f32)],
    cpu_sprite: Option<&'static str>,
    cpu_name: &'static str,
) -> MapDef {
    MapDef {
        id,
        name,
        tagline,
        kind: MapKind::Circuit,
        width: 1920.0,
        height: 1080.0,
        scale: 1,
        grip: 1.0,
        traction: 1.0,
        cpu_sprite,
        cpu_name,
        waypoints,
    }
}

const fn sprint(mut map: MapDef, width: f32, height: f32) -> MapDef {
    map.kind = MapKind::Sprint;
    map.width = width;
    map.height = height;
    map.scale = 3;
    map
}

const fn arena(mut map: MapDef) -> MapDef {
    map.kind = MapKind::Battle;
    map.width = 1920.0;
    map.height = 1440.0;
    map.scale = 3;
    map
}

const fn surface(mut map: MapDef, grip: f32, traction: f32) -> MapDef {
    map.grip = grip;
    map.traction = traction;
    map
}

/// Battle arenas have no course; this loop round the inside of the arena is only used for
/// the minimap outline and where the item boxes go.
const ARENA_LOOP: &[(f32, f32)] = &[(360.0, 260.0), (1560.0, 260.0), (1560.0, 1180.0), (360.0, 1180.0)];

/// every map, in the order the picker shows them
pub static ALL: [MapDef; 15] = [
    city(
        "classic",
        "Classic",
        "The original circuit. Follow the chevrons through the turns.",
        &[
            (190.0, 470.0), (530.0, 470.0), (530.0, 700.0), (150.0, 700.0), (150.0, 935.0),
            (990.0, 935.0), (990.0, 660.0), (800.0, 660.0), (800.0, 440.0), (1240.0, 440.0),
            (1240.0, 700.0), (1460.0, 700.0), (1460.0, 935.0), (1780.0, 935.0), (1780.0, 440.0),
            (1450.0, 440.0), (1450.0, 230.0), (200.0, 230.0), (200.0, 300.0),
        ],
        None,
        "CPU",
    ),
    city(
        "sf",
        "San Francisco",
        "Over the Golden Gate and down the Embarcadero, against Waymos.",
        &[
            (200.0, 880.0), (1740.0, 880.0), (1740.0, 180.0), (1100.0, 180.0), (1100.0, 520.0),
            (620.0, 520.0), (620.0, 230.0), (200.0, 230.0), (200.0, 300.0),
        ],
        Some("waymo"),
        "WAYMO",
    ),
    city(
        "tokyo",
        "Tokyo",
        "Through the Shibuya scramble and around Tokyo Tower, against kei cars.",
        &[
            (200.0, 860.0), (700.0, 860.0), (700.0, 620.0), (1200.0, 620.0), (1200.0, 900.0),
            (1760.0, 900.0), (1760.0, 200.0), (1000.0, 200.0), (1000.0, 420.0), (520.0, 420.0),
            (520.0, 230.0), (200.0, 230.0), (200.0, 300.0),
        ],
        Some("kei"),
        "KEI",
    ),
    city(
        "nyc",
        "New York",
        "Past Central Park, over the Brooklyn Bridge and through Times Square, against yellow cabs.",
        &[
            (200.0, 700.0), (560.0, 700.0), (560.0, 900.0), (1760.0, 900.0), (1760.0, 200.0),
            (1240.0, 200.0), (1240.0, 520.0), (860.0, 520.0), (860.0, 230.0), (200.0, 230.0),
            (200.0, 300.0),
        ],
        Some("cab"),
        "CAB",
    ),
    city(
        "chicago",
        "Chicago",
        "Over the river, past the Bean and Willis Tower, chased by squad cars.",
        &[
            (200.0, 900.0), (900.0, 900.0), (900.0, 700.0), (1500.0, 700.0), (1500.0, 160.0),
            (1100.0, 160.0), (1100.0, 430.0), (640.0, 430.0), (640.0, 230.0), (200.0, 230.0),
            (200.0, 300.0),
        ],
        Some("cop"),
        "COP",
    ),
    city(
        "london",
        "London",
        "Westminster to Tower Bridge, against red double-deckers.",
        &[
            (200.0, 860.0), (1000.0, 860.0), (1000.0, 960.0), (1760.0, 960.0), (1760.0, 520.0),
            (1300.0, 520.0), (1300.0, 180.0), (760.0, 180.0), (760.0, 420.0), (480.0, 420.0),
            (480.0, 230.0), (200.0, 230.0), (200.0, 300.0),
        ],
        Some("bus"),
        "BUS",
    ),
    city(
        "paris",
        "Paris",
        "Along the Seine, round the Eiffel Tower and the Arc, against 2CVs.",
        &[
            (200.0, 700.0), (700.0, 700.0), (700.0, 940.0), (1500.0, 940.0), (1500.0, 700.0),
            (1760.0, 700.0), (1760.0, 180.0), (1100.0, 180.0), (1100.0, 420.0), (560.0, 420.0),
            (560.0, 230.0), (200.0, 230.0), (200.0, 300.0),
        ],
        Some("2cv"),
        "2CV",
    ),
    city(
        "taipei",
        "Taipei",
        "Past Taipei 101 and the night market, against a swarm of scooters.",
        &[
            (200.0, 900.0), (1300.0, 900.0), (1300.0, 640.0), (1760.0, 640.0), (1760.0, 180.0),
            (1240.0, 180.0), (1240.0, 420.0), (800.0, 420.0), (800.0, 230.0), (200.0, 230.0),
            (200.0, 300.0),
        ],
        Some("moto"),
        "MOTO",
    ),
    // sprints: one run from A to B
    sprint(
        city(
            "summit",
            "Summit Sprint",
            "Hairpins up a mountain pass. The road narrows near the top.",
            &[
                (200.0, 300.0), (200.0, 620.0), (1650.0, 920.0), (1730.0, 1180.0), (1000.0, 1260.0),
                (850.0, 1420.0), (300.0, 1480.0), (230.0, 1740.0), (900.0, 1810.0), (1050.0, 1970.0),
                (1650.0, 2040.0), (1730.0, 2300.0), (300.0, 2600.0), (230.0, 2860.0), (1650.0, 3160.0),
                (1730.0, 3420.0), (1000.0, 3500.0), (850.0, 3660.0), (300.0, 3720.0), (230.0, 3980.0),
                (900.0, 4050.0), (1050.0, 4210.0), (1650.0, 4280.0), (1730.0, 4540.0), (300.0, 4840.0),
                (230.0, 5100.0), (1650.0, 5400.0), (1730.0, 5660.0), (1000.0, 5740.0), (850.0, 5900.0),
                (300.0, 5960.0), (230.0, 6220.0), (1100.0, 6480.0), (1100.0, 6780.0), (1100.0, 6920.0),
            ],
            None,
            "RALLY",
        ),
        1920.0,
        7200.0,
    ),
    sprint(
        city(
            "rushhour",
            "Rush Hour",
            "Rob the bank, lose the cops in the alley and the car park.",
            &[
                (200.0, 300.0), (200.0, 1100.0), (1200.0, 1100.0), (1200.0, 1700.0), (1750.0, 1700.0),
                (2550.0, 1700.0), (2550.0, 1960.0), (1950.0, 1960.0), (1950.0, 2220.0), (2650.0, 2220.0),
                (3050.0, 2220.0), (3050.0, 3000.0), (1700.0, 3000.0), (1100.0, 3000.0), (1100.0, 3480.0),
                (3450.0, 3480.0), (3450.0, 2850.0), (3450.0, 2700.0),
            ],
            Some("cop"),
            "COP",
        ),
        3840.0,
        3840.0,
    ),
    sprint(
        city(
            "rooftop",
            "Rooftop Run",
            "Skyscraper roofs and narrow sky bridges. Don't look down.",
            &[
                (200.0, 300.0), (200.0, 900.0), (700.0, 900.0), (1100.0, 900.0), (1500.0, 900.0),
                (1500.0, 1400.0), (2100.0, 1400.0), (2500.0, 1400.0), (2900.0, 1400.0), (3300.0, 1000.0),
                (3700.0, 1000.0), (4100.0, 1000.0), (4500.0, 1000.0), (4500.0, 500.0), (5000.0, 500.0),
                (5400.0, 500.0), (5800.0, 500.0), (5800.0, 1200.0), (6300.0, 1200.0), (6650.0, 1200.0),
                (6900.0, 1200.0), (7050.0, 1200.0),
            ],
            None,
            "RIVAL",
        ),
        7200.0,
        1800.0,
    ),
    surface(
        sprint(
            city(
                "moon",
                "Moon Base",
                "Low grip, big craters, and a rocket waiting at the end.",
                &[
                    (200.0, 300.0), (200.0, 1100.0), (1100.0, 1100.0), (1700.0, 1800.0), (1700.0, 2700.0),
                    (2900.0, 2700.0), (3500.0, 2000.0), (3500.0, 900.0), (4400.0, 900.0), (4900.0, 1500.0),
                    (4900.0, 2550.0), (4900.0, 2700.0),
                ],
                Some("rover"),
                "ROVER",
            ),
            5400.0,
            3240.0,
        ),
        0.45,
        0.7,
    ),
    // battle arenas: last car driving wins
    arena(city("junkyard", "Junkyard", "Scrap piles and a wall of crushed cars. Ram everyone.", ARENA_LOOP, None, "WRECKER")),
    arena(city("stadium", "Stadium", "A packed dirt bowl. The crowd wants carnage.", ARENA_LOOP, None, "WRECKER")),
    surface(
        arena(city("rink", "Ice Rink", "No grip at all. Slide into people at full speed.", ARENA_LOOP, None, "WRECKER")),
        0.35,
        0.65,
    ),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_unique_and_lookup_falls_back_to_classic() {
        for (i, m) in ALL.iter().enumerate() {
            assert_eq!(index_of(m.id), Some(i));
        }
        assert_eq!(get("nowhere").id, "classic");
    }

    #[test]
    fn grid_matches_the_original_layout() {
        // slot 0: image corner (165, 276) -> center (170, 286)
        assert_eq!(grid_slot(0).pos, Vec2::new(170.0, 286.0));
        // slot 4: row 1, middle column, set back 12
        assert_eq!(grid_slot(4).pos, Vec2::new(206.0, 244.0));
    }
}

/// the packed road mask of a map (data/masks/<id>.bin, made by `cargo run -p tools -- pack-masks`)
pub fn mask_bin(id: &str) -> &'static [u8] {
    macro_rules! masks {
        ($($id:literal),*) => {
            match id {
                $($id => include_bytes!(concat!("../../../data/masks/", $id, ".bin")),)*
                _ => include_bytes!("../../../data/masks/classic.bin"),
            }
        };
    }
    masks!(
        "classic", "sf", "tokyo", "nyc", "chicago", "london", "paris", "taipei", "summit", "rushhour",
        "rooftop", "moon", "junkyard", "stadium", "rink"
    )
}

/// the road and walls of a map
pub fn track(map: &MapDef) -> crate::track::Track {
    crate::track::Track::for_map(map, mask_bin(map.id)).expect("bundled masks are valid")
}
