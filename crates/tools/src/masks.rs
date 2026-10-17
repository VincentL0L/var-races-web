//! Packs every map's road_mask.png into the 1-bit run-length form the game loads
//! (see sim::track::Mask). A pixel is road when red, green and blue are all >= 0.85.

use std::fs;
use std::path::Path;

use sim::track::Mask;

/// 0.85 of 255, rounded up: the road threshold the game has always used
const ROAD: u8 = 217;

pub fn pack_all(root: &Path) -> Result<(), String> {
    let out = root.join("data/masks");
    fs::create_dir_all(&out).map_err(|e| e.to_string())?;
    for map in sim::maps::ALL.iter() {
        let src = root.join(format!("assets/maps/{}/road_mask.png", map.id));
        let mask = read_mask(&src)?;
        let bin = mask.encode();
        fs::write(out.join(format!("{}.bin", map.id)), &bin).map_err(|e| e.to_string())?;
        println!("{:<10} {:>4} x {:<4} {:>6} bytes", map.id, mask.width, mask.height, bin.len());
    }
    Ok(())
}

pub fn read_mask(path: &Path) -> Result<Mask, String> {
    let img = read_rgba(path)?;
    Ok(Mask::from_fn(img.width, img.height, |x, y| {
        let p = &img.pixels[(y * img.width + x) * 4..][..3];
        p.iter().all(|&c| c >= ROAD)
    }))
}

pub struct Rgba {
    pub width: usize,
    pub height: usize,
    pub pixels: Vec<u8>,
}

/// any 8-bit PNG, as RGBA rows top to bottom
pub fn read_rgba(path: &Path) -> Result<Rgba, String> {
    let file = fs::File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut decoder = png::Decoder::new(file);
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().map_err(|e| format!("{}: {e}", path.display()))?;
    let mut buf = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).map_err(|e| format!("{}: {e}", path.display()))?;
    let (w, h) = (info.width as usize, info.height as usize);
    let channels = info.color_type.samples();
    let mut pixels = Vec::with_capacity(w * h * 4);
    for px in buf[..w * h * channels].chunks(channels) {
        match channels {
            1 => pixels.extend_from_slice(&[px[0], px[0], px[0], 255]),
            2 => pixels.extend_from_slice(&[px[0], px[0], px[0], px[1]]),
            3 => pixels.extend_from_slice(&[px[0], px[1], px[2], 255]),
            _ => pixels.extend_from_slice(&px[..4]),
        }
    }
    Ok(Rgba { width: w, height: h, pixels })
}
