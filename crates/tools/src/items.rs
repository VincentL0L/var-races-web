//! Pixel art for the items: the item box on the track, an icon for each item (shown in the HUD),
//! and the things you see on the track (rocket, oil, the shield bubble).

use std::path::Path;

use crate::image::Picture;

/// name, letters, the letters' colours (0xrrggbb), 16 rows of 16
type Icon = (&'static str, &'static str, &'static [u32], [&'static str; 16]);

const ICONS: [Icon; 8] = [
    (
        "box",
        "kyYowr",
        &[0x2a1d17, 0xf2b83a, 0xffe08a, 0xb57a1c, 0xffffff, 0xd9433b],
        [
            "..kkkkkkkkkkkk..",
            ".kYYYYYYYYYYYYk.",
            "kYyyyyyyyyyyyyok",
            "kYyyyywwwwyyyyok",
            "kYyyywwyywwyyyok",
            "kYyyyyyyywwyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyyyyyyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyywwyyyyok",
            "kYyyyyyyyyyyyyok",
            "kYyyyyyyyyyyyyok",
            "kYoooooooooooook",
            ".kooooooooooook.",
            "..kkkkkkkkkkkk..",
        ],
    ),
    (
        "nitro",
        "kbBwroy",
        &[0x1c1c22, 0x2f6fd0, 0x7fb6ff, 0xffffff, 0xd9433b, 0xf08a2e, 0xffe066],
        [
            "......kkkk......",
            ".....kwwwwk.....",
            "....kkbbbbkk....",
            "...kBbbbbbbbk...",
            "...kBbwwwwbbk...",
            "...kBbwbbwbbk...",
            "...kBbwwwwbbk...",
            "...kBbbbbbbbk...",
            "...kBbbbbbbbk...",
            "...kBbwbbwbbk...",
            "...kBbbbbbbbk...",
            "...kkbbbbbbkk...",
            "....kkkkkkkk....",
            ".....yooooy.....",
            "......orro......",
            ".......rr.......",
        ],
    ),
    (
        "rocket",
        "krRwsy",
        &[0x1c1c22, 0xd9433b, 0xff8a7a, 0xffffff, 0x8a5a36, 0xffe066],
        [
            ".......kk.......",
            "......kwwk......",
            ".....kwwwwk.....",
            ".....kRrrrk.....",
            ".....kRrrrk.....",
            ".....kRwwrk.....",
            ".....kRrrrk.....",
            ".....kRrrrk.....",
            ".....kRwwrk.....",
            ".....kRrrrk.....",
            "....kkkrrkkk....",
            "...kr.ks.k.rk...",
            ".......s........",
            ".......s........",
            "......ysy.......",
            ".....y.y.y......",
        ],
    ),
    (
        "oil",
        "kdgpcy",
        &[0x0e0e12, 0x24242c, 0x3a3a48, 0xb57cff, 0x3fe0ff, 0xffe066],
        [
            "................",
            "....kkkkkk......",
            "..kkddddddkk....",
            ".kdddggdddddkk..",
            ".kddgppgdddddk..",
            "kdddgccgddgddk..",
            "kdddddyddgpgdk..",
            "kddddddddgcgddk.",
            ".kdddggddddddddk",
            ".kddgpcgdddddddk",
            "..kddgyddddddkk.",
            "...kkddddddkk...",
            ".....kkkkkk.....",
            "................",
            "................",
            "................",
        ],
    ),
    (
        "bubble",
        "kbBw",
        &[0x1f4f7f, 0x7fc8ff, 0xc8ecff, 0xffffff],
        [
            ".....kkkkkk.....",
            "...kkBBBBBBkk...",
            "..kBwwBBBBBBBk..",
            ".kBwwBBBBBBBBBk.",
            ".kBwBBBBBBBBBBk.",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBBbk",
            "kBBBBBBBBBBBBbbk",
            "kBBBBBBBBBBBBbbk",
            ".kBBBBBBBBBBbbk.",
            ".kbBBBBBBBBbbbk.",
            "..kbbBBBBbbbbk..",
            "...kkbbbbbbkk...",
            ".....kkkkkk.....",
        ],
    ),
    (
        "pulse",
        "kyYc",
        &[0x1c1c22, 0xffd23f, 0xfff4a8, 0x3fe0ff],
        [
            "...cc......cc...",
            "..c..........c..",
            ".c.....kkkk...c.",
            "c.....kYyyk....c",
            "c....kYyyk.....c",
            ".....kYyyk......",
            "....kYyyykkkk...",
            "....kYyyyyyyk...",
            "...kkkkYyyyk....",
            "......kYyyk.....",
            "c....kYyyk.....c",
            "c....kYyk......c",
            ".c...kYk......c.",
            "..c..kk......c..",
            "...cc......cc...",
            "................",
        ],
    ),
    (
        "frost",
        "kbBw",
        &[0x1f3f7f, 0x5fb0f0, 0xb8e6ff, 0xffffff],
        [
            ".......kk.......",
            "......kwBk......",
            "..kk..kBbk..kk..",
            "..kBk.kBbk.kBk..",
            "...kBkkBbkkBk...",
            "....kBBwBBBk....",
            "kkkkkBwwwBbkkkkk",
            "kBBBBwwwwwBBBBbk",
            "kkkkkBwwwBbkkkkk",
            "....kBBwBBBk....",
            "...kBkkBbkkBk...",
            "..kBk.kBbk.kBk..",
            "..kk..kBbk..kk..",
            "......kBbk......",
            ".......kk.......",
            "................",
        ],
    ),
    (
        "fire",
        "kroyw",
        &[0x5a1408, 0xd9332a, 0xf08a2e, 0xffd23f, 0xfff6d0],
        [
            "......k.........",
            ".....kr...k.....",
            "....kro..kr.....",
            "...kroo.kro.k...",
            "...kroookrookr..",
            "..kroooooooookr.",
            "..krooyyyyoook..",
            ".krooyyyyyyook..",
            ".kroyyywwyyyok..",
            ".kroyywwwwyyok..",
            ".kroyywwwwyyok..",
            ".krooyywwyyook..",
            "..krooyyyyook...",
            "...krroooork....",
            "....kkrrrrk.....",
            "......kkkk......",
        ],
    ),
];

pub fn draw_all(root: &Path) -> Result<(), String> {
    let dir = root.join("assets/ui/items");
    for (name, letters, colors, rows) in ICONS {
        let mut img = Picture::new(16, 16);
        for (y, row) in rows.iter().enumerate() {
            for (x, ch) in row.chars().take(16).enumerate() {
                img.set(x, y, letters.find(ch).map_or(0, |i| 0xff00_0000 | colors[i]));
            }
        }
        img.save(dir.join(format!("{name}.png")), true)?;
        if name == "box" {
            // Cubes need an opaque square face, unlike the rounded HUD icon.
            for (y, row) in rows.iter().enumerate() {
                for (x, ch) in row.chars().take(16).enumerate() {
                    if ch == '.' { img.set(x, y, 0xfff2_b83a); }
                }
            }
            img.save(dir.join("box_face.png"), true)?;
        }
    }
    // the shield ring drawn around a protected car (32 x 32)
    let mut ring = Picture::new(32, 32);
    for y in 0..32 {
        for x in 0..32 {
            let d = (x as f64 - 15.5).hypot(y as f64 - 15.5);
            if d < 15.5 {
                let (mut alpha, mut c) = if d > 13.5 { (220, 0x7fc8ff) } else { ((60.0 * d / 13.5) as u32, 0xc8ecff) };
                if x < 12 && y < 12 && d > 9.0 && d < 12.0 {
                    alpha = 200;
                    c = 0xffffff;
                }
                ring.set(x, y, alpha << 24 | c);
            }
        }
    }
    ring.save(dir.join("shield.png"), true)?;
    println!("items drawn");
    Ok(())
}
