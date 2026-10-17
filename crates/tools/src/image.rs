//! Writing pictures: 32-bit ARGB pixels (Java's int colours) to PNG.

use std::fs;
use std::path::Path;

/// a picture as 0xAARRGGBB pixels, rows top to bottom
pub struct Picture {
    pub width: usize,
    pub height: usize,
    pub pixels: Vec<u32>,
}

impl Picture {
    pub fn new(width: usize, height: usize) -> Picture {
        Picture { width, height, pixels: vec![0; width * height] }
    }

    pub fn set(&mut self, x: usize, y: usize, argb: u32) {
        self.pixels[y * self.width + x] = argb;
    }

    pub fn get(&self, x: usize, y: usize) -> u32 {
        self.pixels[y * self.width + x]
    }

    /// saves as PNG; `alpha` false drops the alpha channel (an RGB picture)
    pub fn save(&self, path: impl AsRef<Path>, alpha: bool) -> Result<(), String> {
        let path = path.as_ref();
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        }
        let file = fs::File::create(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let mut enc = png::Encoder::new(std::io::BufWriter::new(file), self.width as u32, self.height as u32);
        enc.set_color(if alpha { png::ColorType::Rgba } else { png::ColorType::Rgb });
        enc.set_depth(png::BitDepth::Eight);
        enc.set_compression(png::Compression::Best);
        let mut data = Vec::with_capacity(self.pixels.len() * 4);
        for &p in &self.pixels {
            data.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, p as u8]);
            if alpha {
                data.push((p >> 24) as u8);
            }
        }
        let mut w = enc.write_header().map_err(|e| e.to_string())?;
        w.write_image_data(&data).map_err(|e| e.to_string())
    }

    /// a smaller copy, each pixel the average of a block (alpha dropped)
    pub fn shrink(&self, w: usize, h: usize) -> Picture {
        let (fx, fy) = (self.width / w, self.height / h);
        let mut out = Picture::new(w, h);
        for y in 0..h {
            for x in 0..w {
                let (mut r, mut g, mut b) = (0u64, 0u64, 0u64);
                for j in 0..fy {
                    for i in 0..fx {
                        let c = self.get(x * fx + i, y * fy + j);
                        r += (c >> 16 & 255) as u64;
                        g += (c >> 8 & 255) as u64;
                        b += (c & 255) as u64;
                    }
                }
                let n = (fx * fy) as u64;
                out.set(x, y, 0xff00_0000 | ((r / n) as u32) << 16 | ((g / n) as u32) << 8 | (b / n) as u32);
            }
        }
        out
    }
}

/// a colour from 0xrrggbb, opaque
pub const fn rgb(hex: u32) -> u32 {
    0xff00_0000 | hex
}

/// mixes two colours: t = 0 gives a, 1 gives b (always opaque, like the original)
pub fn blend(a: u32, b: u32, t: f64) -> u32 {
    let r = (((a >> 16) & 255) as f64 * (1.0 - t) + ((b >> 16) & 255) as f64 * t) as i32 as u32;
    let g = (((a >> 8) & 255) as f64 * (1.0 - t) + ((b >> 8) & 255) as f64 * t) as i32 as u32;
    let bl = ((a & 255) as f64 * (1.0 - t) + (b & 255) as f64 * t) as i32 as u32;
    0xff00_0000 | r << 16 | g << 8 | bl
}
