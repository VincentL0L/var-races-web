# VAR-RACES

A [libGDX](https://libgdx.com/) project generated with [gdx-liftoff](https://github.com/libgdx/gdx-liftoff).

This project was generated with a template including simple application launchers and an `ApplicationAdapter` extension that draws a simple GUI on the screen.

## Platforms

- `core`: Main module with the application logic shared by all platforms.
- `lwjgl3`: Primary desktop platform using LWJGL3; was called 'desktop' in older docs.

## Gradle

This project uses [Gradle](https://gradle.org/) to manage dependencies.
The Gradle wrapper was included, so you can run Gradle tasks using `gradlew.bat` or `./gradlew` commands.
Useful Gradle tasks and flags:

- `--continue`: when using this flag, errors will not stop the tasks from running.
- `--daemon`: thanks to this flag, Gradle daemon will be used to run chosen tasks.
- `--offline`: when using this flag, cached dependency archives will be used.
- `--refresh-dependencies`: this flag forces validation of all dependencies. Useful for snapshot versions.
- `build`: builds sources and archives of every project.
- `cleanEclipse`: removes Eclipse project data.
- `cleanIdea`: removes IntelliJ project data.
- `clean`: removes `build` folders, which store compiled classes and built archives.
- `eclipse`: generates Eclipse project data.
- `idea`: generates IntelliJ project data.
- `lwjgl3:jar`: builds application's runnable jar, which can be found at `lwjgl3/build/libs`.
- `lwjgl3:run`: starts the application.
- `test`: runs unit tests (if any).

Note that most tasks that are not specific to a single project can be run with `name:` prefix, where the `name` should be replaced with the ID of a specific project.
For example, `core:clean` removes `build` folder only from the `core` project.

## Web version and online multiplayer

This copy adds:

- `teavm/` compiles the game to JavaScript for the browser (hosted on Firebase).
- `server/` is the online race server (`RaceServer`, one `Room` per race) that
  replaced the KryoNet `GameServer`. Players connect over WebSockets, so both the
  browser and desktop versions can race each other.
- Single Player runs the same race logic locally against 3 CPU cars (`NetworkClient`).
- Race courses have raised track walls and yellow chevron boards at turns in the
  first-person view. The overhead view shows the same collision boundary as a yellow
  curb. `V` switches views during a race; battle arenas keep their existing layout.
- Multiplayer lists public races, creates public or private races, or joins one with a 4-letter code.
  The host can switch **Bots** off in the lobby for a players-only race; the setting
  is shared with everyone joining the room and locks when the countdown starts.
  Ranked races keep their fixed bot settings.
- **Race Car Soccer** is a separate, first-person-only online mode. Create a 1v1 or
  2v2 room from the main menu and share its five-letter room code. Matches start when
  all 2 or 4 players join, with balanced blue/orange teams. Drive with WASD/arrows,
  boost with Shift, and jump with Space (touch controls are also provided).
  The server simulates the cars and bouncing ball, validates goals, and broadcasts
  one shared score. First to five goals wins, or the leader after three minutes;
  ties use sudden-death overtime. The host can start a rematch after the result.
  A disconnect returns the room to waiting for a replacement.
  Gold pads refill 25 boost (small pads) or the whole tank (corner pads). Drive
  over colored cubes to activate a power-up automatically: cyan **Nitro** gives
  six seconds of extra speed without spending boost, pink **Power Shot** strengthens
  your next ball hit within eight seconds, and purple **Magnet** pulls a nearby
  ball toward your front bumper for six seconds. Pads respawn after 4/10 seconds,
  power-ups after 12; their rings show recharge progress. Pickups, effects and
  cooldowns are server-controlled and shared by all players, with fresh pickups
  and cleared effects at each kickoff.
- Sharp text on Retina screens (`Ui`). Titles use the game's own racing font drawn by
  `tools/make_speed_font.py`; small text uses Michroma (SIL OFL, see `tools/`).
- Gilded 16-bit buttons and panels (`tools/make_ui.py`).
- A pixel-art speedometer (`tools/make_speedometer.py`).

Commands:

- Local preview (JDK 17+, Python 3, Node/npm): `npm run dev`. This builds and tests
  the updated game, starts the race server, and serves the website at
  `http://localhost:8000`. Press Ctrl+C to stop both servers. Optional settings
  in `.env.local`: `JAVA_HOME`, `GRADLE_BIN`, `WEB_PORT` (8000), and `PORT` (8080).

- Play locally: `./gradlew server:run`, then `./gradlew teavm:buildJavaScript` and serve
  `teavm/build/dist/webapp` (on localhost the game uses `ws://localhost:8080`)
- Desktop: `./gradlew lwjgl3:run` (add `-Dvarraces.server=wss://...` to use another server)
- Deploy the website: `firebase deploy --only hosting`
- Deploy the server: Render builds `Dockerfile` (see `render.yaml`)
- Test soccer multiplayer against a running local server (Node 22+):
  `node tools/test_soccer_server.mjs ws://localhost:8080`. This plays a complete
  1v1 match through normal inputs, checks goal synchronization, rematches and
  disconnects, then checks a four-player 2v2 match.
- Live at https://var-races.web.app (any page accepts `?server=wss://host` to pick a server)
