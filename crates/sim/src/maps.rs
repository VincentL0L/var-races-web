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

/// a big circuit, stored at a third of its size like the sprints (with laps as usual)
const fn big(mut map: MapDef, width: f32, height: f32) -> MapDef {
    map.width = width;
    map.height = height;
    map.scale = 3;
    map
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
pub static ALL: [MapDef; 17] = [
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
    // a proper racing circuit: the centre line of tools/MakeGrandPrix.java (in the Java game)
    big(
        city(
            "gp",
            "Grand Prix",
            "A real racing circuit: esses, a hairpin, kerbs and gravel traps, against F1 cars.",
            &[
                (192.0, 395.0), (195.0, 490.0), (198.0, 580.0), (200.0, 674.0), (200.0, 765.0), (199.0, 856.0),
                (198.0, 950.0), (197.0, 1044.0), (197.0, 1135.0), (198.0, 1229.0), (201.0, 1321.0), (204.0, 1416.0),
                (207.0, 1511.0), (214.0, 1607.0), (227.0, 1700.0), (253.0, 1789.0), (291.0, 1876.0), (316.0, 1918.0),
                (344.0, 1956.0), (376.0, 1991.0), (411.0, 2022.0), (448.0, 2050.0), (527.0, 2095.0), (616.0, 2130.0),
                (707.0, 2153.0), (754.0, 2160.0), (802.0, 2161.0), (895.0, 2153.0), (987.0, 2131.0), (1033.0, 2114.0),
                (1076.0, 2094.0), (1115.0, 2070.0), (1149.0, 2037.0), (1179.0, 1998.0), (1228.0, 1917.0), (1255.0, 1875.0),
                (1284.0, 1837.0), (1352.0, 1772.0), (1416.0, 1719.0), (1458.0, 1692.0), (1500.0, 1672.0), (1544.0, 1661.0),
                (1593.0, 1660.0), (1639.0, 1667.0), (1685.0, 1681.0), (1768.0, 1717.0), (1850.0, 1760.0), (1892.0, 1787.0),
                (1964.0, 1847.0), (2033.0, 1908.0), (2107.0, 1965.0), (2191.0, 2010.0), (2274.0, 2046.0), (2361.0, 2078.0),
                (2451.0, 2102.0), (2544.0, 2119.0), (2636.0, 2126.0), (2730.0, 2123.0), (2822.0, 2111.0), (2911.0, 2092.0),
                (3002.0, 2061.0), (3087.0, 2022.0), (3168.0, 1975.0), (3235.0, 1924.0), (3269.0, 1892.0), (3299.0, 1857.0),
                (3350.0, 1777.0), (3389.0, 1690.0), (3409.0, 1619.0), (3418.0, 1572.0), (3421.0, 1526.0), (3419.0, 1476.0),
                (3412.0, 1426.0), (3402.0, 1382.0), (3386.0, 1333.0), (3367.0, 1288.0), (3343.0, 1248.0), (3315.0, 1212.0),
                (3279.0, 1179.0), (3237.0, 1150.0), (3197.0, 1128.0), (3156.0, 1109.0), (3108.0, 1093.0), (3061.0, 1082.0),
                (3015.0, 1078.0), (2968.0, 1081.0), (2878.0, 1103.0), (2790.0, 1135.0), (2733.0, 1157.0), (2652.0, 1207.0),
                (2579.0, 1259.0), (2537.0, 1283.0), (2490.0, 1299.0), (2445.0, 1300.0), (2396.0, 1293.0), (2348.0, 1277.0),
                (2307.0, 1259.0), (2263.0, 1233.0), (2224.0, 1204.0), (2190.0, 1171.0), (2158.0, 1132.0), (2133.0, 1094.0),
                (2111.0, 1054.0), (2092.0, 1007.0), (2081.0, 961.0), (2080.0, 915.0), (2086.0, 866.0), (2097.0, 822.0),
                (2114.0, 779.0), (2134.0, 739.0), (2164.0, 698.0), (2196.0, 666.0), (2237.0, 637.0), (2279.0, 616.0),
                (2368.0, 584.0), (2460.0, 564.0), (2552.0, 553.0), (2643.0, 553.0), (2738.0, 557.0), (2831.0, 561.0),
                (2927.0, 559.0), (3020.0, 555.0), (3115.0, 548.0), (3185.0, 538.0), (3231.0, 526.0), (3277.0, 508.0),
                (3320.0, 483.0), (3358.0, 453.0), (3392.0, 416.0), (3416.0, 374.0), (3423.0, 327.0), (3415.0, 280.0),
                (3395.0, 240.0), (3362.0, 203.0), (3323.0, 176.0), (3278.0, 155.0), (3190.0, 126.0), (3097.0, 108.0),
                (3000.0, 95.0), (2908.0, 87.0), (2815.0, 83.0), (2725.0, 81.0), (2629.0, 80.0), (2535.0, 81.0),
                (2444.0, 81.0), (2352.0, 82.0), (2260.0, 82.0), (2167.0, 82.0), (2074.0, 82.0), (1984.0, 81.0),
                (1891.0, 81.0), (1797.0, 81.0), (1703.0, 82.0), (1609.0, 82.0), (1517.0, 82.0), (1423.0, 82.0),
                (1332.0, 82.0), (1238.0, 82.0), (1142.0, 82.0), (1047.0, 82.0), (955.0, 82.0), (861.0, 82.0),
                (768.0, 82.0), (673.0, 82.0), (579.0, 82.0), (486.0, 82.0), (394.0, 82.0), (299.0, 89.0),
                (254.0, 85.0), (213.0, 111.0), (199.0, 157.0), (191.0, 248.0), (200.0, 300.0),
            ],
            Some("f1"),
            "F1",
        ),
        3600.0,
        2304.0,
    ),
    // the Monaco street circuit: the centre line of tools/monaco.mjs
    big(
        city(
            "monaco",
            "Monaco",
            "The street circuit: barriers at the edge, the hairpin, the tunnel and the harbour, against F1 cars.",
            &[(201.0, 385.0), (199.0, 468.0), (194.0, 549.0), (187.0, 631.0), (179.0, 715.0), (171.0, 798.0), (159.0, 879.0), (148.0, 963.0), (144.0, 1049.0), (143.0, 1134.0), (139.0, 1219.0), (134.0, 1302.0), (138.0, 1383.0), (148.0, 1468.0), (161.0, 1548.0), (172.0, 1591.0), (189.0, 1633.0), (209.0, 1669.0), (234.0, 1706.0), (269.0, 1732.0), (308.0, 1743.0), (392.0, 1754.0), (471.0, 1765.0), (551.0, 1775.0), (636.0, 1789.0), (720.0, 1807.0), (798.0, 1829.0), (875.0, 1851.0), (954.0, 1873.0), (1038.0, 1891.0), (1122.0, 1908.0), (1188.0, 1925.0), (1265.0, 1957.0), (1339.0, 1992.0), (1419.0, 2018.0), (1497.0, 2038.0), (1577.0, 2057.0), (1656.0, 2073.0), (1740.0, 2084.0), (1807.0, 2093.0), (1850.0, 2102.0), (1891.0, 2119.0), (1930.0, 2143.0), (1960.0, 2171.0), (1984.0, 2203.0), (2004.0, 2242.0), (2015.0, 2284.0), (2020.0, 2328.0), (2020.0, 2370.0), (2014.0, 2413.0), (2001.0, 2454.0), (1982.0, 2491.0), (1953.0, 2524.0), (1893.0, 2584.0), (1868.0, 2616.0), (1844.0, 2651.0), (1839.0, 2694.0), (1854.0, 2735.0), (1878.0, 2776.0), (1926.0, 2846.0), (1978.0, 2911.0), (2027.0, 2976.0), (2073.0, 3044.0), (2118.0, 3112.0), (2168.0, 3181.0), (2205.0, 3227.0), (2237.0, 3260.0), (2272.0, 3287.0), (2313.0, 3278.0), (2337.0, 3243.0), (2339.0, 3198.0), (2337.0, 3153.0), (2343.0, 3109.0), (2356.0, 3067.0), (2374.0, 3029.0), (2397.0, 2992.0), (2428.0, 2960.0), (2459.0, 2933.0), (2496.0, 2914.0), (2538.0, 2920.0), (2572.0, 2941.0), (2593.0, 2979.0), (2586.0, 3025.0), (2573.0, 3066.0), (2539.0, 3125.0), (2519.0, 3165.0), (2514.0, 3208.0), (2534.0, 3245.0), (2572.0, 3263.0), (2615.0, 3277.0), (2655.0, 3288.0), (2696.0, 3290.0), (2732.0, 3269.0), (2752.0, 3233.0), (2754.0, 3189.0), (2742.0, 3105.0), (2731.0, 3021.0), (2724.0, 2935.0), (2716.0, 2854.0), (2706.0, 2771.0), (2697.0, 2723.0), (2686.0, 2682.0), (2671.0, 2640.0), (2633.0, 2567.0), (2599.0, 2490.0), (2568.0, 2413.0), (2552.0, 2376.0), (2532.0, 2341.0), (2504.0, 2309.0), (2439.0, 2258.0), (2375.0, 2209.0), (2304.0, 2160.0), (2230.0, 2118.0), (2156.0, 2081.0), (2081.0, 2045.0), (2007.0, 2010.0), (1933.0, 1976.0), (1855.0, 1944.0), (1778.0, 1917.0), (1698.0, 1891.0), (1619.0, 1870.0), (1537.0, 1854.0), (1457.0, 1840.0), (1415.0, 1833.0), (1374.0, 1822.0), (1339.0, 1801.0), (1324.0, 1759.0), (1302.0, 1725.0), (1270.0, 1701.0), (1228.0, 1703.0), (1148.0, 1701.0), (1062.0, 1693.0), (980.0, 1683.0), (898.0, 1673.0), (816.0, 1663.0), (737.0, 1650.0), (656.0, 1633.0), (577.0, 1614.0), (532.0, 1600.0), (492.0, 1584.0), (455.0, 1564.0), (422.0, 1538.0), (391.0, 1505.0), (365.0, 1470.0), (330.0, 1392.0), (311.0, 1354.0), (296.0, 1315.0), (290.0, 1272.0), (289.0, 1229.0), (292.0, 1184.0), (300.0, 1142.0), (320.0, 1107.0), (360.0, 1070.0), (389.0, 1039.0), (406.0, 1001.0), (425.0, 922.0), (441.0, 841.0), (454.0, 772.0), (459.0, 732.0), (458.0, 687.0), (446.0, 645.0), (415.0, 578.0), (396.0, 540.0), (394.0, 500.0), (400.0, 457.0), (413.0, 415.0), (437.0, 359.0), (457.0, 320.0), (481.0, 285.0), (538.0, 225.0), (565.0, 194.0), (589.0, 157.0), (599.0, 115.0), (571.0, 84.0), (530.0, 68.0), (489.0, 66.0), (406.0, 71.0), (322.0, 77.0), (278.0, 84.0), (238.0, 98.0), (203.0, 120.0), (199.0, 161.0), (199.0, 245.0), (200.0, 300.0)],
            Some("f1"),
            "F1",
        ),
        2880.0,
        3456.0,
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
        "classic", "sf", "tokyo", "nyc", "chicago", "london", "paris", "taipei", "gp", "monaco", "summit", "rushhour",
        "rooftop", "moon", "junkyard", "stadium", "rink"
    )
}

/// the road and walls of a map
pub fn track(map: &MapDef) -> crate::track::Track {
    crate::track::Track::for_map(map, mask_bin(map.id)).expect("bundled masks are valid")
}
