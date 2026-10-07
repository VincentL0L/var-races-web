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
- Multiplayer lists public races, creates public or private races, or joins one with a 4-letter code.
- Sharp text on Retina screens (`Ui`), using Racing Sans One and Michroma (both SIL OFL, see `tools/`).
- A pixel-art speedometer (`tools/make_speedometer.py`).

Commands:

- Play locally: `./gradlew server:run`, then `./gradlew teavm:buildJavaScript` and serve
  `teavm/build/dist/webapp` (on localhost the game uses `ws://localhost:8080`)
- Desktop: `./gradlew lwjgl3:run` (add `-Dvarraces.server=wss://...` to use another server)
- Deploy the website: `firebase deploy --only hosting`
- Deploy the server: Render builds `Dockerfile` (see `render.yaml`)
- Live at https://var-races.web.app (any page accepts `?server=wss://host` to pick a server)
