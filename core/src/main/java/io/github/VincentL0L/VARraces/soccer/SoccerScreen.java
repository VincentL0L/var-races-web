package io.github.VincentL0L.VARraces.soccer;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import io.github.VincentL0L.VARraces.*;
import io.github.VincentL0L.VARraces.Multiplayer.client.GameSocket;

/** Soccer lobby and match HUD. All driving and scoring are simulated on the server. */
public final class SoccerScreen extends ScreenAdapter {
    private final Game game;
    private final int selectedCar;
    private final Stage stage = new Stage(Ui.viewport());
    private final Skin skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
    private final SoccerView view = new SoccerView();
    private final float[][] targets = new float[4][], cars = new float[4][];
    private final float[] ballTarget = {700, 450, 26}, ball = {700, 450, 26};
    private final Table setup, waiting, finished;
    private final Label status = Cards.text("Connecting to the game server...", false);
    private final Label roomList = Cards.text("", false), waitText = Cards.text("", false);
    private final Label score = Cards.title("BLUE  0 : 0  ORANGE", 26);
    private final Label clock = Cards.text("3:00", false), roomLabel = Cards.text("RACE CAR SOCCER", false);
    private final Label banner = Cards.title("", 38);
    private final Label boostLabel = new Label("BOOST 100", new Label.LabelStyle(Ui.font(13), Color.WHITE));
    private final Label ballLabel = Cards.text("", false), winner = Cards.title("", 30);
    private final TextButton rematch;
    private GameSocket socket;
    private boolean connected, connecting, disposed, jumpPending;
    private final boolean[] touch = new boolean[6];
    private String code = "";
    private int slot = -1, host, phase = SoccerMatch.WAITING, size = 1;
    private float time = 180, countdown, sendTimer, sinceState;
    private String event = "";

    public SoccerScreen(Game game, int selectedCar) {
        this.game = game; this.selectedCar = selectedCar;
        Gdx.input.setInputProcessor(stage); Sounds.stopMusic();
        Table card = Cards.card();
        card.add(Cards.kicker("FIRST PERSON  /  ONLINE MULTIPLAYER")).left().row();
        card.add(Cards.title("RACE CAR SOCCER", 29)).padBottom(14).left().row();
        Label rules = Cards.text("Drive the ball into the other team's goal.\nFirst to 5, or lead after 3 minutes. Ties go to overtime.", false);
        rules.setWrap(true); card.add(rules).height(64).left().row();
        Table modes = new Table();
        modes.add(button("CREATE 1v1", () -> send("SOCCER|CREATE|1"))).width(220).height(54).padRight(12);
        modes.add(button("CREATE 2v2", () -> send("SOCCER|CREATE|2"))).width(220).height(54);
        card.add(modes).padBottom(12).row();
        TextField joinCode = new TextField("", skin);
        joinCode.setMessageText("ROOM CODE"); joinCode.setMaxLength(5);
        joinCode.setTextFieldFilter((field, ch) -> Character.isLetter(ch));
        Table join = new Table();
        join.add(joinCode).width(280).height(46).padRight(12);
        join.add(button("JOIN", () -> send("SOCCER|JOIN|" + joinCode.getText().trim()))).width(160).height(46);
        card.add(join).row();
        status.setWrap(true); card.add(status).height(50).row();
        roomList.setWrap(true); card.add(roomList).height(60).left().row();
        card.add(button("REFRESH / RECONNECT", () -> { if (connected) send("SOCCER|LIST"); else connect(); })).height(40).row();
        card.add(button("BACK", this::leave)).height(44).padTop(10);
        setup = Cards.center(card); stage.addActor(setup);

        Table waitCard = Cards.card();
        waitCard.add(Cards.title("WAITING FOR PLAYERS", 26)).row();
        waitText.setWrap(true); waitCard.add(waitText).height(140).row();
        waitCard.add(button("LEAVE ROOM", this::leave)).height(50);
        waiting = Cards.center(waitCard); waiting.setVisible(false); stage.addActor(waiting);

        Table endCard = Cards.card(); endCard.add(winner).padBottom(20).row();
        rematch = button("REMATCH", () -> send("SOCCER|REMATCH"));
        endCard.add(rematch).height(52).row();
        endCard.add(button("MAIN MENU", this::leave)).height(48).padTop(12);
        finished = Cards.center(endCard); finished.setVisible(false); stage.addActor(finished);

        Table top = new Table(); top.setFillParent(true); top.top().pad(14);
        Table strip = new Table(); strip.setBackground(Ui.solid(new Color(0.025f, 0.06f, 0.11f, 0.94f))); strip.pad(10, 18, 10, 18);
        strip.add(roomLabel).width(280).left(); strip.add(score).expandX().center();
        strip.add(clock).width(100).right();
        strip.add(button("LEAVE", this::leave)).width(95).height(35).padLeft(15);
        top.add(strip).expandX().fillX(); stage.addActor(top);

        Table center = new Table(); center.setFillParent(true); center.add(banner).padBottom(120); stage.addActor(center);
        center.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
        Table bottom = new Table(); bottom.setFillParent(true); bottom.bottom().pad(18);
        Table readout = new Table();
        readout.setBackground(Ui.solid(new Color(0.025f, 0.06f, 0.11f, 0.94f)));
        readout.pad(8, 14, 8, 14);
        readout.add(boostLabel).left().expandX(); readout.add(ballLabel).right();
        bottom.add(readout).colspan(2).expandX().fillX().row();
        if (Ui.touchScreen) {
            Table buttons = new Table();
            String[] names = {"LEFT", "RIGHT", "GAS", "BRAKE", "BOOST", "JUMP"};
            for (int i = 0; i < names.length; i++) {
                final int index = i; TextButton b = new TextButton(names[i], skin);
                b.addListener(new InputListener() {
                    public boolean touchDown(InputEvent e, float x, float y, int pointer, int button) {
                        touch[index] = true; if (index == 5) jumpPending = true; return true;
                    }
                    public void touchUp(InputEvent e, float x, float y, int pointer, int button) { touch[index] = false; }
                });
                buttons.add(b).width(110).height(65).pad(5);
            }
            bottom.add(buttons).colspan(2).row();
        } else {
            Table hint = new Table(); hint.setBackground(Ui.solid(new Color(0.025f, 0.06f, 0.11f, 0.94f)));
            hint.pad(8, 14, 8, 14);
            hint.add(Cards.text("WASD / ARROWS drive   |   SHIFT boost   |   SPACE jump   |   ESC leave", false));
            bottom.add(hint).colspan(2).expandX().fillX();
        }
        stage.addActor(bottom);
        connect();
    }

    private TextButton button(String text, Runnable action) {
        TextButton b = Cards.smallButton(text, skin);
        b.addListener(new ClickListener() { public void clicked(InputEvent e, float x, float y) { action.run(); } });
        return b;
    }

    private void connect() {
        if (connecting || disposed) return;
        if (Main.socketFactory == null) { status.setText("Multiplayer is not available on this platform"); return; }
        connecting = true; status.setText("Connecting...");
        socket = Main.socketFactory.create(Main.serverUrl, new GameSocket.Listener() {
            public void onOpen() { Gdx.app.postRunnable(() -> {
                if (disposed) return;
                connected = true; connecting = false; status.setText("Create a match or join a friend's room code."); send("SOCCER|LIST");
            }); }
            public void onMessage(String message) { Gdx.app.postRunnable(() -> { if (!disposed) receive(message); }); }
            public void onClose() { Gdx.app.postRunnable(() -> {
                if (disposed) return;
                connected = connecting = false;
                status.setText("Disconnected. Check the server and use Reconnect.");
                code = ""; slot = -1; setup.setVisible(true); waiting.setVisible(false); finished.setVisible(false);
            }); }
        });
    }

    private void send(String message) {
        if (connected) socket.send(message);
        else status.setText("Not connected yet. Check the server and try Reconnect.");
    }

    private void receive(String message) {
        if (message.startsWith("SOCCER_JOINED|")) {
            String[] p = message.split("\\|"); code = p[1]; slot = Integer.parseInt(p[2]);
            setup.setVisible(false); stage.setKeyboardFocus(null); sinceState = 0;
        } else if (message.startsWith("SOCCER_ERROR|")) status.setText(message.substring(13));
        else if (message.startsWith("SOCCER_LIST")) {
            String[] p = message.split("\\|"); StringBuilder list = new StringBuilder("OPEN ROOMS: ");
            if (p.length == 1) list.append("None yet - create one and share the code.");
            for (int i = 1; i < Math.min(p.length, 5); i++) {
                String[] r = p[i].split(",");
                list.append(r[0]).append(" (").append(r[1]).append("v").append(r[1]).append(", ")
                    .append(r[2]).append('/').append(Integer.parseInt(r[1]) * 2).append(")  ");
            }
            roomList.setText(list);
        } else if (message.startsWith("SOCCER_STATE|")) {
            JsonValue s = new JsonReader().parse(message.substring(13));
            int nextPhase = s.getInt("phase"); boolean reset = nextPhase != phase;
            phase = nextPhase; time = s.getFloat("time"); countdown = s.getFloat("countdown");
            size = s.getInt("size"); host = s.getInt("host", 0); event = s.getString("event"); sinceState = 0;
            score.setText("BLUE  " + s.getInt("blue") + " : " + s.getInt("orange") + "  ORANGE");
            JsonValue b = s.get("ball");
            for (int i = 0; i < 3; i++) { ballTarget[i] = b.getFloat(i); if (reset) ball[i] = ballTarget[i]; }
            for (int i = 0; i < 4; i++) targets[i] = null;
            for (JsonValue c = s.get("cars").child; c != null; c = c.next) {
                int id = c.getInt(0); targets[id] = c.asFloatArray();
                if (cars[id] == null || reset) cars[id] = targets[id].clone();
            }
            for (int i = 0; i < 4; i++) if (targets[i] == null) cars[i] = null;
        }
    }

    @Override public void render(float delta) {
        if (disposed) return;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { leave(); return; }
        float smooth = 1 - (float) Math.exp(-25 * Math.min(delta, 0.1f));
        for (int i = 0; i < 4; i++) if (cars[i] != null && targets[i] != null) {
            for (int j = 1; j < 8; j++) cars[i][j] = j == 4
                ? MathUtils.lerpAngleDeg(cars[i][j], targets[i][j], smooth)
                : MathUtils.lerp(cars[i][j], targets[i][j], smooth);
        }
        for (int i = 0; i < 3; i++) ball[i] = MathUtils.lerp(ball[i], ballTarget[i], smooth);
        view.render(cars, ball, slot);
        sinceState += delta;
        waiting.setVisible(!code.isEmpty() && phase == SoccerMatch.WAITING);
        finished.setVisible(!code.isEmpty() && phase == SoccerMatch.FINISHED);
        banner.setText(code.isEmpty() || phase == SoccerMatch.WAITING || phase == SoccerMatch.FINISHED ? ""
            : phase == SoccerMatch.KICKOFF ? String.valueOf(Math.max(1, (int) Math.ceil(countdown)))
            : phase == SoccerMatch.GOAL ? event : time <= 0 ? "OVERTIME" : "");
        int count = 0; for (float[] c : cars) if (c != null) count++;
        waitText.setText("ROOM CODE: " + code + "\n" + size + "v" + size + "  |  " + count + "/" + (size * 2)
            + " connected\n\nShare this code with your friends.\nThe match starts when everyone joins.");
        roomLabel.setText(code.isEmpty() ? "RACE CAR SOCCER" : "ROOM " + code + " | " + size + "v" + size);
        clock.setText(time <= 0 ? "OT" : ((int) Math.ceil(time) / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", (int) Math.ceil(time) % 60));
        winner.setText(event); rematch.setVisible(slot == host);
        if (slot >= 0 && cars[slot] != null) {
            float[] c = cars[slot];
            boostLabel.setText((slot % 2 == 0 ? "BLUE" : "ORANGE") + " TEAM  |  BOOST " + (int) c[5]);
            boostLabel.setColor(slot % 2 == 0 ? SoccerView.BLUE : SoccerView.ORANGE);
            float angle = MathUtils.atan2(ball[1] - c[2], ball[0] - c[1]) * MathUtils.radiansToDegrees;
            float relative = ((angle - c[4] + 540) % 360) - 180;
            String bearing = Math.abs(relative) > 110 ? "BEHIND" : relative > 20 ? "LEFT" : relative < -20 ? "RIGHT" : "AHEAD";
            ballLabel.setText(sinceState > 2 ? "CONNECTION LOST - waiting for server" : "BALL " + bearing + "  "
                + (int) Math.sqrt((ball[0] - c[1]) * (ball[0] - c[1]) + (ball[1] - c[2]) * (ball[1] - c[2])) + "m");
            sendTimer += delta;
            jumpPending |= Gdx.input.isKeyJustPressed(Input.Keys.SPACE);
            if (sendTimer >= 0.05f && connected) {
                sendTimer = 0;
                float throttle = (down(Input.Keys.W, Input.Keys.UP) || touch[2] ? 1 : 0) - (down(Input.Keys.S, Input.Keys.DOWN) || touch[3] ? 1 : 0);
                float steer = (down(Input.Keys.A, Input.Keys.LEFT) || touch[0] ? 1 : 0) - (down(Input.Keys.D, Input.Keys.RIGHT) || touch[1] ? 1 : 0);
                boolean boost = down(Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT) || touch[4];
                boolean jump = jumpPending || Gdx.input.isKeyPressed(Input.Keys.SPACE) || touch[5];
                send("SOCCER|INPUT|" + throttle + "|" + steer + "|" + (boost ? 1 : 0) + "|" + (jump ? 1 : 0));
                jumpPending = false;
            }
        }
        stage.getViewport().apply(); stage.act(delta); stage.draw();
    }

    private boolean down(int a, int b) { return Gdx.input.isKeyPressed(a) || Gdx.input.isKeyPressed(b); }
    private void leave() { if (!disposed) game.setScreen(new MenuScreen(game, selectedCar)); }
    @Override public void resize(int width, int height) { stage.getViewport().update(width, height, true); view.resize(width, height); }
    @Override public void hide() { dispose(); }
    @Override public void dispose() {
        if (disposed) return; disposed = true;
        if (socket != null) socket.close();
        view.dispose(); stage.dispose(); skin.dispose();
    }
}
