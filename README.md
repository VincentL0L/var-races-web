# VAR Races

A 16-bit arcade racer for the browser, with an overhead and a first-person view.
It has circuits in eight cities, point-to-point sprints, battle arenas with items, and Race Car Soccer.
Play single player against CPU cars, or online in public, private and ranked races.

Live at https://var-races.web.app (any page accepts `?server=wss://host` to pick another server).

## How it fits together

The whole game runs on one simulation written in Rust.
The server runs it natively, and the browser runs the same code compiled to WebAssembly.

```
crates/sim      the game: car physics, CPU drivers, items, battle, soccer, races and rooms
                (deterministic, fixed 60 Hz tick, no I/O; maps and road masks are built in)
crates/proto    the binary wire protocol (postcard), shared by server and browser
crates/server   the online server: tokio + axum WebSockets, one task per room, accounts and ranked
crates/wasm     the simulation for the browser (wasm-bindgen)
crates/tools    asset generators: maps, cars, items, heights, audio, packed road masks
web/            the client: TypeScript, three.js for the world, HTML/CSS for menus and HUD
assets/         source art, fonts and sound (made by the generators and tools/*.py)
data/masks      every map's road mask, packed to 1 bit a pixel (made from assets/maps)
```

- **Single player** runs the server's own room code in the browser, CPU cars included.
- **Online**, the server is authoritative: clients send inputs, never positions.
  Each client predicts its own car with the shared physics and reconciles against the server's snapshots.
  Other cars and the ball are interpolated through an adaptive jitter buffer.
- **Ranked** races sign in with Firebase.
  The server verifies the ID token and keeps Elo ratings in Firestore.

## Playing locally

You need:
- Rust with the `wasm32-unknown-unknown` target (`rustup target add wasm32-unknown-unknown`).
- `wasm-bindgen-cli` at the version in `Cargo.lock` (`cargo install wasm-bindgen-cli --version 0.2.129 --locked`).
- Node 22 or newer, then `npm install`.
- Optionally, `wasm-opt` (binaryen) for a smaller WebAssembly build.

Then run `npm run dev` and open the address it prints.
It builds what's missing or out of date, starts the server on port 8080, and serves the client with Vite on port 8000.
On localhost the client finds the local server by itself.
Set `PORT` and `WEB_PORT` to use other ports.
After changing Rust code, stop it and run it again.

## Controls

| | Race | Soccer |
|---|---|---|
| Drive | WASD or arrow keys | WASD or arrow keys |
| Boost | Space | Shift |
| Item | E | |
| Jump | | Space |
| Switch view | V | |
| Pause, then restart or quit | Esc or P, then R or Q | Esc leaves |
| Mute | M | M |

Phones and tablets get touch controls.
Double-tap and hold gas to boost, and hold brake to reverse.

## Commands

| Command | What it does |
|---|---|
| `npm run dev` | play locally (see above) |
| `npm run build` | the production client in `web/dist` |
| `npm run typecheck` | type-check the client |
| `cargo test --workspace` | simulation, protocol and server tests (the server tests play whole online games) |
| `npm run wasm` | build the simulation for the browser into `web/src/wasm/pkg` |
| `npm run assets` | convert `assets/` for the web (WebP, hashed URLs) into `web/public/gen` |
| `node tools/e2e.mjs` | with `npm run dev` running: two players race online, then play soccer, in Chrome |
| `node tools/shot.mjs <url> <out.png>` | a screenshot of the game in headless Chrome, for checking by eye |

## Remaking assets

The maps, cars, item icons, first-person heights and all the sound are generated, not drawn by hand.
The generators are deterministic, so running one again reproduces the committed files.
Run them from the repository root with `cargo run -p tools --release -- <step>`:

| Step | Makes |
|---|---|
| `cities` | Tokyo, New York, Chicago, London, Paris and Taipei, and their CPU cars |
| `sf` | San Francisco, the Waymo, and the classic map's preview |
| `sprints` | Summit Sprint, Rush Hour, Rooftop Run, Moon Base and the rover |
| `arenas` | the Junkyard, Stadium and Ice Rink battle arenas |
| `garage` | the garage cars (`assets/ui/cars`) |
| `items` | the item icons (`assets/ui/items`) |
| `heights` | each map's trees and buildings for the first-person view (`heights.png`) |
| `audio` | the music and every sound effect (needs `lame` for the MP3 music) |
| `pack-masks` | `data/masks/*.bin` from each map's `road_mask.png`; run it after changing a map |

The Python tools make the rest:
`make_ui.py` draws the gilded panels, `make_speedometer.py` the speedometer, and `make_cars.py` the player and CPU car sprites.
`make_speed_font.py` and `make_font.py` make the two web fonts.
The Python tools need Pillow, and the fonts also need fonttools, brotli and shapely.

## Deploying

- **Website:** run `npm run build`, then `firebase deploy --only hosting`.
  Hashed files under `/assets` and `/gen` are cached for a year, and the page itself is always revalidated.
- **Server:** Render builds the `Dockerfile` (see `render.yaml`).
  This gives a static binary in an empty image of a few megabytes, which starts in milliseconds.
  It reads `PORT`, `FIREBASE_SERVICE_ACCOUNT` (the service account JSON; without it, ranked is off) and `RUST_LOG`.

## Credits

Michroma is by The Michroma Project Authors, under the SIL Open Font License (see `tools/Michroma-OFL.txt`).
