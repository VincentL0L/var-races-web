package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The lobby before a race: one gilded card over the drifting, frosted track. It lists the
 * drivers (with their cars and whether they're ready) and has just two buttons, Leave and
 * Ready. Once everyone is ready the red start flag waves and the race screen takes over.
 */
public class LobbyScreen implements Screen {
    private static final int CPU_COUNT = 3;

    private final Game game;
    private final int selectedCar;
    private final NetworkClient networkClient;
    private final Stage uiStage;
    private final Skin skin;
    private final TrackBackdrop backdrop;
    private final TrackMap map;
    private final Texture[] cars = new Texture[6];
    private Label kicker;
    private Table drivers;
    private TextButton readyButton;
    private String shownDrivers = null;
    private StartFlag startFlag;

    /**
     * @param game game
     * @param selectedCar car skin 1-3
     * @param client online client already in a room, or null for single player
     * @param singlePlayerMap the map picked for single player (online the room decides)
     */
    public LobbyScreen(Game game, int selectedCar, NetworkClient client, TrackMap singlePlayerMap) {
        this.game = game;
        this.selectedCar = selectedCar;
        uiStage = new Stage(Ui.viewport());
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));

        if (client != null) {
            networkClient = client;
        } else {
            networkClient = new NetworkClient(null);
            networkClient.start(singlePlayerMap);
        }
        networkClient.setMyCar(selectedCar);
        map = networkClient.getMap();
        backdrop = new TrackBackdrop(map);
        // car1-3 are the players' skins; the CPUs drive car4-6, or the map's own car (Waymos)
        for (int i = 0; i < cars.length; i++) {
            String file = i >= 3 && map.cpuSprite != null ? map.cpuSprite : "ui/car" + (i + 1) + ".png";
            cars[i] = new Texture(Gdx.files.internal(file));
        }

        createUI();
        Gdx.input.setInputProcessor(uiStage);
    }

    /**
     * the card: kicker, title, driver list, Leave + Ready
     */
    private void createUI() {
        Table card = Cards.card();
        boolean online = networkClient.isOnline();

        kicker = Cards.kicker(online ? (networkClient.isRoomPublic() ? "PUBLIC ROOM  /  " : "PRIVATE ROOM  /  ")
            + map.name.toUpperCase() : "SINGLE PLAYER");
        card.add(kicker).left().row();
        card.add(Cards.title(online ? networkClient.getRoomCode() : map.name.toUpperCase(), 44)).left().padTop(2).padBottom(16).row();

        card.add(Cards.kicker("DRIVERS")).left().padBottom(6).row();
        drivers = new Table();
        drivers.defaults().width(Cards.CARD_WIDTH).height(Cards.ROW_HEIGHT).padBottom(6);
        card.add(drivers).row();

        // race settings: laps and how good the CPUs are (online, only the host can change them)
        card.add(Cards.kicker("RACE SETTINGS")).left().padTop(8).padBottom(6).row();
        lapsValue = Cards.text("", true);
        difficultyValue = Cards.text("", true);
        card.add(picker("LAPS", lapsValue, -1, 0)).height(Cards.ROW_HEIGHT).padBottom(6).row();
        card.add(picker("CPU DIFFICULTY", difficultyValue, 0, -1)).height(Cards.ROW_HEIGHT).row();
        settingsNote = new Label("", new Label.LabelStyle(Ui.font(9), Cards.LABEL));
        card.add(settingsNote).left().padTop(4).row();

        TextButton leave = Cards.smallButton("Leave", skin);
        leave.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) {
                if (!networkClient.isFlagShown()) {
                    networkClient.stop();
                    game.setScreen(new MenuScreen(game, selectedCar));
                }
            }
        });
        readyButton = new TextButton("Ready", skin);
        readyButton.addListener(new ClickListener() {
            public void clicked(InputEvent event, float x, float y) {
                if (!networkClient.isReady() && networkClient.isConnected()) {
                    networkClient.setReady(true);
                }
            }
        });
        Table buttons = new Table();
        buttons.add(leave).width(130).height(52);
        buttons.add().expandX();
        buttons.add(readyButton).width(220).height(60);
        card.add(buttons).padTop(14).row();

        uiStage.addActor(Cards.center(card));
    }

    private static final String[] DIFFICULTIES = {"EASY", "NORMAL", "HARD"};
    private Label lapsValue, difficultyValue, settingsNote;
    private final List<TextButton> settingButtons = new ArrayList<>();

    /**
     * one setting: a recessed row with its name, then < value >
     * @param lapsStep non-zero for the laps picker   @param difficultyStep non-zero for the CPU picker
     */
    private Table picker(String name, Label value, int lapsStep, int difficultyStep) {
        final boolean isLaps = lapsStep != 0;
        Table row = Cards.row();
        row.add(Cards.text(name, false)).left().expandX();
        TextButton less = Cards.smallButton("<", skin);
        TextButton more = Cards.smallButton(">", skin);
        less.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                step(isLaps, -1);
            }
        });
        more.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                step(isLaps, 1);
            }
        });
        settingButtons.add(less);
        settingButtons.add(more);
        row.add(less).size(38, 32);
        row.add(value).width(120).center();
        value.setAlignment(com.badlogic.gdx.utils.Align.center);
        row.add(more).size(38, 32);
        return row;
    }

    /** moves a setting one choice left or right (wrapping round) */
    private void step(boolean isLaps, int direction) {
        int laps = networkClient.getLaps(), difficulty = networkClient.getDifficulty();
        if (isLaps) {
            int[] choices = RaceManager.LAP_CHOICES;
            int i = 0;
            for (int k = 0; k < choices.length; k++) {
                if (choices[k] == laps) {
                    i = k;
                }
            }
            laps = choices[(i + direction + choices.length) % choices.length];
        } else {
            difficulty = (difficulty + direction + DIFFICULTIES.length) % DIFFICULTIES.length;
        }
        networkClient.setRaceSettings(laps, difficulty);
    }

    /** shows the current settings, and greys the arrows out for players who can't change them */
    private void refreshSettings() {
        int laps = networkClient.getLaps();
        lapsValue.setText(laps + (laps == 1 ? " LAP" : " LAPS"));
        difficultyValue.setText(DIFFICULTIES[networkClient.getDifficulty()]);
        boolean canChange = networkClient.canChangeSettings();
        for (TextButton b : settingButtons) {
            b.setDisabled(!canChange);
            b.setVisible(canChange);
        }
        // colour code the difficulty
        int d = networkClient.getDifficulty();
        difficultyValue.setColor(d == 0 ? Cards.READY : d == 2 ? Cards.ERROR : Ui.GOLD);
        settingsNote.setText(networkClient.isOnline() && !canChange && !networkClient.isFlagShown()
            ? "The host picks the laps and CPU difficulty" : "");
    }

    /**
     * rebuilds the driver rows when someone joins, leaves or readies up
     */
    private void refreshDrivers() {
        String me = networkClient.getPlayerId();
        List<String> ids = new ArrayList<>(networkClient.getPlayerReadyStates().keySet());
        StringBuilder key = new StringBuilder();
        for (String id : ids) {
            key.append(id).append(networkClient.isPlayerReady(id)).append(networkClient.getOpponentCar(id)).append(';');
        }
        if (key.toString().equals(shownDrivers)) {
            return;
        }
        shownDrivers = key.toString();
        drivers.clear();

        // you first, then everyone else, then the CPUs
        drivers.add(driverRow(skin(selectedCar), "YOU", true, networkClient.isReady() ? "READY" : null)).row();
        for (String id : ids) {
            if (!id.equals(me)) {
                drivers.add(driverRow(skin(networkClient.getOpponentCar(id)), id.toUpperCase(), false,
                    networkClient.isPlayerReady(id) ? "READY" : null)).row();
            }
        }
        if (networkClient.isOnline()) {
            // online the list can get long, so the CPUs share one row
            Table row = Cards.row();
            for (int i = 0; i < CPU_COUNT; i++) {
                row.add(Cards.carIcon(cars[3 + i])).size(13, 26).padRight(6);
            }
            row.add(Cards.text("+ " + CPU_COUNT + " " + map.cpuName + "S", false)).left().expandX().padLeft(8);
            row.add(Cards.pill(map.cpuName, Cards.CPU, true));
            drivers.add(row).row();
        } else {
            for (int i = 0; i < CPU_COUNT; i++) {
                drivers.add(driverRow(cars[3 + i], map.displayName("CPU" + (i + 1)), false, "CPU")).row();
            }
        }
    }

    private final java.util.Map<Integer, Texture> playerSkins = new java.util.HashMap<>();

    /** a player's garage car sprite (loaded once) */
    private Texture skin(int car) {
        Texture t = playerSkins.get(car);
        if (t == null) {
            t = new Texture(Gdx.files.internal(io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel.sprite(car)));
            playerSkins.put(car, t);
        }
        return t;
    }

    /**
     * one recessed row: car, name, status pill
     * @param status "READY", "CPU", or null for not ready yet
     */
    private Table driverRow(Texture car, String name, boolean you, String status) {
        Table row = Cards.row();
        row.add(Cards.carIcon(car)).size(13, 26).padRight(14);
        row.add(Cards.text(name, you)).left().expandX();
        if ("CPU".equals(status)) {
            row.add(Cards.pill(map.cpuName, Cards.CPU, true));
        } else if (status != null) {
            row.add(Cards.pill("READY", Cards.READY, false));
        } else {
            row.add(Cards.pill("NOT READY", Cards.WAITING, false));
        }
        return row;
    }

    /**
     * @param delta time since last frame
     */
    public void render(float delta) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        networkClient.update(delta);

        refreshDrivers();
        refreshSettings();
        if (!networkClient.isConnected()) {
            String error = networkClient.getError();
            kicker.setText(error != null ? error.toUpperCase() : "CONNECTING...");
            kicker.setColor(error != null ? Cards.ERROR : Cards.LABEL);
            readyButton.setDisabled(true);
        } else if (networkClient.isReady()) {
            readyButton.setText(networkClient.isOnline() && !networkClient.isFlagShown() ? "Waiting" : "Ready");
            readyButton.setDisabled(true);
        }

        backdrop.render(delta);
        uiStage.act(delta);
        uiStage.getViewport().apply();
        uiStage.draw();

        // once everyone is ready the red VAR RACES flag waves over everything; while it
        // covers the screen the race screen takes over behind it, with the cars on the grid
        if (networkClient.isFlagShown() && startFlag == null) {
            startFlag = new StartFlag();
        }
        if (startFlag != null) {
            startFlag.render(delta);
            if (startFlag.isCovering()) {
                Vector2 start = networkClient.getStartPosition();
                StartFlag flag = startFlag;
                startFlag = null;   // GameScreen owns it now
                game.setScreen(new GameScreen(game, selectedCar, networkClient, start.x, start.y, flag));
            }
        }
    }

    /**
     * @param width new width   @param height new height
     */
    public void resize(int width, int height) {
        backdrop.resize(width, height);
        uiStage.getViewport().update(width, height, true);
    }

    /**
     * called when screen exits LobbyScreen
     */
    public void hide() {
        dispose();
    }

    /**
     * frees memory
     */
    public void dispose() {
        backdrop.dispose();
        if (startFlag != null) {
            startFlag.dispose();
        }
        for (Texture t : cars) {
            t.dispose();
        }
        for (Texture t : playerSkins.values()) {
            t.dispose();
        }
        uiStage.dispose();
        skin.dispose();
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
