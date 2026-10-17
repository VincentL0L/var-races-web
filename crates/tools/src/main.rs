//! Asset build steps and generators. Run from the repository root:
//!
//!     cargo run -p tools --release -- <step> [output root, default .]
//!
//! Steps:
//!   audio        composes the music and synthesises every sound (needs lame for the MP3s)
//!   pack-masks   assets/maps/*/road_mask.png -> data/masks/*.bin (what the game loads)
//!   cities       paints Tokyo, New York, Chicago, London, Paris, Taipei and their CPU cars
//!   sf           paints San Francisco and the Waymo, and the classic map's preview
//!   arenas       paints the Junkyard, Stadium and Ice Rink battle arenas
//!   garage       draws the garage cars (assets/ui/cars)
//!   heights      works out each map's trees and buildings (heights.png), for first person
//!   items        draws the item icons (assets/ui/items)
//!   sprints      paints Summit Sprint, Rush Hour, Rooftop Run, Moon Base and the rover

use std::path::PathBuf;
use std::process::ExitCode;

mod arenas;
mod audio;
mod cities;
mod garage;
mod heights;
mod items;
mod image;
mod jrand;
mod masks;
mod sf;
mod sprints;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let root = PathBuf::from(args.get(1).map_or(".", String::as_str));
    let result = match args.first().map(String::as_str) {
        Some("audio") => audio::make_all(&root),
        Some("pack-masks") => masks::pack_all(&root),
        Some("cities") => cities::paint_all(&root),
        Some("sf") => sf::paint(&root),
        Some("arenas") => arenas::paint_all(&root),
        Some("garage") => garage::draw_all(&root),
        Some("items") => items::draw_all(&root),
        Some("heights") => heights::make_all(&root),
        Some("sprints") => sprints::paint_all(&root),
        _ => Err("usage: tools <audio | pack-masks | cities | sf | sprints | arenas | garage | items | heights> [output root]".to_string()),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e}");
            ExitCode::FAILURE
        }
    }
}
