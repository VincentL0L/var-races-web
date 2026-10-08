package io.github.VincentL0L.VARraces;

import java.util.Map;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;

/**
 * Screen called by MenuScreen that calls GameScreen once every player
 * in the lobby readys up
 */
public class LobbyScreen implements Screen {
    private Stage stage;
    private Stage uiStage;
    private Game game;
    private Skin skin;
    private int selectedCar;
    private NetworkClient networkClient;
    private Label statusLabel;
    private Label playersLabel;
    private TextButton readyButton;
    private boolean isReady = false;
    private OrthographicCamera camera;
    private Background background;
    private final FrostedBackdrop frost = new FrostedBackdrop();
    // the track behind the lobby drifts like the old DVD logo, bouncing off the map's edges
    private static final float MAP_WIDTH = 1920f;
    private static final float MAP_HEIGHT = 1080f;
    private float driftX = MathUtils.randomSign() * 70f;
    private float driftY = MathUtils.randomSign() * 45f;
    private StartFlag startFlag;

    /**
     * creates lobby screen passing on the current game and car selected,
     * intializes a networkclient and other fields
     * @param game
     * @param selectedCar
     * @param client online client already in a room, or null for single player
     */
    public LobbyScreen(Game game, int selectedCar, NetworkClient client) {
        this.game = game;
        this.selectedCar = selectedCar;
        
        camera = new OrthographicCamera(675, 360);
        camera.position.set(1000, 500, 0);
        camera.update();

        stage = new Stage(new ExtendViewport(675, 360, camera));
        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        uiStage = new Stage(Ui.viewport());
        
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        
        if (client != null) {
            networkClient = client;
        } else {
            networkClient = new NetworkClient(null);
            networkClient.start("localhost");
        }
        
        createUI();
        background = new Background(stage);
        
        Gdx.input.setInputProcessor(uiStage);
    }

    /**
     * Creates GUI elements of the lobby
     */
    private void createUI() {
        Table mainTable = new Table();
        mainTable.setFillParent(true);

        Label.LabelStyle titleStyle = new Label.LabelStyle(Ui.display(36), Ui.GOLD);
        String title = "Race Lobby";
        if (networkClient.isOnline()) {
            title = (networkClient.isRoomPublic() ? "Public Race " : "Private Race ") + networkClient.getRoomCode();
        }
        Label titleLabel = new Label(title, titleStyle);

        Label.LabelStyle normalStyle = new Label.LabelStyle(Ui.font(13), Ui.CREAM);
        statusLabel = new Label("Press Ready to race", normalStyle);
        playersLabel = new Label("Connected Players: 1", normalStyle);

        readyButton = new TextButton("Ready", skin);
        readyButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!isReady) {
                    isReady = true;
                    readyButton.setText("Waiting...");
                    readyButton.setDisabled(true);
                    networkClient.setReady(true);
                }
            }
        });

        mainTable.add(titleLabel).pad(20).row();
        mainTable.add(statusLabel).pad(10).row();
        mainTable.add(playersLabel).pad(10).row();
        mainTable.add(readyButton).pad(20).width(200).height(60).row();

        String instructions = "Race 3 CPU cars - click Ready to start";
        if (networkClient.isOnline()) {
            instructions = "Share code " + networkClient.getRoomCode()
                + " with friends. The race starts when everyone is Ready";
        }
        Label instructionsLabel = new Label(instructions, normalStyle);
        mainTable.add(instructionsLabel).pad(20).row();

        TextButton leaveButton = new TextButton("Leave", skin);
        leaveButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!networkClient.isFlagShown()) {
                    networkClient.stop();
                    game.setScreen(new MenuScreen(game, selectedCar));
                }
            }
        });
        mainTable.add(leaveButton).width(140).height(45).row();

        // gilded panel behind the text so it reads over the track
        Table panel = new Table();
        panel.setFillParent(true);
        mainTable.setFillParent(false);
        mainTable.setBackground(Ui.panel());
        mainTable.pad(20, 40, 30, 40);
        panel.add(mainTable);
        uiStage.addActor(panel);
    }

    /**
     * 
     * @param delta time since last frame
     */
    public void render(float delta) {
        Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        networkClient.update(delta);

        driftCamera(delta);

        StringBuilder playerList = new StringBuilder();
        playerList.append("Connected Players:\n");
        
        String readyText = networkClient.isReady() ? " [READY]" : " [NOT READY]";
        String localPlayerId = networkClient.getPlayerId();
        playerList.append(localPlayerId).append(readyText).append(" (You)\n");
        
        Map<String, Boolean> readyStates = networkClient.getPlayerReadyStates();
        for (String id : readyStates.keySet()) {
            if (!id.equals(localPlayerId)) {
                readyText = networkClient.isPlayerReady(id) ? " [READY]" : " [NOT READY]";
                playerList.append(id).append(readyText).append("\n");
            }
        }
        
        playersLabel.setText(playerList.toString());

        if (!networkClient.isConnected()) {
            statusLabel.setText(networkClient.getError() != null ? networkClient.getError() : "Waiting for server connection...");
            readyButton.setDisabled(true);
        } else if (networkClient.isReady()) {
            statusLabel.setText(networkClient.isOnline() ? "Waiting for other players..." : "Get ready...");
            readyButton.setText("Waiting...");
            readyButton.setDisabled(true);
        }
        // the track behind the lobby panel is frosted
        frost.begin();
        background.render(stage.getCamera());
        stage.act(delta);
        stage.draw();
        frost.end();
        frost.draw(1f);
        uiStage.act(delta);
        uiStage.getViewport().apply();
        uiStage.draw();

        // once everyone is ready the red VAR RACES flag waves over everything; while it
        // covers the screen the race screen takes over behind it, with the cars on the grid
        if (networkClient.isFlagShown() && startFlag == null) {
            startFlag = new StartFlag();
            statusLabel.setText("Get ready...");
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
     * moves the camera at a steady speed, bouncing whenever the view reaches an edge of the map
     */
    private void driftCamera(float delta) {
        float halfW = Math.min(camera.viewportWidth * camera.zoom / 2f, MAP_WIDTH / 2f);
        float halfH = Math.min(camera.viewportHeight * camera.zoom / 2f, MAP_HEIGHT / 2f);
        float x = camera.position.x + driftX * delta;
        float y = camera.position.y + driftY * delta;
        if (x < halfW) {
            x = halfW;
            driftX = Math.abs(driftX);
        } else if (x > MAP_WIDTH - halfW) {
            x = MAP_WIDTH - halfW;
            driftX = -Math.abs(driftX);
        }
        if (y < halfH) {
            y = halfH;
            driftY = Math.abs(driftY);
        } else if (y > MAP_HEIGHT - halfH) {
            y = MAP_HEIGHT - halfH;
            driftY = -Math.abs(driftY);
        }
        camera.position.set(x, y, 0);
        camera.update();
    }

    /**
     * resizes stage and uiStage
     * @param width new width
     * @param height new height
     */
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        uiStage.getViewport().update(width, height, true);
    }

    /**
     * called when screen exits LobbyScreen
     */
    public void hide() {
        dispose();
    }
    
    /**
     * removes memory
     */
    public void dispose() {
        frost.dispose();
        if (startFlag != null) {
            startFlag.dispose();
        }
        stage.dispose();
        uiStage.dispose();
        skin.dispose();
    }

    //abstract classes that arent used
    public void show() {}

    public void pause() {}

    public void resume() {}

} 