package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.viewport.FitViewport;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel;

/**
 * Parking: six levels, from an easy first spot to a timed squeeze in a busy multi-storey
 * garage. Drive into the glowing bay, inside the lines and facing the way the arrow points,
 * and come to a stop. Every bump into a parked car, pillar, wall or cone counts; three and
 * the level's failed. Stars for a clean, quick park; the best are remembered.
 *
 * The car here handles like a real one at parking speed: the front wheels steer and the
 * back follows (a bicycle model), so it swings wide going forward and tucks in reversing.
 */
public class ParkingScreen implements Screen {
    /** the lot, in world units */
    private static final float WORLD_W = 1000, WORLD_H = 640;
    /** our car's size and wheelbase (world units) */
    private static final float CAR_W = 26, CAR_L = 52, WHEELBASE = 34;
    private static final float MAX_FORWARD = 150, MAX_REVERSE = 75, MAX_STEER = 36;
    private static final int MAX_BUMPS = 3;

    private static final String[] PARKED = {"ui/car4.png", "ui/car5.png", "ui/car6.png", "ui/cab.png", "ui/waymo.png",
        "ui/kei.png", "ui/2cv.png", "ui/cop.png", "ui/cars/roadster_1.png", "ui/cars/hauler_2.png"};

    /** a solid thing: a box at (x, y) of size w x h turned by angle; kind 0 wall/pillar, 1 parked car, 2 cone */
    private static final class Thing {
        final float x, y, w, h, angle;
        final int kind, sprite;

        Thing(float x, float y, float w, float h, float angle, int kind, int sprite) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.angle = angle;
            this.kind = kind;
            this.sprite = sprite;
        }
    }

    /** one level: where we start, the bay to park in (facing `spotAngle`), and what's in the way */
    private static final class Level {
        String name, hint;
        boolean garage;
        float startX, startY, startHeading;
        float spotX, spotY, spotW = 40, spotH = 68, spotAngle;
        float timeLimit = 0;
        float par = 30;
        final List<Thing> things = new ArrayList<>();
        /** painted bay lines: x, y, angle of each bay (same size as the spot) */
        final List<float[]> bays = new ArrayList<>();
    }

    private final Game game;
    private final int carNumber;
    private final Stage stage = new Stage(Ui.viewport());
    private final Skin skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
    private final OrthographicCamera camera = new OrthographicCamera();
    private final FitViewport world = new FitViewport(WORLD_W, WORLD_H, camera);
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final Texture carArt, floor, concrete;
    private final Texture[] parkedArt = new Texture[PARKED.length];
    private final List<Level> levels = new ArrayList<>();
    private final Table menu, result, hud;
    private final Label title, hint, status, resultTitle, resultText;
    private final TextButton nextButton;
    private final boolean[] touch = new boolean[4];

    private Level level;
    private int levelIndex = -1;
    private float x, y, heading, speed, steer, clock, stillTime, shake;
    private int bumps;
    private boolean finished;

    public ParkingScreen(Game game, int carNumber) {
        this.game = game;
        this.carNumber = carNumber;
        Sounds.music(Sounds.TITLE, true);
        carArt = load(CarModel.sprite(carNumber));
        for (int i = 0; i < PARKED.length; i++) {
            parkedArt[i] = load(PARKED[i]);
        }
        floor = noise(0.36f, 0.36f, 0.38f);
        concrete = noise(0.52f, 0.52f, 0.5f);
        buildLevels();

        Sounds.clickSounds(stage);
        Gdx.input.setInputProcessor(stage);

        // the level list
        Table card = Cards.card();
        card.add(Cards.kicker("SINGLE PLAYER")).left().row();
        card.add(Cards.title("PARKING", 36)).left().padBottom(6).row();
        Label rules = Cards.text("Park in the glowing bay, facing the arrow, and stop.\n"
            + "W / S drive and reverse, A / D steer, SPACE brake. Three bumps and you fail.", false);
        rules.setWrap(true);
        card.add(rules).left().padBottom(10).row();
        Table grid = new Table();
        for (int i = 0; i < levels.size(); i++) {
            final int index = i;
            int stars = bestStars(i);
            String label = (i + 1) + ". " + levels.get(i).name.toUpperCase() + "  " + starText(stars);
            TextButton b = Cards.smallButton(label, skin);
            b.getLabel().setAlignment(com.badlogic.gdx.utils.Align.left);
            b.addListener(new ClickListener() {
                public void clicked(InputEvent e, float bx, float by) {
                    start(index);
                }
            });
            grid.add(b).width(CARD_ROW).height(44).padBottom(6).row();
        }
        card.add(grid).row();
        TextButton back = Cards.smallButton("Back", skin);
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                game.setScreen(new MapSelectScreen(game, carNumber));
            }
        });
        card.add(back).width(130).height(48).left().padTop(6);
        menu = Cards.center(card);
        stage.addActor(menu);

        // the HUD over a level
        hud = new Table();
        hud.setFillParent(true);
        hud.top().pad(12);
        Table strip = new Table();
        strip.setBackground(Ui.solid(new Color(0.06f, 0.05f, 0.04f, 0.88f)));
        strip.pad(8, 14, 8, 14);
        title = new Label("", new Label.LabelStyle(Ui.display(18), Ui.GOLD));
        status = new Label("", new Label.LabelStyle(Ui.font(12), Color.WHITE));
        strip.add(title).left().expandX();
        strip.add(status).right().padRight(14);
        TextButton quit = Cards.smallButton("Levels", skin);
        quit.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                showMenu();
            }
        });
        strip.add(quit).width(110).height(36);
        hud.add(strip).expandX().fillX().row();
        hint = new Label("", new Label.LabelStyle(Ui.font(11), new Color(1f, 1f, 1f, 0.85f)));
        hud.add(hint).padTop(6).row();
        if (Ui.touchScreen) {
            hud.add().expandY().row();
            Table pads = new Table();
            String[] names = {"LEFT", "RIGHT", "REVERSE", "GAS"};
            for (int i = 0; i < names.length; i++) {
                final int k = i;
                TextButton b = new TextButton(names[i], skin);
                b.addListener(new InputListener() {
                    public boolean touchDown(InputEvent e, float px, float py, int pointer, int button) {
                        touch[k] = true;
                        return true;
                    }

                    public void touchUp(InputEvent e, float px, float py, int pointer, int button) {
                        touch[k] = false;
                    }
                });
                pads.add(b).width(120).height(70).pad(6);
                if (i == 1) {
                    pads.add().expandX();
                }
            }
            hud.add(pads).expandX().fillX();
        }
        hud.setVisible(false);
        stage.addActor(hud);

        // the result card
        Table end = Cards.card();
        resultTitle = Cards.title("", 32);
        resultText = Cards.text("", false);
        end.add(resultTitle).padBottom(8).row();
        end.add(resultText).padBottom(14).row();
        Table buttons = new Table();
        TextButton retry = Cards.smallButton("Retry", skin);
        retry.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                start(levelIndex);
            }
        });
        nextButton = new TextButton("Next level", skin);
        nextButton.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                if (levelIndex + 1 < levels.size()) {
                    start(levelIndex + 1);
                } else {
                    showMenu();
                }
            }
        });
        TextButton list = Cards.smallButton("Levels", skin);
        list.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                showMenu();
            }
        });
        buttons.add(list).width(120).height(50).padRight(10);
        buttons.add(retry).width(120).height(50).padRight(10);
        buttons.add(nextButton).width(180).height(56);
        end.add(buttons);
        result = Cards.center(end);
        result.setVisible(false);
        stage.addActor(result);
    }

    private static final float CARD_ROW = 400f;

    // ---------------------------------------------------------------- levels

    private void buildLevels() {
        // 1: drive straight into a gap in a row of bays
        Level l = new Level();
        l.name = "First Spot";
        l.hint = "Drive forward into the glowing bay.";
        l.startX = 500;
        l.startY = 120;
        l.startHeading = 90;
        row(l, 220, 540, 9, 64, 90, 4);
        kerb(l, 590);
        l.par = 20;
        levels.add(l);

        // 2: reverse in: the arrow points out of the bay
        l = new Level();
        l.name = "Back It In";
        l.hint = "Reverse in: the arrow shows which way the car must face.";
        l.startX = 160;
        l.startY = 300;
        l.startHeading = 0;
        row(l, 260, 540, 8, 64, -90, 5);
        row(l, 260, 100, 8, 64, 90, -1);
        kerb(l, 590);
        kerb(l, 50);
        l.par = 30;
        levels.add(l);

        // 3: angled bays
        l = new Level();
        l.name = "Angled Bays";
        l.hint = "These bays are on a slant. Swing wide, then in.";
        l.startX = 120;
        l.startY = 300;
        l.startHeading = 0;
        for (int i = 0; i < 9; i++) {
            float bx = 260 + i * 62, by = 520;
            l.bays.add(new float[] {bx, by, 120});
            if (i == 5) {
                spot(l, bx, by, 120);
            } else if (i % 3 != 2) {
                l.things.add(new Thing(bx, by, CAR_W, CAR_L, 120 + 180 * (i % 2), 1, i % PARKED.length));
            }
        }
        cones(l, 260, 200, 760, 200, 9);       // a line of cones along the far side of the lane
        kerb(l, 595);
        l.par = 30;
        levels.add(l);

        // 4: parallel parking between two cars at the kerb
        l = new Level();
        l.name = "Parallel Park";
        l.hint = "Pull alongside the gap, then reverse in at an angle.";
        l.startX = 860;
        l.startY = 400;
        l.startHeading = 180;
        l.things.add(new Thing(WORLD_W / 2, 590, WORLD_W, 36, 0, 0, 0));      // the kerb and pavement
        float kerbY = 572 - CAR_W / 2f - 4;
        for (float cx = 110; cx < 940; cx += 74) {
            if (Math.abs(cx - 480) < 60) {
                continue;
            }
            l.things.add(new Thing(cx, kerbY, CAR_L, CAR_W, 180, 1, (int) (cx / 74) % PARKED.length));
        }
        l.spotX = 480;
        l.spotY = kerbY;
        l.spotW = CAR_L + 14;
        l.spotH = CAR_W + 8;
        l.spotAngle = 180;
        l.bays.add(new float[] {480, kerbY, 0, CAR_L + 14, CAR_W + 8});
        l.par = 35;
        levels.add(l);

        // 5: a multi-storey garage with pillars between the bays
        l = new Level();
        l.name = "Pillar Squeeze";
        l.hint = "Narrow bays and concrete pillars. Take it slow.";
        l.garage = true;
        l.startX = 140;
        l.startY = 320;
        l.startHeading = 0;
        for (int i = 0; i < 8; i++) {
            float bx = 250 + i * 46, top = 540, bottom = 100;
            l.bays.add(new float[] {bx, top, 90, 38, 64});
            l.bays.add(new float[] {bx, bottom, -90, 38, 64});
            if (i == 6) {
                l.spotX = bx;
                l.spotY = top;
                l.spotW = 38;
                l.spotAngle = 90;
            } else {
                l.things.add(new Thing(bx, top, CAR_W, CAR_L, 90 + (i % 2) * 180, 1, i % PARKED.length));
            }
            if (i != 3) {
                l.things.add(new Thing(bx, bottom, CAR_W, CAR_L, -90 + (i % 2) * 180, 1, (i + 3) % PARKED.length));
            }
            if (i % 2 == 0) {
                l.things.add(new Thing(bx - 23, top + 26, 12, 12, 0, 0, 0));
                l.things.add(new Thing(bx - 23, bottom - 26, 12, 12, 0, 0, 0));
            }
        }
        garageWalls(l);
        l.par = 35;
        levels.add(l);

        // 6: against the clock through a busy garage, reversing in at the far end
        l = new Level();
        l.name = "Rush Hour Garage";
        l.hint = "45 seconds. Weave through, then back into the bay.";
        l.garage = true;
        l.timeLimit = 45;
        l.startX = 100;
        l.startY = 110;
        l.startHeading = 0;
        garageWalls(l);
        for (int i = 0; i < 6; i++) {
            float bx = 260 + i * 46;
            l.bays.add(new float[] {bx, 230, 90, 40, 64});
            l.things.add(new Thing(bx, 230, CAR_W, CAR_L, 90, 1, i % PARKED.length));
        }
        for (int i = 0; i < 6; i++) {
            float bx = 520 + i * 46;
            l.bays.add(new float[] {bx, 420, -90, 40, 64});
            l.things.add(new Thing(bx, 420, CAR_W, CAR_L, -90, 1, (i + 4) % PARKED.length));
        }
        l.things.add(new Thing(330, 470, 16, 16, 0, 0, 0));
        l.things.add(new Thing(660, 170, 16, 16, 0, 0, 0));
        cones(l, 420, 330, 420, 330, 1);
        cones(l, 560, 300, 640, 300, 3);
        for (int i = 0; i < 5; i++) {
            float by = 150 + i * 70;
            l.bays.add(new float[] {890, by, 0, 64, 40});
            if (i == 3) {
                l.spotX = 890;
                l.spotY = by;
                l.spotW = 64;
                l.spotH = 40;
                l.spotAngle = 180;
            } else {
                l.things.add(new Thing(890, by, CAR_L, CAR_W, i % 2 == 0 ? 0 : 180, 1, (i + 2) % PARKED.length));
            }
        }
        l.par = 30;
        levels.add(l);
    }

    /** a row of bays facing `angle`; bay `gap` is the spot (or -1: all full), some others empty */
    private void row(Level l, float x0, float y, int count, float step, float angle, int gap) {
        for (int i = 0; i < count; i++) {
            float bx = x0 + i * step;
            l.bays.add(new float[] {bx, y, angle});
            if (i == gap) {
                spot(l, bx, y, angle);
            } else if ((i * 7) % 5 != 1 || gap < 0) {
                l.things.add(new Thing(bx, y, CAR_W, CAR_L, angle + ((i % 3 == 0) ? 180 : 0), 1, (i + (int) y) % PARKED.length));
            }
        }
    }

    private void spot(Level l, float x, float y, float angle) {
        l.spotX = x;
        l.spotY = y;
        l.spotAngle = angle;
    }

    private void cones(Level l, float x0, float y0, float x1, float y1, int n) {
        for (int i = 0; i < n; i++) {
            float t = n == 1 ? 0 : i / (float) (n - 1);
            l.things.add(new Thing(MathUtils.lerp(x0, x1, t), MathUtils.lerp(y0, y1, t), 10, 10, 0, 2, 0));
        }
    }

    /** a raised kerb strip behind a row of bays, so you can't drive straight through */
    private void kerb(Level l, float y) {
        l.things.add(new Thing(WORLD_W / 2, y, WORLD_W - 120, 14, 0, 0, 0));
    }

    private void garageWalls(Level l) {
        l.things.add(new Thing(WORLD_W / 2, WORLD_H - 8, WORLD_W, 16, 0, 0, 0));
        l.things.add(new Thing(WORLD_W / 2, 8, WORLD_W, 16, 0, 0, 0));
        l.things.add(new Thing(WORLD_W - 8, WORLD_H / 2, 16, WORLD_H, 0, 0, 0));
    }

    // ---------------------------------------------------------------- playing

    private void start(int index) {
        levelIndex = index;
        level = levels.get(index);
        x = level.startX;
        y = level.startY;
        heading = level.startHeading;
        speed = steer = clock = stillTime = shake = 0;
        bumps = 0;
        finished = false;
        menu.setVisible(false);
        result.setVisible(false);
        hud.setVisible(true);
        title.setText((index + 1) + ". " + level.name.toUpperCase());
        hint.setText(level.hint);
        Sounds.play("click", 0.5f);
    }

    private void showMenu() {
        game.setScreen(new ParkingScreen(game, carNumber));
    }

    private void update(float dt) {
        boolean gas = Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP) || touch[3];
        boolean back = Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN) || touch[2];
        boolean brake = Gdx.input.isKeyPressed(Input.Keys.SPACE);
        float steerIn = (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT) || touch[0] ? 1 : 0)
            - (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT) || touch[1] ? 1 : 0);
        // pedals: gas forward, S brakes then reverses; let go and it rolls to a stop
        if (brake) {
            speed = approach(speed, 0, 400 * dt);
        } else if (gas) {
            speed = speed < 0 ? approach(speed, 0, 300 * dt) : Math.min(MAX_FORWARD, speed + 110 * dt);
        } else if (back) {
            speed = speed > 0 ? approach(speed, 0, 300 * dt) : Math.max(-MAX_REVERSE, speed - 80 * dt);
        } else {
            speed = approach(speed, 0, 70 * dt);
        }
        steer = approach(steer, steerIn * MAX_STEER, 120 * dt);
        // the back wheels follow the front: turn rate from speed and the wheels' angle
        float px = x, py = y, ph = heading;
        heading += speed / WHEELBASE * MathUtils.tanDeg(steer) * MathUtils.radiansToDegrees * dt;
        x += MathUtils.cosDeg(heading) * speed * dt;
        y += MathUtils.sinDeg(heading) * speed * dt;
        if (hits()) {
            x = px;
            y = py;
            heading = ph;
            if (Math.abs(speed) > 12) {
                bumps++;
                shake = 6;
                Sounds.play("hit", 0.6f, MathUtils.random(0.9f, 1.1f));
                if (bumps >= MAX_BUMPS) {
                    end(false, "Three bumps. Your insurance has questions.");
                }
            }
            speed = -speed * 0.2f;
        }
        clock += dt;
        if (level.timeLimit > 0 && clock >= level.timeLimit && !finished) {
            end(false, "Out of time.");
        }
        // parked: fully inside the lines, the right way round, and stopped for a moment
        boolean inside = parked();
        stillTime = inside && Math.abs(speed) < 4 ? stillTime + dt : 0;
        if (stillTime > 0.8f && !finished) {
            int stars = bumps == 0 && clock <= level.par ? 3 : bumps <= 1 ? 2 : 1;
            saveStars(levelIndex, stars);
            end(true, "Parked in " + String.format(java.util.Locale.ROOT, "%.1f", clock) + "s with "
                + bumps + (bumps == 1 ? " bump.  " : " bumps.  ") + starText(stars));
        }
        String time = level.timeLimit > 0 ? "TIME " + Math.max(0, (int) Math.ceil(level.timeLimit - clock))
            : "TIME " + (int) clock + "s";
        status.setText(time + "   BUMPS " + bumps + "/" + MAX_BUMPS + "   " + (speed < -1 ? "R" : "D")
            + (inside ? "   IN THE BAY - STOP" : ""));
        shake *= (float) Math.exp(-10 * dt);
    }

    private void end(boolean won, String text) {
        finished = true;
        speed = 0;
        Sounds.play(won ? "finish" : "back", 0.6f);
        resultTitle.setText(won ? "PARKED!" : "FAILED");
        resultTitle.setColor(won ? Ui.GOLD : Cards.ERROR);
        resultText.setText(text);
        nextButton.setVisible(won);
        nextButton.setText(levelIndex + 1 < levels.size() ? "Next level" : "All done");
        result.setVisible(true);
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    // ---------------------------------------------------------------- geometry

    private final Vector2[] mine = corners(new Vector2[4]), theirs = corners(new Vector2[4]);

    private static Vector2[] corners(Vector2[] c) {
        for (int i = 0; i < 4; i++) {
            c[i] = new Vector2();
        }
        return c;
    }

    /** the four corners of a box of length l (along its angle) and width w */
    private static void box(Vector2[] out, float cx, float cy, float l, float w, float angle) {
        float c = MathUtils.cosDeg(angle), s = MathUtils.sinDeg(angle);
        float[][] k = {{1, 1}, {1, -1}, {-1, -1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            float lx = k[i][0] * l / 2f, ly = k[i][1] * w / 2f;
            out[i].set(cx + lx * c - ly * s, cy + lx * s + ly * c);
        }
    }

    private boolean hits() {
        box(mine, x, y, CAR_L, CAR_W, heading);
        for (Vector2 p : mine) {
            if (p.x < 0 || p.y < 0 || p.x > WORLD_W || p.y > WORLD_H) {
                return true;
            }
        }
        for (Thing t : level.things) {
            // things are stored w across x and h along y before turning; cars are h long
            if (t.kind == 1 && t.h > t.w) {
                box(theirs, t.x, t.y, t.h, t.w, t.angle);
            } else {
                box(theirs, t.x, t.y, t.w, t.h, t.angle);
            }
            if (overlap(mine, theirs)) {
                return true;
            }
        }
        return false;
    }

    /** separating axis test between two boxes */
    private static boolean overlap(Vector2[] a, Vector2[] b) {
        Vector2[][] shapes = {a, b};
        for (Vector2[] s : shapes) {
            for (int i = 0; i < 2; i++) {
                float ax = s[i + 1].x - s[i].x, ay = s[i + 1].y - s[i].y;
                float nx = -ay, ny = ax;
                float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE, minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
                for (Vector2 p : a) {
                    float d = p.x * nx + p.y * ny;
                    minA = Math.min(minA, d);
                    maxA = Math.max(maxA, d);
                }
                for (Vector2 p : b) {
                    float d = p.x * nx + p.y * ny;
                    minB = Math.min(minB, d);
                    maxB = Math.max(maxB, d);
                }
                if (maxA < minB || maxB < minA) {
                    return false;
                }
            }
        }
        return true;
    }

    /** inside the bay with every corner, and facing within 12 degrees of the arrow */
    private boolean parked() {
        box(mine, x, y, CAR_L, CAR_W, heading);
        float c = MathUtils.cosDeg(level.spotAngle), s = MathUtils.sinDeg(level.spotAngle);
        // the bay's long side runs along spotAngle for upright bays, across it for parallel ones
        float along = Math.max(level.spotW, level.spotH), across = Math.min(level.spotW, level.spotH);
        for (Vector2 p : mine) {
            float dx = p.x - level.spotX, dy = p.y - level.spotY;
            float u = dx * c + dy * s, v = -dx * s + dy * c;
            if (Math.abs(u) > along / 2f || Math.abs(v) > across / 2f) {
                return false;
            }
        }
        float diff = ((heading - level.spotAngle) % 360 + 540) % 360 - 180;
        return Math.abs(diff) < 12;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(float delta) {
        delta = Math.min(delta, 0.05f);
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (level != null && !finished) {
                showMenu();
                return;
            }
            game.setScreen(new MapSelectScreen(game, carNumber));
            return;
        }
        if (level != null && !finished && !menu.isVisible()) {
            update(delta);
        }
        Gdx.gl.glClearColor(0.08f, 0.08f, 0.09f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (level != null && !menu.isVisible()) {
            world.apply();
            camera.position.set(WORLD_W / 2f + MathUtils.random(-shake, shake), WORLD_H / 2f + MathUtils.random(-shake, shake), 0);
            camera.update();
            drawLevel();
        }
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }

    private void drawLevel() {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        Texture ground = level.garage ? concrete : floor;
        batch.draw(ground, 0, 0, WORLD_W, WORLD_H, 0, 0, (int) WORLD_W / 2, (int) WORLD_H / 2, false, false);
        batch.end();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(camera.combined);
        // painted bay lines
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        Color line = level.garage ? new Color(0.95f, 0.8f, 0.2f, 1f) : new Color(0.95f, 0.95f, 0.92f, 1f);
        for (float[] b : level.bays) {
            // {x, y, angle} is a standard bay; {x, y, angle, a, b} gives its two sides
            float along = b.length > 4 ? Math.max(b[3], b[4]) : 68, across = b.length > 4 ? Math.min(b[3], b[4]) : 40;
            bayLines(b[0], b[1], along, across, b[2], line);
        }
        // the target bay: a pulsing green fill, its outline and an arrow for the way to face
        float pulse = 0.35f + 0.2f * MathUtils.sin(clock * 5f);
        float along = Math.max(level.spotW, level.spotH), across = Math.min(level.spotW, level.spotH);
        shapes.setColor(0.2f, 0.9f, 0.4f, pulse);
        shapes.rect(level.spotX - along / 2f, level.spotY - across / 2f, along / 2f, across / 2f, along, across, 1, 1, level.spotAngle);
        shapes.setColor(0.3f, 1f, 0.5f, 0.9f);
        float ac = MathUtils.cosDeg(level.spotAngle), as = MathUtils.sinDeg(level.spotAngle);
        float tipX = level.spotX + ac * along * 0.32f, tipY = level.spotY + as * along * 0.32f;
        float baseX = level.spotX - ac * along * 0.05f, baseY = level.spotY - as * along * 0.05f;
        shapes.triangle(tipX, tipY, baseX - as * across * 0.3f, baseY + ac * across * 0.3f,
            baseX + as * across * 0.3f, baseY - ac * across * 0.3f);
        shapes.rectLine(level.spotX - ac * along * 0.3f, level.spotY - as * along * 0.3f, baseX, baseY, across * 0.18f);
        // walls, pillars and cones
        for (Thing t : level.things) {
            if (t.kind == 0) {
                shapes.setColor(0.25f, 0.25f, 0.27f, 1f);
                shapes.rect(t.x - t.w / 2f, t.y - t.h / 2f, t.w / 2f, t.h / 2f, t.w, t.h, 1, 1, t.angle);
                if (t.w < 30 && t.h < 30) {
                    // pillars wear yellow and black hazard stripes
                    shapes.setColor(0.95f, 0.8f, 0.2f, 1f);
                    shapes.rect(t.x - t.w / 2f, t.y - t.h / 2f, t.w, t.h * 0.3f);
                    shapes.rect(t.x - t.w / 2f, t.y + t.h * 0.2f, t.w, t.h * 0.3f);
                }
            } else if (t.kind == 2) {
                shapes.setColor(0.95f, 0.45f, 0.1f, 1f);
                shapes.circle(t.x, t.y, t.w / 2f + 1, 12);
                shapes.setColor(1f, 1f, 1f, 1f);
                shapes.circle(t.x, t.y, t.w / 4f, 10);
            }
        }
        shapes.end();

        batch.begin();
        for (Thing t : level.things) {
            if (t.kind != 1) {
                continue;
            }
            float l = Math.max(t.w, t.h), w = Math.min(t.w, t.h);
            drawCar(parkedArt[t.sprite], t.x, t.y, l, w, t.angle);
        }
        batch.setColor(0f, 0f, 0f, 0.35f);
        drawCar(carArt, x + 3, y - 3, CAR_L, CAR_W, heading);
        batch.setColor(Color.WHITE);
        drawCar(carArt, x, y, CAR_L, CAR_W, heading);
        batch.end();
    }

    /** a car sprite (drawn pointing up) centred at x, y, pointing at angle */
    private void drawCar(Texture art, float cx, float cy, float l, float w, float angle) {
        batch.draw(art, cx - w / 2f, cy - l / 2f, w / 2f, l / 2f, w, l, 1, 1, angle - 90, 0, 0,
            art.getWidth(), art.getHeight(), false, false);
    }

    /** a bay's three painted lines (open on the side it's entered from) */
    private void bayLines(float cx, float cy, float along, float across, float angle, Color color) {
        shapes.setColor(color);
        float c = MathUtils.cosDeg(angle), s = MathUtils.sinDeg(angle);
        float t = 2.5f;
        // the two side lines run the bay's length; the end line closes the far end
        for (int side = -1; side <= 1; side += 2) {
            float ox = -s * side * across / 2f, oy = c * side * across / 2f;
            shapes.rectLine(cx + ox - c * along / 2f, cy + oy - s * along / 2f, cx + ox + c * along / 2f, cy + oy + s * along / 2f, t);
        }
        shapes.rectLine(cx + c * along / 2f - s * across / 2f, cy + s * along / 2f + c * across / 2f,
            cx + c * along / 2f + s * across / 2f, cy + s * along / 2f - c * across / 2f, t);
    }

    // ---------------------------------------------------------------- bits

    private static Texture load(String path) {
        Texture t = new Texture(Gdx.files.internal(path));
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    /** a speckled asphalt / concrete tile */
    private static Texture noise(float r, float g, float b) {
        Pixmap p = new Pixmap(64, 64, Pixmap.Format.RGBA8888);
        java.util.Random rnd = new java.util.Random(5);
        for (int py = 0; py < 64; py++) {
            for (int px = 0; px < 64; px++) {
                float f = 1f + (rnd.nextFloat() - 0.5f) * 0.12f;
                p.setColor(r * f, g * f, b * f, 1f);
                p.drawPixel(px, py);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        return t;
    }

    private static String starText(int stars) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            s.append(i < stars ? '*' : '-');
        }
        return s.toString();
    }

    private static Preferences prefs() {
        try {
            return Gdx.app.getPreferences("var-races");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int bestStars(int level) {
        Preferences p = prefs();
        return p == null ? 0 : p.getInteger("parking" + level, 0);
    }

    private static void saveStars(int level, int stars) {
        Preferences p = prefs();
        if (p != null && stars > bestStars(level)) {
            p.putInteger("parking" + level, stars);
            p.flush();
        }
    }

    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        world.update(width, height, true);
    }

    public void hide() {
        dispose();
    }

    public void dispose() {
        stage.dispose();
        skin.dispose();
        batch.dispose();
        shapes.dispose();
        carArt.dispose();
        floor.dispose();
        concrete.dispose();
        for (Texture t : parkedArt) {
            t.dispose();
        }
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
