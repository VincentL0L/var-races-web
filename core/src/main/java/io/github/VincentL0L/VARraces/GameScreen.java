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
import com.badlogic.gdx.audio.Music;
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
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Game screen that keeps track of the leaderboard that network client recieves from gameserver
 * and displays the racetrack, starting countdown, and finish message
 */
public class GameScreen implements Screen {
    private Stage stage;
    private Stage uiStage;
    private Music bgm;
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
        rm = new RaceManager(map.waypoints);
        trackWatch = new RaceManager(map.waypoints);
        nc.setRaceManager(rm);

        OrthographicCamera cam = new OrthographicCamera();
        camControl = new CameraController(cam);
        stage = new Stage(new ExtendViewport(675, 360, cam));
        uiStage = new Stage(Ui.viewport());
        Gdx.input.setInputProcessor(uiStage);

        bgm = Gdx.audio.newMusic(Gdx.files.internal("idle.mp3"));
        bgm.setLooping(true);
        bgm.play();

        player = new Player(stage, car, map);
        player.setInputEnabled(false);
        player.getImage().setPosition(x, y);
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
            bgm.stop();
            bgm.dispose();
            bgm = Gdx.audio.newMusic(Gdx.files.internal("background.mp3"));
            bgm.setLooping(true);
            bgm.play();
            start = true;
        }

        if (!done && rm.isFinished(nc.getPlayerId())) {
            done = true;
            end = 0f;
            finish.setVisible(true);
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
                game.setScreen(new EndScreen(game, car, pos, lead, map));
                return;
            }
        }

        // controls work while racing and not paused
        player.setInputEnabled(start && !done && !paused);
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
                Image actor = new Image(oppSkins[skinFor(id) - 1]);
                actor.setSize(player.getWidth(), player.getHeight());
                actor.setOrigin(actor.getWidth() / 2, actor.getHeight() / 2);
                stage.addActor(actor);
                state = new OpponentState(actor, new Vector2(pose[0], pose[1]));
                nwOpp.put(id, state);
            }
            // single player: exact positions every frame; online: smoothly interpolated
            state.img.setPosition(pose[0], pose[1]);
            // CPUs report their driving direction, players report their sprite rotation
            state.img.setRotation(id.startsWith("CPU") ? pose[2] - 90 : pose[2]);
        }

        // bump into the other cars (they're drawn 10x20 like the player, so their image is their body)
        if (!frozen) {
            for (OpponentState other : nwOpp.values()) {
                player.collideWith(other.img.getX(), other.img.getY(), other.img.getRotation() + 90f);
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
        stage.act(dt);
        stage.draw();
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
        if (start && !done && !frozen) {
            trackWatch.updateRacer(nc.getPlayerId(), new Vector2(player.getX(), player.getY()), raceClock);
        }
        over.setOffTrack(start && !done && trackWatch.distanceFromTrack(nc.getPlayerId()) > OFF_TRACK_DISTANCE);
        over.setPaused(paused);
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
                setPaused(!paused);
            } else if (row == 1) {
                restart();
            } else if (row == 2) {
                quit();
            }
        }
    }

    private void setPaused(boolean value) {
        paused = value;
        pausePanel.setVisible(paused);
        if (paused) {
            bgm.pause();
        } else {
            bgm.play();
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
    private int skinFor(String id) {
        if (id.startsWith("CPU") && id.length() == 4) {
            return 3 + MathUtils.clamp(id.charAt(3) - '0', 1, 3);
        }
        return MathUtils.clamp(nc.getOpponentCar(id), 1, 3);
    }

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
        if (touch != null) {
            touch.dispose();
        }
        if (startFlag != null) {
            startFlag.dispose();
        }
        pauseSkin.dispose();
        over.dispose();
        stage.dispose();
        for (Texture t : oppSkins) {
            t.dispose();
        }
        bgm.dispose();
        nc.stop();
    }

    // abstract methods that arent used but have to be declared
    public void show() {}

    public void pause() {}

    public void resume() {}
};


