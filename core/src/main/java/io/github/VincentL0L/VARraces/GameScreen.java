package io.github.VincentL0L.VARraces;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;
import io.github.VincentL0L.VARraces.Multiplayer.packets.PositionPacket;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Game screen that keeps track of the leaderboard that network client recieves from gameserver
 * and displays the racetrack, starting countdown, and finish message
 */
public class GameScreen implements Screen {
    private Stage stage;
    private Stage uiStage;
    private Map<String, OpponentState> nwOpp = new HashMap<>();
    private RaceManager rm;
    private Game game;
    private int car;
    public NetworkClient nc;
    private final StartLights lights = new StartLights();
    private final TouchControls touch = Ui.touchScreen ? new TouchControls() : null;
    /** the red VAR RACES flag from the lobby, still waving off when the race screen opens */
    private StartFlag startFlag;
    private Label finish;
    private boolean start = false;
    private boolean done = false;
    private Texture[] oppSkins;
    private final float[] pose = new float[3];
    private Background bg;
    private CameraController camControl;
    private Overlay over;
    private Player player;
    private float end = 0f;
    /** stopwatch: seconds since GO */
    private float raceClock = 0f;
    private boolean paused = false;
    private Table pausePanel;
    private Skin pauseSkin;
    private final FrostedBackdrop frost = new FrostedBackdrop();
    private float frostAmount = 0f;
    /** follows only our own car, for the RETURN TO TRACK warning */
    private final RaceManager trackWatch;
    private final TrackMap map;
    private boolean keepClient = false;
    private ItemRenderer itemArt;
    private final com.badlogic.gdx.graphics.g2d.SpriteBatch worldBatch = new com.badlogic.gdx.graphics.g2d.SpriteBatch();
    private final Map<String, Vector2> carCenters = new HashMap<>();
    /** how far from the track counts as off it (the road is about 125 wide) */
    private static final float OFF_TRACK_DISTANCE = 95f;

    /**
     * Creates a GameScreen class with parameters, initializes fields, loads sounds
     * @param g game
     * @param c car number
     * @param n network client
     * @param x start position x
     * @param y start position y
     */
    public GameScreen(Game g, int c, NetworkClient n, float x, float y) {
        this(g, c, n, x, y, null);
    }

    /**
     * @param flag the start flag still waving off the screen, drawn on top until it's gone (or null)
     */
    public GameScreen(Game g, int c, NetworkClient n, float x, float y, StartFlag flag) {
        startFlag = flag;
        game = g;
        car = c;
        nc = n;
        map = nc.getMap();
        rm = RaceManager.forMap(map);
        rm.setLaps(nc.getLaps());
        trackWatch = RaceManager.forMap(map);
        // it only watches where we are on the track, so it must never think we've finished
        trackWatch.setLaps(1000);
        nc.setRaceManager(rm);

        OrthographicCamera cam = new OrthographicCamera();
        camControl = new CameraController(cam);
        stage = new Stage(new ExtendViewport(675, 360, cam));
        uiStage = new Stage(Ui.viewport());
        Sounds.clickSounds(uiStage);
        Gdx.input.setInputProcessor(uiStage);

        // the menus' music fades out while everyone lines up on the grid
        Sounds.stopMusic();

        player = new Player(stage, car, map);
        player.setInputEnabled(false);
        player.getImage().setPosition(x, y);
        // arenas: everyone starts facing the middle
        player.getImage().setRotation(nc.getStartHeading() - 90f);
        // start with the camera already on our car in its grid slot
        cam.position.set(x + player.getWidth() / 2f, y + player.getHeight() / 2f, 0);
        cam.update();

        BitmapFont font = Ui.displayOutlined(15);
        
        finish = new Label("FINISH!", new LabelStyle(font, Color.YELLOW));
        finish.setFontScale(Ui.fontScale(4f));
        finish.setAlignment(Align.center);
        finish.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
        finish.setVisible(false);

        uiStage.addActor(finish);


        bg = new Background(stage, map);

        // car1-3 are the skins players pick, car4-6 are CPU1-CPU3
        // on some maps the CPUs all drive the same car (San Francisco: Waymos)
        oppSkins = new Texture[6];
        for (int i = 0; i < oppSkins.length; i++) {
            String file = i >= 3 && map.cpuSprite != null ? map.cpuSprite : "ui/car" + (i + 1) + ".png";
            oppSkins[i] = new Texture(Gdx.files.internal(file));
            oppSkins[i].setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }

        over = new Overlay(rm, map);
        itemArt = new ItemRenderer(map);
        com.badlogic.gdx.graphics.Texture[] icons = new com.badlogic.gdx.graphics.Texture[
            io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.Item.values().length];
        for (io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.Item item
                : io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.Item.values()) {
            icons[item.ordinal()] = itemArt.icon(item);
        }
        over.setItemIcons(icons);
        over.setOnline(nc.isOnline());
        pauseSkin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        pausePanel = buildPausePanel();
        uiStage.addActor(pausePanel);
        over.setPlayerId(nc.getPlayerId());
    }

    /**
     * renders countdown overlay if game hasn't started, finish overlay 
     * when game ends, all of the cars, and the background
     * @param delta time between last render
     */
    public void render(float delta) {
        handleMenuInput();
        // single player freezes completely while paused; online the race goes on without us
        boolean frozen = paused && !nc.isOnline();
        float dt = frozen ? 0f : delta;

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (!frozen) {
            nc.update(delta);
        }
        String cdtxt = nc.getCountdownText();

        // everyone waits on the grid until GO!, then the racing music starts
        if (!start && cdtxt.equals("GO!")) {
            Sounds.music(map.battle ? Sounds.BATTLE : Sounds.RACE, true);
            start = true;
        }

        if (!done && rm.isFinished(nc.getPlayerId())) {
            done = true;
            player.stopEngine();
            Sounds.stopMusic();
            Sounds.play("finish", 0.6f);
            end = 0f;
            if (map.battle) {
                // the battle's over: did we win?
                List<RacerInfo> order = rm.getSortedLeaderboard();
                boolean won = !order.isEmpty() && order.get(0).name.equals(nc.getPlayerId());
                finish.setText(won ? "WINNER!" : "BATTLE OVER");
                finish.pack();
                finish.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
            }
            finish.setVisible(true);
        }
        // knocked out of a battle: the car's a wreck until the battle ends
        boolean wrecked = map.battle && nc.isKnockedOut(nc.getPlayerId());
        if (wrecked && !done && !finish.isVisible()) {
            finish.setText("KNOCKED OUT");
            finish.pack();
            finish.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
            finish.setColor(Color.SALMON);
            finish.setVisible(true);
            player.getImage().setColor(0.35f, 0.33f, 0.32f, 1f);
        }

        if (done) {
            end += dt;
            if (end >= 3f) {
                int pos = 1;
                String id = nc.getPlayerId();
                List<RacerInfo> lead = rm.getSortedLeaderboard();

                for (RacerInfo racer : lead) {
                    if (racer.name.equals(id)) {
                        break;
                    }
                    pos++;
                }
                // a ranked race keeps its connection open until the rating change comes back
                keepClient = nc.isRanked();
                game.setScreen(new EndScreen(game, car, pos, lead, map, keepClient ? nc : null));
                return;
            }
        }

        // items: what happened to our car, and using the one we hold (E, or the ITEM button)
        if (!frozen) {
            for (String effect : nc.takeMyEffects()) {
                if (effect.equals("BOOST")) {
                    player.nitro();
                } else if (effect.equals("SPIN")) {
                    player.spinOut();
                } else if (effect.equals("SLOW")) {
                    player.slowDown(io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.SLOW_TIME);
                    Sounds.play("pulse", 0.5f, 0.8f);
                } else if (effect.equals("FREEZE")) {
                    player.freeze(io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.FREEZE_TIME);
                } else if (effect.equals("BURN")) {
                    player.burn(io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.BURN_TIME);
                } else if (effect.equals("BLOCK")) {
                    Sounds.play("block", 0.6f);
                } else if (effect.equals("DMG")) {
                    over.flashDamage();
                    camControl.shake(6f);
                    Sounds.play("hit", 0.6f, MathUtils.random(0.9f, 1.1f));
                } else if (effect.equals("OUT")) {
                    Sounds.play("knockout", 0.8f);
                }
            }
            // other cars knocked out in a battle go up with a bang too (quieter)
            for (String out : nc.takeKnockouts()) {
                if (!out.equals(nc.getPlayerId())) {
                    Sounds.play("knockout", 0.4f, 1.15f);
                }
            }
        }
        boolean useItem = Gdx.input.isKeyJustPressed(Input.Keys.E) || (touch != null && touch.itemTapped());
        if (useItem && start && !done && !paused && !player.isSpinning()) {
            playItemSound(nc.getHeldItem());
            nc.useItem();
        }
        over.setItem(nc.getHeldItem() == null ? null : itemArt.icon(nc.getHeldItem()),
            nc.getHeldItem() == null ? null : nc.getHeldItem().label);
        over.setItemAmmo(nc.getHeldItem() == io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.Item.FROST || nc.getHeldItem() == io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.Item.FIRE ? nc.getHeldAmmo() : 0);
        if (touch != null) {
            touch.setItem(nc.getHeldItem() == null ? null : itemArt.icon(nc.getHeldItem()));
        }

        // controls work while racing and not paused
        player.setInputEnabled(start && !done && !paused && !player.isSpinning() && !player.isFrozen() && !wrecked);
        if (touch == null) {
            player.setTouchInput(false, 0f, false, false, false, false);
        }
        if (!frozen) {
            player.render(delta);
        }
        camControl.update(player.getX() + player.getWidth() / 2f,
        player.getY() + player.getHeight() / 2f, player.getRotation() + 90f, delta);

        if (!frozen) {
            nc.sendPos(player.getX(), player.getY(), player.getRotation());
        }

        Map<String, PositionPacket> opp = nc.getOpponents();
        discOpp(opp);

        for (Map.Entry<String, PositionPacket> entry : opp.entrySet()) {
            String id = entry.getKey();

            if (id.equals(nc.getPlayerId())){
                continue;
            }

            if (!nc.getOpponentPose(id, pose)) {
                continue;
            }
            OpponentState state = nwOpp.get(id);
            if (state == null) {
                Image actor = new Image(skinFor(id));
                actor.setSize(player.getWidth(), player.getHeight());
                actor.setOrigin(actor.getWidth() / 2, actor.getHeight() / 2);
                stage.addActor(actor);
                state = new OpponentState(actor, new Vector2(pose[0], pose[1]));
                nwOpp.put(id, state);
            }
            // how fast it's going (smoothed), so bumps know who's shoving whom
            if (delta > 0f) {
                float vx = (pose[0] - state.img.getX()) / delta, vy = (pose[1] - state.img.getY()) / delta;
                if (Math.abs(vx) < 1500f && Math.abs(vy) < 1500f) {
                    state.velocity.lerp(new Vector2(vx, vy), Math.min(1f, delta * 12f));
                }
            }
            // single player: exact positions every frame; online: smoothly interpolated
            state.img.setPosition(pose[0], pose[1]);
            // CPUs report their driving direction, players report their sprite rotation
            state.img.setRotation(id.startsWith("CPU") ? pose[2] - 90 : pose[2]);
        }

        // bump into the other cars (they're drawn 10x20 like the player, so their image is their body)
        if (!frozen) {
            for (Map.Entry<String, OpponentState> e : nwOpp.entrySet()) {
                OpponentState other = e.getValue();
                // CPUs weigh the standard amount; other players weigh what their car does
                float mass = e.getKey().startsWith("CPU") ? 1f : CarModel.of(nc.getOpponentCar(e.getKey())).mass;
                player.collideWith(other.img.getX(), other.img.getY(), other.img.getRotation() + 90f,
                    other.velocity.x, other.velocity.y, mass);
            }
        }

        // the track frosts over while paused, and after you cross the finish line
        float frostTarget = paused ? 1f : done ? 0.85f : 0f;
        frostAmount += MathUtils.clamp(frostTarget - frostAmount, -delta * 4f, delta * 3f);
        boolean frosted = frostAmount > 0.001f;
        if (frosted) {
            frost.begin();
        }
        bg.render(stage.getCamera());
        // item boxes and oil under the cars; rockets and shields over them
        worldBatch.setProjectionMatrix(stage.getCamera().combined);
        worldBatch.begin();
        itemArt.drawGround(worldBatch, nc.getItemView(), dt);
        worldBatch.end();
        stage.act(dt);
        stage.draw();
        carCenters.clear();
        carCenters.put(nc.getPlayerId(), new Vector2(player.getX() + player.getWidth() / 2f, player.getY() + player.getHeight() / 2f));
        for (Map.Entry<String, OpponentState> e : nwOpp.entrySet()) {
            Image img = e.getValue().img;
            carCenters.put(e.getKey(), new Vector2(img.getX() + img.getWidth() / 2f, img.getY() + img.getHeight() / 2f));
        }
        worldBatch.begin();
        itemArt.drawAir(worldBatch, nc.getItemView(), carCenters);
        worldBatch.end();
        if (map.battle) {
            drawHealthBars();
            drawOpponentArrows();
        }
        if (frosted) {
            frost.end();
            frost.draw(frostAmount);
        }

        // stopwatch: runs from GO; once you finish it shows your official finish time
        if (start && !done && !frozen) {
            raceClock += delta;
        }
        RacerInfo me = rm.getRacerInfoByName(nc.getPlayerId());
        over.setRaceTime(me != null && me.isFinished() ? me.finishTime : raceClock);
        // RETURN TO TRACK: track our own car's place on the course; far from the stretch
        // we should be on means we're out on the grass or skipped part of the track
        if (start && !done && !frozen && !map.battle) {
            trackWatch.updateRacer(nc.getPlayerId(), new Vector2(player.getX(), player.getY()), raceClock);
        }
        over.setOffTrack(start && !done && !map.battle && trackWatch.distanceFromTrack(nc.getPlayerId()) > OFF_TRACK_DISTANCE);
        over.setPaused(paused);
        over.setCars(carCenters);
        over.render(player);
        lights.render(cdtxt, frozen ? 0f : delta);
        if (touch != null) {
            touch.update(player, !paused && !done);
        }

        // countdown / FINISH! / pause panel on top of everything
        uiStage.act(delta);
        uiStage.getViewport().apply();
        uiStage.draw();

        if (startFlag != null) {
            startFlag.render(delta);
            if (startFlag.isDone()) {
                startFlag.dispose();
                startFlag = null;
            }
        }
    }

    /** the sound of using an item (the Nitro's whoosh plays when the boost arrives) */
    private static void playItemSound(io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem.Item item) {
        if (item == null) {
            return;
        }
        switch (item) {
            case ROCKET: Sounds.play("rocket", 0.6f); break;
            case OIL: Sounds.play("oil", 0.6f); break;
            case BUBBLE: Sounds.play("shield", 0.55f); break;
            case PULSE: Sounds.play("pulse", 0.6f); break;
            case FROST: Sounds.play("frost_shot", 0.5f, MathUtils.random(0.95f, 1.08f)); break;
            case FIRE: Sounds.play("fire_shot", 0.5f, MathUtils.random(0.95f, 1.08f)); break;
            default: break;
        }
    }

    private com.badlogic.gdx.graphics.glutils.ShapeRenderer bars;

    /** a little health bar over every car in a battle (wrecks get none) */
    private void drawHealthBars() {
        if (bars == null) {
            bars = new com.badlogic.gdx.graphics.glutils.ShapeRenderer();
        }
        // drawn on the screen (not the rotating map) so the bars always sit level above the car
        bars.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.gl.glEnable(GL20.GL_BLEND);
        com.badlogic.gdx.math.Vector3 p = new com.badlogic.gdx.math.Vector3();
        float px = Gdx.graphics.getBackBufferWidth() / (float) Math.max(1, Gdx.graphics.getWidth());
        bars.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        for (RacerInfo r : rm.getRacers()) {
            Vector2 c = carCenters.get(r.name);
            if (c == null || r.progress < io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE) {
                continue;
            }
            float hp = MathUtils.clamp((r.progress - io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE) / 100f, 0f, 1f);
            p.set(c.x, c.y, 0f);
            stage.getCamera().project(p, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            float u = Ui.density / px;
            float w = 34f * u, h = 5f * u, x = p.x / px - w / 2f, y = p.y / px + 22f * u;
            bars.setColor(0.1f, 0.07f, 0.05f, 0.85f);
            bars.rect(x - u, y - u, w + 2f * u, h + 2f * u);
            bars.setColor(hp > 0.5f ? 0.35f : hp > 0.25f ? 0.95f : 0.95f, hp > 0.5f ? 0.85f : hp > 0.25f ? 0.75f : 0.3f, 0.3f, 1f);
            bars.rect(x, y, w * hp, h);
        }
        bars.end();
    }

    /**
     * battle: an arrow at the screen's edge pointing at every opponent that's off screen
     * (still in), so you can always find someone to ram
     */
    private void drawOpponentArrows() {
        if (bars == null) {
            return;
        }
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float px = Gdx.graphics.getBackBufferWidth() / (float) Math.max(1, w);
        float u = Ui.density / px;
        // the arrows stay inside a box clear of the HUD across the top
        float margin = 46f * u, top = h - Math.min(h * 0.45f, 290f * u);
        float cx = w / 2f, cy = (margin + top) / 2f, halfW = cx - margin, halfH = (top - margin) / 2f;
        com.badlogic.gdx.math.Vector3 p = new com.badlogic.gdx.math.Vector3();
        bars.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        bars.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
        for (RacerInfo r : rm.getRacers()) {
            Vector2 c = carCenters.get(r.name);
            if (c == null || r.name.equals(nc.getPlayerId())
                    || r.progress < io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE) {
                continue;
            }
            p.set(c.x, c.y, 0f);
            stage.getCamera().project(p, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            float sx = p.x / px, sy = p.y / px;
            if (sx > margin && sx < w - margin && sy > margin && sy < top) {
                continue;     // on screen: no arrow needed
            }
            // pin it to the edge along the line from the middle of the screen
            float dx = sx - cx, dy = sy - cy;
            float t = Math.min(halfW / Math.max(1e-3f, Math.abs(dx)), halfH / Math.max(1e-3f, Math.abs(dy)));
            float ax = cx + dx * t, ay = cy + dy * t;
            float len = (float) Math.hypot(dx, dy), nx = dx / len, ny = dy / len;
            float size = 26f * u;
            // closer opponents get bigger, brighter arrows
            float near = MathUtils.clamp(1f - (len - w * 0.5f) / (w * 1.5f), 0.45f, 1f);
            bars.setColor(0.1f, 0.07f, 0.05f, 0.85f);
            triangle(ax, ay, nx, ny, size * near + 3f * u);
            bars.setColor(0.95f, 0.3f + 0.4f * (1f - near), 0.2f, 0.95f);
            triangle(ax, ay, nx, ny, size * near);
        }
        bars.end();
    }

    private void triangle(float x, float y, float nx, float ny, float s) {
        bars.triangle(x + nx * s, y + ny * s, x - nx * s * 0.6f - ny * s * 0.8f, y - ny * s * 0.6f + nx * s * 0.8f,
            x - nx * s * 0.6f + ny * s * 0.8f, y - ny * s * 0.6f - nx * s * 0.8f);
    }

    /**
     * Esc pauses / resumes; R restarts and Q quits while paused; the menu panel's rows can be clicked
     */
    private void handleMenuInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.P)) {
            setPaused(!paused);
        } else if (paused && Gdx.input.isKeyJustPressed(Input.Keys.R)) {
            restart();
        } else if (paused && Gdx.input.isKeyJustPressed(Input.Keys.Q)) {
            quit();
        } else if (Gdx.input.justTouched()) {
            int row = over.menuRowAt(Gdx.input.getX(), Gdx.input.getY());
            if (row == 0) {
                setPaused(!paused);        // the little pause button opens the pause menu
            } else if (row == 1 && start && !done && !paused && !player.isSpinning()) {
                playItemSound(nc.getHeldItem());
                nc.useItem();              // tapping the item panel uses the item
            }
        }
    }

    private void setPaused(boolean value) {
        paused = value;
        pausePanel.setVisible(paused);
        Sounds.pauseMusic(paused);
        if (paused) {
            player.stopEngine();
        }
    }

    /**
     * single player: back to a fresh lobby; online: leave the race
     */
    private void restart() {
        if (nc.isOnline()) {
            quit();
        } else {
            game.setScreen(new LobbyScreen(game, car, null, map));
        }
    }

    private void quit() {
        game.setScreen(new MenuScreen(game, car));
    }

    /**
     * the centered pause panel: RESUME / RESTART / MAIN MENU, hidden until paused
     */
    private Table buildPausePanel() {
        Table panel = new Table();
        panel.setBackground(Ui.panel());
        panel.pad(24, 44, 30, 44);
        panel.add(new Label("PAUSED", new LabelStyle(Ui.display(40), Ui.GOLD))).padBottom(6).row();
        String note = nc.isOnline() ? "The race keeps going online" : "Esc to resume";
        panel.add(new Label(note, new LabelStyle(Ui.font(12), Ui.CREAM))).padBottom(18).row();

        TextButton resume = new TextButton("Resume", pauseSkin);
        resume.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                setPaused(false);
            }
        });
        TextButton again = new TextButton(nc.isOnline() ? "Leave Race" : "Restart", pauseSkin);
        again.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                restart();
            }
        });
        TextButton menu = new TextButton("Main Menu", pauseSkin);
        menu.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                quit();
            }
        });
        panel.add(resume).width(240).height(56).pad(6).row();
        panel.add(again).width(240).height(56).pad(6).row();
        panel.add(menu).width(240).height(56).pad(6).row();

        Table holder = new Table();
        holder.setFillParent(true);
        holder.add(panel);
        holder.setVisible(false);
        return holder;
    }

    /**
     * @param id opponent id
     * @return which car sprite to draw: CPU1-3 use cars 4-6, players use the car they picked
     */
    private Texture skinFor(String id) {
        if (id.startsWith("CPU") && id.length() == 4) {
            return oppSkins[2 + MathUtils.clamp(id.charAt(3) - '0', 1, 3)];
        }
        // other players: the car they picked in the garage (loaded once per car)
        int car = nc.getOpponentCar(id);
        Texture t = playerSkins.get(car);
        if (t == null) {
            t = new Texture(Gdx.files.internal(CarModel.sprite(car)));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            playerSkins.put(car, t);
        }
        return t;
    }

    private final Map<Integer, Texture> playerSkins = new HashMap<>();

    /**
     * removes all opponents aren't part of the race anymore
     * @param curOpp packets sent by GameServer of current opponents
     */
    private void discOpp(Map<String, PositionPacket> curOpp) {
        List<String> remove = new ArrayList<>();
        for (String id : nwOpp.keySet()) {
            if (!curOpp.containsKey(id)) {
                remove.add(id);
            }
        }
        for (String id : remove) {
            nwOpp.remove(id).img.remove();
        }
    }

    /**
     * resizes stage
     * @param width new screen width
     * @param height new screen height
     */
    public void resize(int width, int height) {
        // false: keep the camera on the car instead of jumping to the middle of the map
        stage.getViewport().update(width, height, false);
        uiStage.getViewport().update(width, height, true);
        finish.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
    }

    /**
     * automatically called by LibGDX when switching screens, since 
     * there is no need to go back to GameScreen we can remove memory
     */
    public void hide() {
        dispose();
    }


    /**
     * clears memory
     */
    public void dispose() {
        frost.dispose();
        lights.dispose();
        if (bars != null) {
            bars.dispose();
        }
        itemArt.dispose();
        worldBatch.dispose();
        if (touch != null) {
            touch.dispose();
        }
        if (startFlag != null) {
            startFlag.dispose();
        }
        pauseSkin.dispose();
        over.dispose();
        stage.dispose();
        for (Texture t : playerSkins.values()) {
            t.dispose();
        }
        for (Texture t : oppSkins) {
            t.dispose();
        }
        player.stopEngine();
        if (!keepClient) {
            nc.stop();
        }
    }

    // abstract methods that arent used but have to be declared
    public void show() {}

    public void pause() {}

    public void resume() {}
};


