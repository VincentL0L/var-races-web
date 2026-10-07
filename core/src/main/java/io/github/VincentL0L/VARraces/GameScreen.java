package io.github.VincentL0L.VARraces;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
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
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

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
    private Label cd;
    private Label finish;
    private boolean start = false;
    private boolean done = false;
    private float goTime = 0f;
    private Texture[] oppSkins;
    private Background bg;
    private CameraController camControl;
    private Overlay over;
    private Player player;
    private float end = 0f;

    /**
     * Creates a GameScreen class with parameters, initializes fields, loads sounds
     * @param g game
     * @param c car number
     * @param n network client
     * @param x start position x
     * @param y start position y
     */
    public GameScreen(Game g, int c, NetworkClient n, float x, float y) {
        game = g;
        car = c;
        nc = n;
        rm = new RaceManager(Waypoints.getWaypoints());
        nc.setRaceManager(rm);

        OrthographicCamera cam = new OrthographicCamera();
        camControl = new CameraController(cam);
        stage = new Stage(new ExtendViewport(675, 360, cam));
        Gdx.input.setInputProcessor(stage);
        uiStage = new Stage(Ui.viewport());

        bgm = Gdx.audio.newMusic(Gdx.files.internal("idle.mp3"));
        bgm.setLooping(true);
        bgm.play();

        player = new Player(stage, car);
        player.setInputEnabled(false);
        player.getImage().setPosition(x, y);

        BitmapFont font = Ui.displayOutlined(15);
        
        cd = new Label("", new LabelStyle(font, Color.RED));
        cd.setFontScale(Ui.fontScale(3f));
        cd.setAlignment(Align.center);
        cd.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);

        finish = new Label("FINISH!", new LabelStyle(font, Color.YELLOW));
        finish.setFontScale(Ui.fontScale(4f));
        finish.setAlignment(Align.center);
        finish.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
        finish.setVisible(false);

        uiStage.addActor(finish);
        uiStage.addActor(cd);


        bg = new Background(stage);

        oppSkins = new Texture[3];
        for (int i = 0; i < 3; i++) {
            oppSkins[i] = new Texture(Gdx.files.internal("ui/car" + (i + 1) + ".png"));
        }

        over = new Overlay(rm);
    }

    /**
     * renders countdown overlay if game hasn't started, finish overlay 
     * when game ends, all of the cars, and the background
     * @param delta time between last render
     */
    public void render(float delta) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        bg.render(stage.getCamera());
        nc.update(delta);
        String cdtxt = nc.getCountdownText();
       
        if (!start) {
            if (!cdtxt.equals("GO!")) {
                cd.setText(cdtxt);
                cd.setColor(Color.RED);
                cd.setFontScale(Ui.fontScale(5f));
                cd.pack();
                cd.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
            } else {
                bgm.stop();
                bgm.dispose();
                bgm = Gdx.audio.newMusic(Gdx.files.internal("background.mp3"));
                bgm.setLooping(true);
                bgm.play();
                cd.setText("GO!");
                cd.setColor(Color.YELLOW);
                cd.setFontScale(Ui.fontScale(5f));
                cd.pack();
                cd.setPosition(Ui.width() / 2f, Ui.height() / 2f, Align.center);
                start = true;
                player.setInputEnabled(true);
                player.getImage().setPosition(200, 300);
            }
        } 
        else {
            goTime += delta;
            if (goTime >= 1f) {
                cd.remove();
            }
        }


        if (!done && rm.getLapCount(nc.getPlayerId()) >= RaceManager.LAPS) {
            done = true;
            end = 0f;
            player.setInputEnabled(false);
            finish.setVisible(true);
        }
        /*if (!done && player.getLapCount() >= 1) {
            done = true;
            end = 0f;
            player.setInputEnabled(false);
            finish.setVisible(true);
        }*/

        if (done) {
            end += delta;

            ShapeRenderer fadeIn = new ShapeRenderer();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            fadeIn.begin(ShapeRenderer.ShapeType.Filled);
            fadeIn.setColor(0, 0, 0, 0.5f * Math.min(end / 3f, 1f));
            fadeIn.rect(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            fadeIn.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            fadeIn.dispose();

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
                game.setScreen(new EndScreen(game, car, pos, lead));
                return;
            }
        }


        player.render(delta);
        camControl.update(player.getX() + player.getWidth() / 2f,
        player.getY() + player.getHeight() / 2f, player.getRotation() + 90f);

        nc.sendPos(player.getX(), player.getY(), player.getRotation());

        Map<String, PositionPacket> opp = nc.getOpponents();
        discOpp(opp);

        for (Map.Entry<String, PositionPacket> entry : opp.entrySet()) {
            String id = entry.getKey();
            PositionPacket pos = entry.getValue();
            
            if (id.equals(nc.getPlayerId())){
                continue;
            }

            OpponentState state = nwOpp.get(id);
            Vector2 newPos = new Vector2(pos.x, pos.y);

            if (state == null) {
                Image actor = new Image(oppSkins[nc.getOpponentCar(id) - 1]);
                actor.setSize(player.getWidth(), player.getHeight());
                stage.addActor(actor);
                state = new OpponentState(actor, newPos);
                nwOpp.put(id, state);
            } 
            else {
                state.lastPos.set(state.img.getX(), state.img.getY());
                state.targetPos.set(newPos);
                state.interp = 0f;
            }

            if (state.interp < 1f) {
                state.interp = Math.min(1f, state.interp + delta * 5f);
                Vector2 interpPos = state.lastPos.cpy().lerp(state.targetPos, state.interp);
                state.img.setPosition(interpPos.x, interpPos.y);
            } 
            else {
                state.img.setPosition(newPos.x, newPos.y);
            }
            state.img.setOrigin(state.img.getWidth() / 2, state.img.getHeight() / 2);
           
            if (id.substring(0, 3).equals("CPU")){
                state.img.setRotation(pos.rotation - 90);
            } 
            else{
                state.img.setRotation(pos.rotation);
            }
        }


        stage.act(delta);
        stage.draw();


        uiStage.act(delta);
        uiStage.getViewport().apply();
        uiStage.draw();
        over.render(player, camControl);
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
        stage.getViewport().update(width, height, true);
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


