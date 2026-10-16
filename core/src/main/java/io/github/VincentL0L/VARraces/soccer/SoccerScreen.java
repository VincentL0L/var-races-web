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

/**
 * Soccer lobby and match HUD. Online, all driving and scoring are simulated on the server;
 * against the CPU the same SoccerMatch runs right here, with CPU drivers ({@link #cpuInput})
 * in every seat but ours.
 */
public final class SoccerScreen extends ScreenAdapter {
    private final Game game;
    private final int selectedCar;
    private final Stage stage = new Stage(Ui.viewport());
    private final Skin skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
    private final SoccerView view = new SoccerView();
    private final float[][] targets = new float[4][], cars = new float[4][];
    private final float[] ballTarget = {SoccerMatch.WIDTH / 2, SoccerMatch.HEIGHT / 2, SoccerMatch.BALL_RADIUS},
        ball = {SoccerMatch.WIDTH / 2, SoccerMatch.HEIGHT / 2, SoccerMatch.BALL_RADIUS};
    private final float[] pickupCooldowns = new float[SoccerMatch.createPickups().length];
    private final float[][] effects = new float[4][7];
    private final Table setup, waiting, finished;
    private final Label status = Cards.text("Connecting to the game server...", false);
    private final Label roomList = Cards.text("", false), waitText = Cards.text("", false);
    private final Label score = Cards.title("BLUE  0 : 0  ORANGE", 26);
    private final Label clock = Cards.text("3:00", false), roomLabel = Cards.text("RACE CAR SOCCER", false);
    private final Label banner = Cards.title("", 38);
    private final Label boostLabel = new Label("BOOST 100", new Label.LabelStyle(Ui.font(13), Color.WHITE));
    private final Label ballLabel = Cards.text("", false), winner = Cards.title("", 30);
    private final Label powerLabel = new Label("DRIVE OVER PICKUPS", new Label.LabelStyle(Ui.font(12), Color.WHITE));
    private final Label pickupToast = Cards.title("", 25);
    private final TextButton rematch;
    private GameSocket socket;
    private boolean connected, connecting, disposed, jumpPending;
    private final boolean[] touch = new boolean[6];
    private String code = "";
    private int slot = -1, host, phase = SoccerMatch.WAITING, size = 1;
    private float time = 180, countdown, sendTimer, sinceState;
    private float toastTime;
    private int lastPickupSerial;
    private String event = "";
    /** a match against the CPU, simulated here instead of on the server (null when online) */
    private SoccerMatch local;

    public SoccerScreen(Game game, int selectedCar) {
        this.game = game; this.selectedCar = selectedCar;
        Gdx.input.setInputProcessor(stage); Sounds.stopMusic();
        Table card = Cards.card();
        card.add(Cards.kicker("FIRST PERSON  /  VS CPU OR ONLINE")).left().row();
        card.add(Cards.title("RACE CAR SOCCER", 29)).padBottom(14).left().row();
        Label rules = Cards.text("First to 5, or lead after 3 minutes. Ties go to overtime.\nDrive over pickups - they activate automatically.\nGOLD: boost  |  CYAN: nitro  |  PINK: power shot\nPURPLE: magnet - pull a nearby ball to your bumper.", false);
        rules.setWrap(true); card.add(rules).height(104).left().row();
        Table solo = new Table();
        solo.add(button("VS CPU 1v1", () -> startLocal(1))).width(220).height(54).padRight(12);
        solo.add(button("VS CPU 2v2", () -> startLocal(2))).width(220).height(54);
        card.add(solo).padBottom(10).row();
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
        Table toast = new Table(); toast.setFillParent(true); toast.add(pickupToast).padTop(180);
        toast.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled); stage.addActor(toast);
        Table bottom = new Table(); bottom.setFillParent(true); bottom.bottom().pad(18);
        Table readout = new Table();
        readout.setBackground(Ui.solid(new Color(0.025f, 0.06f, 0.11f, 0.94f)));
        readout.pad(8, 14, 8, 14);
        readout.add(boostLabel).left().expandX(); readout.add(powerLabel).center().expandX(); readout.add(ballLabel).right();
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
            hint.add(Cards.text("WASD / ARROWS drive   |   SHIFT boost / air lift   |   SPACE jump   |   ESC leave", false));
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

    /** single player: you on blue against CPU orange (a CPU team-mate too in 2v2) */
    private void startLocal(int teamSize) {
        local = new SoccerMatch(teamSize);
        for (int i = 0; i < teamSize * 2; i++) {
            local.add(i);
        }
        code = "CPU";
        slot = 0;
        lastPickupSerial = 0;
        toastTime = 0;
        phase = -1;
        setup.setVisible(false);
        stage.setKeyboardFocus(null);
        receive(localState());
    }

    private String localState() {
        String snapshot = local.snapshot();
        return "SOCCER_STATE|" + snapshot.substring(0, snapshot.length() - 1) + ",\"host\":0}";
    }

    /**
     * a CPU driver: get goal-side of the ball and drive through it toward their goal; in
     * 2v2 the back CPU (slots 2 and 3) holds near its own goal while the ball is far away
     */
    private void cpuInput(SoccerMatch m, SoccerMatch.Car car) {
        SoccerMatch.Ball b = m.ball;
        float attackX = car.team == 0 ? SoccerMatch.WIDTH + 60 : -60, ownX = car.team == 0 ? 0 : SoccerMatch.WIDTH;
        float gx = attackX - b.x, gy = SoccerMatch.HEIGHT / 2 - b.y, gl = (float) Math.sqrt(gx * gx + gy * gy);
        gx /= gl;
        gy /= gl;
        float tx, ty;
        boolean behind = (car.x - b.x) * gx + (car.y - b.y) * gy < -10;
        boolean keeper = m.teamSize == 2 && car.slot >= 2 && Math.abs(b.x - ownX) > SoccerMatch.WIDTH * 0.45f;
        if (keeper) {
            tx = ownX + (car.team == 0 ? 150 : -150);
            ty = MathUtils.clamp(b.y, SoccerMatch.HEIGHT / 2 - 160, SoccerMatch.HEIGHT / 2 + 160);
        } else if (behind) {
            tx = b.x + gx * 20;                     // lined up: drive straight through it
            ty = b.y + gy * 20;
        } else {
            tx = b.x - gx * 70;                     // get round behind the ball first
            ty = b.y - gy * 70 + (car.y < b.y ? -40 : 40);
        }
        float dx = tx - car.x, dy = ty - car.y, dist = (float) Math.sqrt(dx * dx + dy * dy);
        float rel = ((MathUtils.atan2(dy, dx) * MathUtils.radiansToDegrees - car.heading) % 360 + 540) % 360 - 180;
        float steer = MathUtils.clamp(rel / 28f, -1f, 1f);
        float throttle = keeper && dist < 40 ? 0f : Math.abs(rel) > 120 && dist < 140 ? -1f : 1f;
        boolean boost = Math.abs(rel) < 14 && dist > 220 && car.boost > 25 && !keeper;
        float bdx = b.x - car.x, bdy = b.y - car.y;
        boolean nearHighBall = b.z > car.z + 25 && bdx * bdx + bdy * bdy < 180 * 180;
        boolean jump = nearHighBall && car.z < 0.1f;
        boost |= nearHighBall && car.z > 0 && car.boost > 5;
        m.input(car.slot, throttle, steer, boost, jump);
    }

    private void send(String message) {
        if (local != null) {
            if (message.equals("SOCCER|REMATCH")) {
                local.restart();
            }
            return;
        }
        if (connected) socket.send(message);
        else status.setText("Not connected yet. Check the server and try Reconnect.");
    }

    private void receive(String message) {
        if (message.startsWith("SOCCER_JOINED|")) {
            String[] p = message.split("\\|"); code = p[1]; slot = Integer.parseInt(p[2]);
            lastPickupSerial = 0; toastTime = 0;
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
            JsonValue pads = s.get("pickups");
            if (pads != null) for (int i = 0; i < Math.min(pads.size, pickupCooldowns.length); i++) pickupCooldowns[i] = pads.getFloat(i);
            JsonValue power = s.get("effects");
            if (power != null) for (JsonValue e = power.child; e != null; e = e.next) effects[e.getInt(0)] = e.asFloatArray();
            if (slot >= 0 && (int) effects[slot][5] > lastPickupSerial) {
                lastPickupSerial = (int) effects[slot][5];
                pickupToast.setText(SoccerMatch.pickupName((int) effects[slot][6]) + "!");
                toastTime = 2;
                Sounds.play("item_pickup", 0.45f);
            }
        }
    }

    @Override public void render(float delta) {
        if (disposed) return;
        // Browser frame clocks can jump during focus changes. Never extrapolate the
        // camera away from the authoritative positions on a negative or invalid frame.
        delta = Float.isFinite(delta) ? MathUtils.clamp(delta, 0, 0.1f) : 0;
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) { leave(); return; }
        if (local != null) {
            // the CPU drive, the match runs a step, and we read it as if the server sent it
            for (SoccerMatch.Car car : local.cars) {
                if (car != null && car.slot != slot) {
                    cpuInput(local, car);
                }
            }
            local.step(delta);
            receive(localState());
        }
        float smooth = 1 - (float) Math.exp(-25 * delta);
        for (int i = 0; i < 4; i++) if (cars[i] != null && targets[i] != null) {
            for (int j = 1; j < 8; j++) cars[i][j] = j == 4
                ? MathUtils.lerpAngleDeg(cars[i][j], targets[i][j], smooth)
                : MathUtils.lerp(cars[i][j], targets[i][j], smooth);
        }
        for (int i = 0; i < 3; i++) ball[i] = MathUtils.lerp(ball[i], ballTarget[i], smooth);
        view.render(cars, ball, slot, pickupCooldowns, effects, delta);
        toastTime = Math.max(0, toastTime - delta);
        pickupToast.setVisible(toastTime > 0 && phase == SoccerMatch.PLAYING);
        sinceState += delta;
        waiting.setVisible(!code.isEmpty() && phase == SoccerMatch.WAITING);
        finished.setVisible(!code.isEmpty() && phase == SoccerMatch.FINISHED);
        banner.setText(code.isEmpty() || phase == SoccerMatch.WAITING || phase == SoccerMatch.FINISHED ? ""
            : phase == SoccerMatch.KICKOFF ? String.valueOf(Math.max(1, (int) Math.ceil(countdown)))
            : phase == SoccerMatch.GOAL ? event : time <= 0 ? "OVERTIME" : "");
        int count = 0; for (float[] c : cars) if (c != null) count++;
        waitText.setText("ROOM CODE: " + code + "\n" + size + "v" + size + "  |  " + count + "/" + (size * 2)
            + " connected\n\nShare this code with your friends.\nThe match starts when everyone joins.");
        roomLabel.setText(code.isEmpty() ? "RACE CAR SOCCER" : local != null ? "VS CPU | " + size + "v" + size
            : "ROOM " + code + " | " + size + "v" + size);
        clock.setText(time <= 0 ? "OT" : ((int) Math.ceil(time) / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", (int) Math.ceil(time) % 60));
        winner.setText(event); rematch.setVisible(slot == host);
        if (slot >= 0 && cars[slot] != null) {
            float[] c = cars[slot];
            boostLabel.setText((slot % 2 == 0 ? "BLUE" : "ORANGE") + " TEAM  |  BOOST " + (int) c[5]);
            boostLabel.setColor(slot % 2 == 0 ? SoccerView.BLUE : SoccerView.ORANGE);
            StringBuilder powers = new StringBuilder();
            String[] names = {"", "NITRO", "POWER SHOT", "MAGNET"};
            for (int i = 1; i <= 3; i++) if (effects[slot][i] > 0) {
                if (powers.length() > 0) powers.append(" | ");
                powers.append(names[i]).append(' ').append((int) Math.ceil(effects[slot][i])).append('s');
            }
            powerLabel.setText(powers.length() == 0 ? "DRIVE OVER PICKUPS" : powers.toString());
            float angle = MathUtils.atan2(ball[1] - c[2], ball[0] - c[1]) * MathUtils.radiansToDegrees;
            float relative = ((angle - c[4] + 540) % 360) - 180;
            String bearing = Math.abs(relative) > 110 ? "BEHIND" : relative > 20 ? "LEFT" : relative < -20 ? "RIGHT" : "AHEAD";
            ballLabel.setText(sinceState > 2 && local == null ? "CONNECTION LOST - waiting for server" : "BALL " + bearing + "  "
                + (int) Math.sqrt((ball[0] - c[1]) * (ball[0] - c[1]) + (ball[1] - c[2]) * (ball[1] - c[2])) + "m  |  HEIGHT " + (int) ball[2] + "m");
            sendTimer += delta;
            jumpPending |= Gdx.input.isKeyJustPressed(Input.Keys.SPACE);
            if (sendTimer >= 0.05f && (connected || local != null)) {
                sendTimer = 0;
                float throttle = (down(Input.Keys.W, Input.Keys.UP) || touch[2] ? 1 : 0) - (down(Input.Keys.S, Input.Keys.DOWN) || touch[3] ? 1 : 0);
                float steer = (down(Input.Keys.A, Input.Keys.LEFT) || touch[0] ? 1 : 0) - (down(Input.Keys.D, Input.Keys.RIGHT) || touch[1] ? 1 : 0);
                boolean boost = down(Input.Keys.SHIFT_LEFT, Input.Keys.SHIFT_RIGHT) || touch[4];
                boolean jump = jumpPending || Gdx.input.isKeyPressed(Input.Keys.SPACE) || touch[5];
                if (local != null) {
                    local.input(slot, throttle, steer, boost, jump);
                } else {
                    send("SOCCER|INPUT|" + throttle + "|" + steer + "|" + (boost ? 1 : 0) + "|" + (jump ? 1 : 0));
                }
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
