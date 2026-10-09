package io.github.VincentL0L.VARraces;

import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Online races: one gilded card over the drifting, frosted track with three sections:
 * every open public race (on any map) to join, hosting a public or private race (the map
 * is picked next, on MapSelectScreen), and joining with a code.
 * Moves to LobbyScreen once the server puts the player in a room.
 */
public class MultiplayerScreen implements Screen {
    private static final float REFRESH_TIME = 2f;
    private static final int MAX_PLAYERS = 6;
    /** the free server sleeps when nobody plays; after this long it's probably waking up */
    private static final float WAKE_HINT_TIME = 3f;
    private static final float BLOCKED_HINT_TIME = 75f;
    private static final int MAX_ROWS = 3;

    private final Game game;
    private final int selectedCar;
    private final Stage stage;
    private final Skin skin;
    private final TrackBackdrop backdrop = new TrackBackdrop(TrackMap.get(TrackMap.CLASSIC));
    private final NetworkClient networkClient;
    private Label kicker;
    private Table roomsTable;
    private TextField codeField;
    private TextButton[] actionButtons;
    private String shownRooms = null;
    private float refreshTimer = REFRESH_TIME;
    private float connectingTime = 0f;
    private boolean leaving = false;
    private Table rankedRow;
    private String shownRanked = null;

    /**
     * connects to the race server and builds the menu
     * @param game game
     * @param selectedCar car skin the player picked
     */
    public MultiplayerScreen(Game game, int selectedCar) {
        this(game, selectedCar, null);
    }

    /**
     * @param client a connection that's already open (coming back from the map picker), or null
     */
    public MultiplayerScreen(Game game, int selectedCar, NetworkClient client) {
        this.game = game;
        this.selectedCar = selectedCar;
        Sounds.music(Sounds.TITLE, true);
        stage = new Stage(Ui.viewport());
        Sounds.clickSounds(stage);
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));

        if (client != null) {
            networkClient = client;
        } else {
            networkClient = new NetworkClient(null);
            networkClient.connect(Main.socketFactory, Main.serverUrl);
        }

        createUI();
        Gdx.input.setInputProcessor(stage);
    }

    /**
     * the card: status, title, open races, host buttons, join by code, back
     */
    private void createUI() {
        Table card = Cards.card();

        kicker = Cards.kicker("CONNECTING...");
        card.add(kicker).left().row();
        card.add(Cards.title("ONLINE RACE", 44)).left().padTop(2).padBottom(16).row();

        // ranked: sign in with Google, then race for a rating
        card.add(Cards.kicker("RANKED")).left().padBottom(6).row();
        rankedRow = new Table();
        card.add(rankedRow).height(Cards.ROW_HEIGHT + 8).row();

        // open public races
        card.add(Cards.kicker("OPEN RACES")).left().padTop(10).padBottom(6).row();
        roomsTable = new Table();
        roomsTable.top();
        roomsTable.defaults().width(Cards.CARD_WIDTH).height(Cards.ROW_HEIGHT).padBottom(6);
        card.add(roomsTable).top().row();

        // host your own
        card.add(Cards.kicker("HOST A RACE")).left().padTop(10).padBottom(6).row();
        TextButton createPublic = new TextButton("Public", skin);
        TextButton createPrivate = new TextButton("Private", skin);
        createPublic.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                host(true);
            }
        });
        createPrivate.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                host(false);
            }
        });
        Table host = new Table();
        float half = (Cards.CARD_WIDTH - 12f) / 2f;
        host.add(createPublic).width(half).height(56);
        host.add(createPrivate).width(half).height(56).padLeft(12);
        card.add(host).row();

        // join a friend's private race
        card.add(Cards.kicker("JOIN WITH A CODE")).left().padTop(16).padBottom(6).row();
        codeField = new TextField("", skin);
        codeField.setMessageText("ABCD");
        codeField.setMaxLength(4);
        codeField.setAlignment(com.badlogic.gdx.utils.Align.center);
        codeField.setTextFieldFilter(new TextField.TextFieldFilter() {
            public boolean acceptChar(TextField field, char c) {
                return Character.isLetterOrDigit(c);
            }
        });
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
        TextButton joinCode = new TextButton("Join", skin);
        joinCode.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (networkClient.isConnected() && codeField.getText().trim().length() == 4) {
                    networkClient.joinRoom(codeField.getText().toUpperCase(), selectedCar);
                }
            }
        });
        Table join = new Table();
        join.add(codeField).width(half).height(50);
        join.add(joinCode).width(half).height(56).padLeft(12);
        card.add(join).row();

        TextButton back = Cards.smallButton("Back", skin);
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                leaving = true;
                networkClient.stop();
                game.setScreen(new MenuScreen(game, selectedCar));
            }
        });
        Table footer = new Table();
        footer.add(back).width(130).height(50);
        footer.add().expandX();
        card.add(footer).padTop(20).row();
        actionButtons = new TextButton[] {createPublic, createPrivate, joinCode};

        stage.addActor(Cards.center(card));
        rebuildRooms();
    }

    /**
     * hosting: pick the map first (the connection stays open while you choose)
     * @param isPublic true to list the race for everyone
     */
    private void host(boolean isPublic) {
        if (networkClient.isConnected()) {
            game.setScreen(new MapSelectScreen(game, selectedCar, networkClient, isPublic));
        }
    }

    /**
     * the ranked row: a Sign in button, or your rank with Find Race and Leaderboard
     * (rebuilt only when what it shows changes)
     */
    private void refreshRanked() {
        Ui.Account account = Ui.account;
        String name = networkClient.getProfileName();
        String state = account == null || !account.available() ? "none"
            : account.uid() == null ? "out:" + account.error()
            : name == null ? "checking" : name + networkClient.getRating() + networkClient.isRankedOn();
        if (state.equals(shownRanked)) {
            return;
        }
        shownRanked = state;
        rankedRow.clear();
        Table row = Cards.row();
        rankedRow.add(row).width(Cards.CARD_WIDTH).height(Cards.ROW_HEIGHT + 8);
        if (state.equals("none")) {
            row.add(Cards.text("Ranked races need the web version", false)).expandX().left();
            return;
        }
        if (account.uid() == null) {
            String error = account.error();
            row.add(Cards.text(error == null ? "Sign in to race for a rank" : "Sign-in failed, try again", false)).expandX().left();
            TextButton signIn = Cards.smallButton("Sign in with Google", skin);
            // the browser only opens Google's window straight from a click: arm it on press
            signIn.addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
                public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                    Ui.account.armSignIn();
                    return true;
                }
            });
            row.add(signIn).width(210).height(38);
            return;
        }
        if (name == null) {
            row.add(Cards.text("Signing in...", false)).expandX().left();
            return;
        }
        // signed in: tier pill, name and rating, then the buttons
        row.add(Cards.pill(networkClient.getTier(), tierColor(networkClient.getTier()), true)).padRight(10);
        row.add(Cards.text(name + "  " + networkClient.getRating(), true)).expandX().left();
        TextButton board = Cards.smallButton("Top 20", skin);
        board.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new LeaderboardScreen(game, selectedCar, networkClient));
            }
        });
        TextButton find = Cards.smallButton("Find Race", skin);
        find.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                networkClient.joinRanked(selectedCar);
            }
        });
        find.setDisabled(!networkClient.isRankedOn());
        row.add(board).width(96).height(36).padRight(6);
        row.add(find).width(120).height(36);
    }

    /**
     * @return a rank's colour (shared with the leaderboard)
     */
    public static com.badlogic.gdx.graphics.Color tierColor(String tier) {
        switch (tier) {
            case "CHAMPION": return new com.badlogic.gdx.graphics.Color(1f, 0.35f, 0.55f, 1f);
            case "DIAMOND": return new com.badlogic.gdx.graphics.Color(0.45f, 0.85f, 1f, 1f);
            case "PLATINUM": return new com.badlogic.gdx.graphics.Color(0.75f, 0.95f, 0.9f, 1f);
            case "GOLD": return new com.badlogic.gdx.graphics.Color(1f, 0.8f, 0.28f, 1f);
            case "SILVER": return new com.badlogic.gdx.graphics.Color(0.82f, 0.84f, 0.88f, 1f);
            default: return new com.badlogic.gdx.graphics.Color(0.8f, 0.55f, 0.32f, 1f);
        }
    }

    /**
     * fills the open race list: every map's races, each row with its code, map, player
     * count and a Join button
     */
    private void rebuildRooms() {
        roomsTable.clear();
        if (!networkClient.isConnected()) {
            roomsTable.add(Cards.emptyRow("...")).row();
            return;
        }
        List<String[]> rooms = networkClient.getPublicRooms();
        if (rooms.isEmpty()) {
            roomsTable.add(Cards.emptyRow("No open races. Host one below!")).row();
            return;
        }
        for (int i = 0; i < rooms.size() && i < MAX_ROWS; i++) {
            final String code = rooms.get(i)[0];
            Table row = Cards.row();
            row.add(Cards.text(code, true)).left().width(70);
            row.add(Cards.text(TrackMap.get(rooms.get(i)[2]).name.toUpperCase(), false)).left().expandX();
            row.add(Cards.text(rooms.get(i)[1] + "/" + MAX_PLAYERS, false)).right().padRight(12);
            TextButton join = Cards.smallButton("Join", skin);
            join.addListener(new ClickListener() {
                public void clicked(InputEvent e, float x, float y) {
                    networkClient.joinRoom(code, selectedCar);
                }
            });
            row.add(join).width(96).height(34);
            roomsTable.add(row).row();
        }
    }

    /**
     * checks for server messages, refreshes the race list and moves to the lobby once in a room
     * @param delta time since last frame
     */
    public void render(float delta) {
        networkClient.update(delta);

        if (networkClient.isInRoom()) {
            game.setScreen(new LobbyScreen(game, selectedCar, networkClient, null));
            return;
        }

        if (networkClient.getError() != null) {
            kicker.setText(networkClient.getError().toUpperCase());
            kicker.setColor(Cards.ERROR);
        } else if (networkClient.isConnected()) {
            kicker.setText("MULTIPLAYER");
            kicker.setColor(Cards.LABEL);
            connectingTime = 0f;
        } else {
            connectingTime += delta;
            // the free server takes up to a minute to wake up; much longer than that and
            // it's this device's network that's blocking it (school and work Wi-Fi often do)
            if (connectingTime > BLOCKED_HINT_TIME) {
                kicker.setText("CAN'T CONNECT. YOUR WI-FI MAY BLOCK ONLINE GAMES - TRY MOBILE DATA");
            } else if (connectingTime > WAKE_HINT_TIME) {
                kicker.setText("WAKING UP THE SERVER... (UP TO A MINUTE)");
            } else {
                kicker.setText("CONNECTING...");
            }
        }
        for (TextButton b : actionButtons) {
            b.setDisabled(!networkClient.isConnected());
        }

        refreshTimer += delta;
        if (networkClient.isConnected() && refreshTimer >= REFRESH_TIME) {
            refreshTimer = 0f;
            networkClient.requestRooms();
        }

        refreshRanked();

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
        backdrop.render(delta);
        stage.act(delta);
        stage.getViewport().apply();
        stage.draw();
    }

    /**
     * @param width new width   @param height new height
     */
    public void resize(int width, int height) {
        backdrop.resize(width, height);
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
        backdrop.dispose();
        stage.dispose();
        skin.dispose();
        if (leaving) {
            networkClient.stop();
        }
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
