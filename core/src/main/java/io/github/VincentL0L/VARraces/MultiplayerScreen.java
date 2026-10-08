package io.github.VincentL0L.VARraces;

import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;

/**
 * Screen called by MenuScreen for online races. Lists public races,
 * creates public or private races, or joins one with a room code.
 * Moves to LobbyScreen once the server puts the player in a room.
 */
public class MultiplayerScreen implements Screen {
    private static final float REFRESH_TIME = 2f;
    private static final int MAX_PLAYERS = 6;

    private Game game;
    private int selectedCar;
    private Stage stage;
    private Skin skin;
    private Texture bgTexture;
    private NetworkClient networkClient;
    private Label statusLabel;
    private Table roomsTable;
    private TextField codeField;
    private TextButton[] actionButtons;
    private String shownRooms = null;
    private float refreshTimer = REFRESH_TIME;
    private boolean leaving = false;

    /**
     * connects to the race server and builds the menu
     * @param game game
     * @param selectedCar car skin the player picked
     */
    public MultiplayerScreen(Game game, int selectedCar) {
        this.game = game;
        this.selectedCar = selectedCar;
        stage = new Stage(Ui.viewport());
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        bgTexture = new Texture("ui/menu.png");

        networkClient = new NetworkClient(null);
        networkClient.connect(Main.socketFactory, Main.serverUrl);

        createUI();
        Gdx.input.setInputProcessor(stage);
    }

    /**
     * builds the title, room list and buttons
     */
    private void createUI() {
        Image bg = new Image(bgTexture);
        bg.setFillParent(true);
        bg.setColor(0.4f, 0.4f, 0.4f, 1f);
        stage.addActor(bg);

        Table mainTable = new Table();
        mainTable.setFillParent(true);

        Label.LabelStyle titleStyle = new Label.LabelStyle(Ui.display(36), Ui.GOLD);
        Label.LabelStyle normalStyle = new Label.LabelStyle(Ui.font(13), Ui.CREAM);

        statusLabel = new Label("Connecting to race server...", normalStyle);
        roomsTable = new Table();

        TextButton createPublic = new TextButton("Public", skin);
        TextButton createPrivate = new TextButton("Private", skin);
        TextButton joinCode = new TextButton("Join", skin);
        TextButton back = new TextButton("Back", skin);
        codeField = new TextField("", skin);
        codeField.setMessageText("CODE");
        codeField.setMaxLength(4);
        // phones have no keyboard for the game itself, so ask with the browser's text box
        codeField.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (Ui.touchScreen && Ui.textPrompt != null) {
                    String code = Ui.textPrompt.ask("Room code", codeField.getText());
                    if (code != null) {
                        code = code.trim().toUpperCase();
                        codeField.setText(code.length() > 4 ? code.substring(0, 4) : code);
                    }
                }
            }
        });

        createPublic.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (networkClient.isConnected()) {
                    networkClient.createRoom(true, selectedCar);
                }
            }
        });
        createPrivate.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (networkClient.isConnected()) {
                    networkClient.createRoom(false, selectedCar);
                }
            }
        });
        joinCode.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (networkClient.isConnected() && codeField.getText().trim().length() == 4) {
                    networkClient.joinRoom(codeField.getText(), selectedCar);
                }
            }
        });
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                leaving = true;
                networkClient.stop();
                game.setScreen(new MenuScreen(game, selectedCar));
            }
        });
        actionButtons = new TextButton[] {createPublic, createPrivate, joinCode};

        Table createRow = new Table();
        createRow.add(new Label("Create a race:", normalStyle)).padRight(10);
        createRow.add(createPublic).width(150).height(54).pad(5);
        createRow.add(createPrivate).width(150).height(54).pad(5);

        Table joinRow = new Table();
        joinRow.add(new Label("Room code:", normalStyle)).padRight(10);
        joinRow.add(codeField).width(110).height(44);
        joinRow.add(joinCode).width(100).height(48).padLeft(10);

        mainTable.add(new Label("Multiplayer", titleStyle)).pad(15).row();
        mainTable.add(statusLabel).pad(5).row();
        mainTable.add(new Label("Public races", normalStyle)).padTop(15).row();
        mainTable.add(roomsTable).width(420).height(150).top().row();
        mainTable.add(createRow).pad(10).row();
        mainTable.add(joinRow).pad(10).row();
        mainTable.add(back).width(150).height(50).padTop(15).row();

        Table panel = new Table();
        panel.setFillParent(true);
        mainTable.setFillParent(false);
        mainTable.setBackground(Ui.panel());
        mainTable.pad(20, 40, 25, 40);
        panel.add(mainTable);
        stage.addActor(panel);
        rebuildRooms();
    }

    /**
     * fills the public race list, each with a join button
     */
    private void rebuildRooms() {
        roomsTable.clear();
        roomsTable.top();
        Label.LabelStyle style = new Label.LabelStyle(Ui.font(13), Ui.CREAM);
        List<String[]> rooms = networkClient.getPublicRooms();

        if (!networkClient.isConnected()) {
            roomsTable.add(new Label("-", style));
            return;
        }
        if (rooms.isEmpty()) {
            roomsTable.add(new Label("No public races right now - create one!", style));
            return;
        }
        for (String[] room : rooms) {
            final String code = room[0];
            roomsTable.add(new Label("Race " + code + "   " + room[1] + "/" + MAX_PLAYERS
                + " players", style)).left().expandX().pad(4);
            TextButton join = new TextButton("Join", skin);
            join.addListener(new ClickListener() {
                public void clicked(InputEvent e, float x, float y) {
                    networkClient.joinRoom(code, selectedCar);
                }
            });
            roomsTable.add(join).width(90).height(42).pad(4).row();
        }
    }

    /**
     * checks for server messages, refreshes the race list and moves to the lobby once in a room
     * @param delta time since last frame
     */
    public void render(float delta) {
        networkClient.update(delta);

        if (networkClient.isInRoom()) {
            game.setScreen(new LobbyScreen(game, selectedCar, networkClient));
            return;
        }

        if (networkClient.getError() != null) {
            statusLabel.setText(networkClient.getError());
            statusLabel.setColor(Color.SALMON);
        } else if (networkClient.isConnected()) {
            statusLabel.setText("Join a public race, create one, or enter a friend's code");
            statusLabel.setColor(Ui.CREAM);
        }
        for (TextButton b : actionButtons) {
            b.setDisabled(!networkClient.isConnected());
        }

        refreshTimer += delta;
        if (networkClient.isConnected() && refreshTimer >= REFRESH_TIME) {
            refreshTimer = 0f;
            networkClient.requestRooms();
        }

        StringBuilder rooms = new StringBuilder(String.valueOf(networkClient.isConnected()));
        for (String[] room : networkClient.getPublicRooms()) {
            rooms.append(room[0]).append(room[1]);
        }
        if (!rooms.toString().equals(shownRooms)) {
            shownRooms = rooms.toString();
            rebuildRooms();
        }

        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    /**
     * resizes stage
     * @param width new width
     * @param height new height
     */
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    /**
     * called when leaving this screen
     */
    public void hide() {
        dispose();
    }

    /**
     * clears memory (the network client lives on in LobbyScreen unless Back was pressed)
     */
    public void dispose() {
        stage.dispose();
        skin.dispose();
        bgTexture.dispose();
        if (leaving) {
            networkClient.stop();
        }
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
