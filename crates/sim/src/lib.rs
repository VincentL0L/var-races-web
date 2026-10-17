//! The VAR Races simulation. Pure game logic with no I/O: the server runs it natively, the
//! browser runs the same code compiled to WebAssembly (single player and prediction).

pub mod maps;
pub mod math;
pub mod rng;
pub mod track;
pub mod car;
pub mod race;
pub mod ai;
pub mod battle;
pub mod events;
pub mod items;
pub mod room;
pub mod soccer;
