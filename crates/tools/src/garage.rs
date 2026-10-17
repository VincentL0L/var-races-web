//! Pixel art for the garage: five car classes (see sim::car::MODELS), three paint jobs each.
//! Every car is 14 x 28 (nose up), drawn as its left half and mirrored so it's symmetric.
//!
//! Letters: o outline, w tire, y headlight, r taillight, L light paint, B paint, D dark paint,
//! s stripe, g glass, h glass shine, i interior (seats), G spoiler, k black trim, . empty

use std::path::Path;

use crate::image::Picture;

const SPRINTER: [&str; 28] = [
    ".......",
    ".......",
    ".......",
    "....ooo",
    "..oyyLL",
    ".oLLLLL",
    "woLBBBB",
    "woLBBBB",
    "woLBBBB",
    ".oLBBBB",
    ".oDgggg",
    ".oDgggh",
    ".oDgggg",
    ".oDBBBB",
    ".oDLLLL",
    ".oDLLLL",
    ".oDLLLL",
    ".oDBBBB",
    ".oDgggg",
    ".oDgggg",
    "woDBBBB",
    "woLBBBB",
    "woLBBBB",
    ".oDDDDD",
    ".orrDDD",
    "..ooooo",
    ".......",
    ".......",
];

const ROADSTER: [&str; 28] = [
    ".......",
    "....ooo",
    "..oyyLL",
    ".oLLLLL",
    ".oLBBBB",
    "woLBBBB",
    "woLBBBB",
    "woLBBBB",
    ".oLBBBB",
    ".oLBBBB",
    ".oDgggg",
    ".oDkkkk",
    ".oDiiik",
    ".oDiiik",
    ".oDiiik",
    ".oDkkkk",
    ".oDBBBB",
    ".oDBBBB",
    ".oDBBBB",
    "woDBBBB",
    "woLBBBB",
    "woLBBBB",
    "woLBBBB",
    ".oLBBBB",
    ".oDDDDD",
    ".orrDDD",
    "..ooooo",
    ".......",
];

const GT: [&str; 28] = [
    "....ooo",
    "..oyyLs",
    ".oLLLLs",
    ".oLBBBs",
    ".oLBBBs",
    "woLBBBs",
    "woLBBBs",
    "woLBBBs",
    ".oLBBBs",
    ".oLBBBs",
    ".oLBBBs",
    ".oDgggg",
    ".oDgggh",
    ".oDgggg",
    ".oDLLLs",
    ".oDLLLs",
    ".oDgggg",
    ".oDBBBs",
    "woDBBBs",
    "woLBBBs",
    "woLBBBs",
    "woLBBBs",
    ".oDBBBs",
    ".oDDDDs",
    "GGGGGGG",
    ".oGGGGG",
    ".orrDDD",
    "..ooooo",
];

const MUSCLE: [&str; 28] = [
    "...oooo",
    "..oyyLL",
    ".oLLLss",
    "woLBBss",
    "woLBBss",
    "woLBkss",
    "woLBkss",
    ".oLBBss",
    ".oLBBss",
    ".oDgggg",
    ".oDgggh",
    ".oDgggg",
    ".oDLLss",
    ".oDLLss",
    ".oDLLss",
    ".oDLLss",
    ".oDgggg",
    ".oDgggg",
    ".oDBBss",
    "woDBBss",
    "woLBBss",
    "woLBBss",
    "woLBBss",
    ".oDBBss",
    ".oDDDss",
    ".orrrDD",
    "..ooooo",
    ".......",
];

const HAULER: [&str; 28] = [
    "..ooooo",
    ".oyyLLL",
    "woLLLLL",
    "woLBBBB",
    "woLBBBB",
    "woLBBBB",
    ".oLBBBB",
    ".oDgggg",
    ".oDgggh",
    ".oDLLLL",
    ".oDLLLL",
    ".oDLLLL",
    ".oDgggg",
    ".okkkkk",
    ".oDkiii",
    ".oDkDDD",
    ".oDkiii",
    ".oDkDDD",
    "woDkiii",
    "woDkDDD",
    "woDkiii",
    "woDkDDD",
    ".oDkiii",
    ".oDkkkk",
    ".oDDDDD",
    ".orrDDD",
    ".oooooo",
    ".......",
];

/// paint jobs per model: light, mid, dark, stripe
const PAINTS: [[[u32; 4]; 3]; 5] = [[[0x9fe7ff, 0x3fb6e8, 0x1f7aa8, 0xffffff], [0xfff2a8, 0xf2d04a, 0xb8962a, 0x2a2a30], [0xffb6d9, 0xf06aa8, 0xb03a76, 0xffffff]],
        [[0xff9a86, 0xe0402e, 0x9a2420, 0xf3ba3c], [0xf3f5f8, 0xc8ccd6, 0x8a8e9e, 0xe0402e], [0x9ff0b8, 0x3fbf6a, 0x22804a, 0xffffff]],
        [[0xe8ecf4, 0xb8bfcc, 0x7c8494, 0xf3ba3c], [0x6a7cff, 0x2f44d8, 0x1a2690, 0xffffff], [0xffb86a, 0xf0802a, 0xb04f12, 0x2a2a30]],
        [[0xffd36a, 0xf2a81c, 0xa86a0a, 0x1c1c22], [0x5a5c66, 0x2e3038, 0x18191e, 0xe0402e], [0xc8a0ff, 0x8a4ae0, 0x562a9a, 0xffffff]],
        [[0xc9a07a, 0x8a6240, 0x5a3e26, 0xf3ba3c], [0x8fc88a, 0x4f8f4a, 0x2f5a2c, 0xf3f5f8], [0xf3f5f8, 0xd8dbe2, 0x9aa0ac, 0x2f44d8]],
];

pub fn draw_all(root: &Path) -> Result<(), String> {
    let models = [SPRINTER, ROADSTER, GT, MUSCLE, HAULER];
    for (m, model) in sim::car::MODELS.iter().enumerate() {
        for (p, paint) in PAINTS[m].iter().enumerate() {
            draw(&models[m], paint).save(root.join(format!("assets/ui/cars/{}_{p}.png", model.id)), true)?;
        }
    }
    println!("garage cars drawn");
    Ok(())
}

fn draw(half: &[&str; 28], paint: &[u32; 4]) -> Picture {
    let mut img = Picture::new(14, 28);
    for (y, row) in half.iter().enumerate() {
        assert_eq!(row.len(), 7, "row {y} is {} wide: {row}", row.len());
        for (x, ch) in row.chars().enumerate() {
            img.set(x, y, color(ch, paint));
            // the right half mirrors the left, except the glass shine (one glint only)
            img.set(13 - x, y, color(if ch == 'h' { 'g' } else { ch }, paint));
        }
    }
    img
}

fn color(c: char, paint: &[u32; 4]) -> u32 {
    match c {
        'o' => 0xff2a1408,
        'w' => 0xff1c1818,
        'y' => 0xfffff0aa,
        'r' => 0xffe83428,
        'g' => 0xff26344e,
        'h' => 0xff96bee8,
        'i' => 0xff4a3426,
        'k' | 'G' => 0xff1e1e24,
        'L' => 0xff000000 | paint[0],
        'B' => 0xff000000 | paint[1],
        'D' => 0xff000000 | paint[2],
        's' => 0xff000000 | paint[3],
        _ => 0,
    }
}
