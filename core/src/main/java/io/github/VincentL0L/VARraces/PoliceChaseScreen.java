package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel;

/**
 * Police Chase: an endless getaway down a three-lane city street, seen from behind the car
 * (third person). Switch lanes round slower traffic, jump the road barriers, hit the ramps
 * for air and grab the coins. Clip something while changing lanes and you stumble: the cop
 * closes right in behind you, and a second stumble before you've shaken him (or anything
 * head on) and you're caught. The street keeps getting faster.
 *
 * The world runs along +y: lanes at x = -LANE, 0, +LANE, buildings past the pavements.
 * Everything ahead is made up as you go and forgotten once it's behind.
 */
public class PoliceChaseScreen implements Screen {
    private static final float LANE = 26, ROAD_HALF = 42, WALK = 24, CAR_W = 12, CAR_L = 24;
    private static final float ROW = 70, AHEAD = 1100;
    private static final float GRAVITY = 420, JUMP = 150;

    /** something on the street: kind TRAFFIC, BARRIER, RAMP, COIN, NITRO, MAGNET */
    private static final int TRAFFIC = 0, BARRIER = 1, RAMP = 2, COIN = 3, NITRO = 4, MAGNET = 5;

    private static final class Thing {
        int kind, lane, sprite;
        float x, y, z, speed;
        boolean gone;
    }

    /** a stretch of buildings on both sides, as one mesh */
    private static final class Block {
        float y0, y1;
        final List<Mesh> meshes = new ArrayList<>();
    }

    private static final String[] TRAFFIC_ART = {"ui/car4.png", "ui/car5.png", "ui/car6.png", "ui/cab.png", "ui/waymo.png",
        "ui/kei.png", "ui/2cv.png", "ui/bus.png", "ui/cars/roadster_1.png", "ui/cars/hauler_2.png"};

    private final Game game;
    private final int carNumber;
    private final PerspectiveCamera cam = new PerspectiveCamera(66f, 640, 360);
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final ShaderProgram fog = new ShaderProgram(Scenery3D.VERTEX, Scenery3D.FRAGMENT);
    private final Matrix4 m = new Matrix4(), screen = new Matrix4();
    private final Texture carArt, copArt, road, facade, white;
    private final Texture[] trafficArt = new Texture[TRAFFIC_ART.length];
    private final java.util.Map<Texture, Color> paint = new java.util.HashMap<>();
    private final java.util.Map<Texture, CarModels3D.Shape> shape = new java.util.HashMap<>();
    private final List<Thing> things = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final java.util.Random rnd = new java.util.Random();
    private final Stage stage = new Stage(Ui.viewport());
    private final Skin skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
    private final Table startCard, endCard;
    private final Label scoreLabel, coinLabel, bestLabel, powerLabel, endText;
    private final Color skyTop = new Color(0.27f, 0.52f, 0.9f, 1f), skyLow = new Color(0.78f, 0.87f, 0.97f, 1f);

    private boolean running, over;
    private int lane = 1, prevLane = 1, coins;
    private float x, y, z, vz, speed, distance, clock, camX;
    private float stumble, nitro, magnet, copGap = 140, nextRow, nextBlock, shake, touchX, touchY;
    private boolean touching;
    private long caughtAt;

    public PoliceChaseScreen(Game game, int carNumber) {
        this.game = game;
        this.carNumber = carNumber;
        carArt = load(CarModel.sprite(carNumber));
        copArt = load("ui/cop.png");
        for (int i = 0; i < TRAFFIC_ART.length; i++) {
            trafficArt[i] = load(TRAFFIC_ART[i]);
        }
        road = roadTexture();
        facade = Scenery3D.makeFacade();
        Pixmap p = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        p.setColor(Color.WHITE);
        p.fill();
        white = new Texture(p);
        p.dispose();
        cam.near = 0.5f;
        cam.far = 1500f;
        cam.up.set(0, 0, 1);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Sounds.clickSounds(stage);
        Gdx.input.setInputProcessor(stage);
        Sounds.music(Sounds.RACE, true);

        // the HUD: score and coins, best, and what power-up is running
        Table hud = new Table();
        hud.setFillParent(true);
        hud.top().pad(12);
        Table strip = new Table();
        strip.setBackground(Ui.solid(new Color(0.06f, 0.05f, 0.04f, 0.85f)));
        strip.pad(8, 14, 8, 14);
        scoreLabel = new Label("0", new Label.LabelStyle(Ui.display(22), Ui.GOLD));
        coinLabel = new Label("COINS 0", new Label.LabelStyle(Ui.font(12), new Color(1f, 0.85f, 0.3f, 1f)));
        bestLabel = new Label("BEST " + best(), new Label.LabelStyle(Ui.font(11), Color.WHITE));
        powerLabel = new Label("", new Label.LabelStyle(Ui.font(12), new Color(0.5f, 0.9f, 1f, 1f)));
        strip.add(scoreLabel).left().padRight(18);
        strip.add(coinLabel).left().padRight(18);
        strip.add(powerLabel).expandX().left();
        strip.add(bestLabel).right().padRight(12);
        TextButton quit = Cards.smallButton("Quit", skin);
        quit.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                game.setScreen(new MapSelectScreen(game, carNumber));
            }
        });
        strip.add(quit).width(90).height(34);
        hud.add(strip).expandX().fillX();
        stage.addActor(hud);

        Table start = Cards.card();
        start.add(Cards.kicker("SINGLE PLAYER")).left().row();
        start.add(Cards.title("POLICE CHASE", 34)).left().padBottom(8).row();
        Label rules = Cards.text("Outrun the cops down an endless city street.\n"
            + "LEFT / RIGHT (or swipe) to change lanes, UP or SPACE (or swipe up) to jump.\n"
            + "Dodge traffic, jump the barriers, hit the ramps, grab the coins.\n"
            + "Clip something changing lanes and the cop closes in. Twice and you're caught.", false);
        rules.setWrap(true);
        start.add(rules).left().padBottom(14).row();
        TextButton go = new TextButton("Floor it", skin);
        go.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                begin();
            }
        });
        start.add(go).width(220).height(60);
        startCard = Cards.center(start);
        stage.addActor(startCard);

        Table end = Cards.card();
        end.add(Cards.title("BUSTED!", 36)).padBottom(8).row();
        endText = Cards.text("", false);
        end.add(endText).padBottom(14).row();
        Table buttons = new Table();
        TextButton again = new TextButton("Run again", skin);
        again.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                // a moment's pause first, so a tap still held from the crash doesn't skip the result
                if (System.currentTimeMillis() - caughtAt > 800) {
                    begin();
                }
            }
        });
        TextButton menu = Cards.smallButton("Menu", skin);
        menu.addListener(new ClickListener() {
            public void clicked(InputEvent e, float bx, float by) {
                game.setScreen(new MapSelectScreen(game, carNumber));
            }
        });
        buttons.add(menu).width(130).height(52).padRight(12);
        buttons.add(again).width(200).height(60);
        end.add(buttons);
        endCard = Cards.center(end);
        endCard.setVisible(false);
        stage.addActor(endCard);
        reset();
    }

    // ---------------------------------------------------------------- the run

    private void reset() {
        things.clear();
        for (Block b : blocks) {
            for (Mesh mesh : b.meshes) {
                mesh.dispose();
            }
        }
        blocks.clear();
        lane = prevLane = 1;
        x = camX = 0;
        y = z = vz = distance = clock = 0;
        speed = 170;
        coins = 0;
        stumble = nitro = magnet = shake = 0;
        copGap = 30;     // starts right behind you (in view), then drops back
        nextRow = 260;
        nextBlock = -300;
        generate();
    }

    private void begin() {
        reset();
        running = true;
        over = false;
        startCard.setVisible(false);
        endCard.setVisible(false);
        Sounds.play("go", 0.6f);
    }

    private void caught() {
        running = false;
        over = true;
        caughtAt = System.currentTimeMillis();
        copGap = 16;
        shake = 8;
        Sounds.play("crash", 0.7f);
        int score = score();
        boolean record = score > best();
        if (record) {
            Preferences p = prefs();
            if (p != null) {
                p.putInteger("chaseBest", score);
                p.flush();
            }
        }
        bestLabel.setText("BEST " + best());
        endText.setText("Score " + score + "   |   " + (int) (distance / 10) + " m   |   " + coins + " coins"
            + (record ? "\nNEW BEST!" : ""));
        endCard.setVisible(true);
    }

    private int score() {
        return (int) (distance / 10) + coins * 10;
    }

    private void update(float dt) {
        clock += dt;
        // steering: switch lanes (keys or a swipe), jump
        boolean left = Gdx.input.isKeyJustPressed(Input.Keys.LEFT) || Gdx.input.isKeyJustPressed(Input.Keys.A);
        boolean right = Gdx.input.isKeyJustPressed(Input.Keys.RIGHT) || Gdx.input.isKeyJustPressed(Input.Keys.D);
        boolean jump = Gdx.input.isKeyJustPressed(Input.Keys.UP) || Gdx.input.isKeyJustPressed(Input.Keys.W)
            || Gdx.input.isKeyJustPressed(Input.Keys.SPACE);
        if (Gdx.input.isTouched()) {
            if (!touching) {
                touching = true;
                touchX = Gdx.input.getX();
                touchY = Gdx.input.getY();
            } else {
                float dx = Gdx.input.getX() - touchX, dy = Gdx.input.getY() - touchY;
                float swipe = 40f * Ui.density;
                if (Math.abs(dx) > swipe || Math.abs(dy) > swipe) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        left |= dx < 0;
                        right |= dx > 0;
                    } else {
                        jump |= dy < 0;
                    }
                    touchX = Gdx.input.getX();
                    touchY = Gdx.input.getY() - 10000;     // one swipe, one move
                }
            }
        } else {
            touching = false;
        }
        if (left && lane > 0) {
            prevLane = lane--;
        }
        if (right && lane < 2) {
            prevLane = lane++;
        }
        if (jump && z <= 0.01f) {
            vz = JUMP;
            Sounds.play("item_land", 0.4f, 1.3f);
        }
        float targetX = (lane - 1) * LANE;
        x = MathUtils.lerp(x, targetX, 1f - (float) Math.exp(-14 * dt));
        vz -= GRAVITY * dt;
        z = Math.max(0, z + vz * dt);
        if (z == 0) {
            vz = Math.max(0, vz);
        }

        // faster and faster; nitro faster still
        speed = Math.min(360, 170 + distance * 0.0045f) * (nitro > 0 ? 1.45f : 1f) * (stumble > 3.5f ? 0.8f : 1f);
        y += speed * dt;
        distance += speed * dt;
        nitro = Math.max(0, nitro - dt);
        magnet = Math.max(0, magnet - dt);
        stumble = Math.max(0, stumble - dt);
        // the cop: right on your bumper after a stumble, dropping back once you're clear
        float wantGap = stumble > 0 ? 30 : 160;
        copGap += (wantGap - copGap) * Math.min(1f, dt * (stumble > 0 ? 3f : 0.4f));

        for (Thing t : things) {
            if (t.kind == TRAFFIC) {
                t.y += t.speed * dt;
            }
            if (t.kind == COIN && magnet > 0 && Math.abs(t.y - y) < 140) {
                t.x += (x - t.x) * Math.min(1, dt * 8);
                t.y += (y - t.y) * Math.min(1, dt * 4);
                t.z += (z + 6 - t.z) * Math.min(1, dt * 6);
            }
        }
        collide();
        // tidy away what's behind and make up what's ahead
        Iterator<Thing> it = things.iterator();
        while (it.hasNext()) {
            Thing t = it.next();
            if (t.gone || t.y < y - 120) {
                it.remove();
            }
        }
        Iterator<Block> bi = blocks.iterator();
        while (bi.hasNext()) {
            Block b = bi.next();
            if (b.y1 < y - 200) {
                for (Mesh mesh : b.meshes) {
                    mesh.dispose();
                }
                bi.remove();
            }
        }
        generate();
        scoreLabel.setText(String.valueOf(score()));
        coinLabel.setText("COINS " + coins);
        powerLabel.setText(nitro > 0 ? "NITRO " + (int) Math.ceil(nitro) : magnet > 0 ? "MAGNET " + (int) Math.ceil(magnet)
            : stumble > 0 ? "THE COP'S RIGHT BEHIND YOU!" : "");
        powerLabel.setColor(stumble > 0 && nitro <= 0 && magnet <= 0 ? Cards.ERROR : new Color(0.5f, 0.9f, 1f, 1f));
        shake *= (float) Math.exp(-8 * dt);
    }

    private void collide() {
        boolean changing = Math.abs(x - (lane - 1) * LANE) > 5;
        for (Thing t : things) {
            if (t.gone) {
                continue;
            }
            float dx = Math.abs(t.x - x), dy = t.y - y;
            if (t.kind == COIN) {
                if (dx < 10 && Math.abs(dy) < 14 && Math.abs(t.z - (z + 6)) < 12) {
                    t.gone = true;
                    coins++;
                    Sounds.play("item_pickup", 0.25f, 1.4f + (coins % 5) * 0.05f);
                }
                continue;
            }
            if (t.kind == NITRO || t.kind == MAGNET) {
                if (dx < 12 && Math.abs(dy) < 16 && z < 14) {
                    t.gone = true;
                    if (t.kind == NITRO) {
                        nitro = 6;
                        Sounds.play("nitro", 0.6f);
                    } else {
                        magnet = 10;
                        Sounds.play("shield", 0.5f);
                    }
                }
                continue;
            }
            if (t.kind == RAMP) {
                // drive up the ramp and get launched off the end
                if (dx < 13 && dy < 0 && dy > -36 && z < 4) {
                    z = (dy + 36) / 36f * 10f;
                    vz = Math.max(vz, 175);
                }
                continue;
            }
            float length = t.kind == TRAFFIC ? (t.sprite == 7 ? 34 : CAR_L) : 6;
            float height = t.kind == TRAFFIC ? (t.sprite == 7 ? 22 : 8) : 7;
            boolean overlapY = Math.abs(dy) < (length + CAR_L) / 2f, overlapX = dx < (CAR_W + (t.kind == BARRIER ? 22 : CAR_W)) / 2f - 1;
            if (!overlapX || !overlapY || z > height) {
                continue;
            }
            if (nitro > 0) {
                // nitro smashes straight through
                t.gone = true;
                shake = 4;
                Sounds.play("hit", 0.5f, 1.2f);
                continue;
            }
            if (changing && t.lane == lane && t.lane != prevLane) {
                // clipped it changing lanes: bounce back and stumble
                lane = prevLane;
                shake = 5;
                Sounds.play("hit", 0.6f);
                if (stumble > 0) {
                    caught();
                    return;
                }
                stumble = 5;
                Sounds.play("pulse", 0.5f, 0.7f);
            } else {
                caught();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- making the street

    private void generate() {
        while (nextRow < y + AHEAD) {
            spawnRow(nextRow);
            float hard = Math.min(1f, distance / 9000f);
            nextRow += ROW * (1.25f - hard * 0.4f) * (0.8f + rnd.nextFloat() * 0.5f);
        }
        while (nextBlock < y + AHEAD + 200) {
            buildBlock(nextBlock, nextBlock + 400);
            nextBlock += 400;
        }
    }

    private void spawnRow(float ry) {
        float hard = Math.min(1f, distance / 9000f);
        int free = rnd.nextInt(3);            // one lane always stays open
        boolean ramped = false;
        for (int l = 0; l < 3; l++) {
            float r = rnd.nextFloat();
            if (l == free) {
                if (r < 0.35f) {
                    coinLine(l, ry, 5, 0);
                } else if (r < 0.40f) {
                    add(rnd.nextBoolean() ? NITRO : MAGNET, l, ry, 4);
                }
                continue;
            }
            if (r < 0.32f + 0.22f * hard) {
                Thing t = add(TRAFFIC, l, ry, 0);
                t.sprite = rnd.nextInt(trafficArt.length);
                t.speed = 40 + rnd.nextFloat() * 40;
            } else if (r < 0.5f + 0.15f * hard) {
                add(BARRIER, l, ry, 0);
                if (rnd.nextFloat() < 0.5f) {
                    coinLine(l, ry - 10, 3, 1);
                }
            } else if (!ramped && r < 0.56f) {
                ramped = true;
                add(RAMP, l, ry, 0);
                coinLine(l, ry + 30, 6, 2);
            } else if (r < 0.75f) {
                coinLine(l, ry, 4, 0);
            }
        }
    }

    private Thing add(int kind, int l, float ty, float tz) {
        Thing t = new Thing();
        t.kind = kind;
        t.lane = l;
        t.x = (l - 1) * LANE;
        t.y = ty;
        t.z = tz;
        things.add(t);
        return t;
    }

    /** a trail of coins along a lane: flat, hopping over a barrier, or an arc off a ramp */
    private void coinLine(int l, float y0, int n, int style) {
        for (int i = 0; i < n; i++) {
            float z0 = 6;
            if (style == 1) {
                z0 = 6 + 26 * MathUtils.sin(MathUtils.PI * (i + 0.5f) / n);
            } else if (style == 2) {
                z0 = 18 + 40 * MathUtils.sin(MathUtils.PI * (i + 0.5f) / n);
            }
            add(COIN, l, y0 + i * 18, z0);
        }
    }

    /** buildings down both sides of a stretch of street: windows, a colour each, roofs, and gaps */
    private void buildBlock(float y0, float y1) {
        Block b = new Block();
        b.y0 = y0;
        b.y1 = y1;
        Scenery3D.Builder walls = new Scenery3D.Builder(b.meshes);
        Color[] paints = {new Color(0.93f, 0.86f, 0.74f, 1f), new Color(0.78f, 0.84f, 0.92f, 1f), new Color(0.92f, 0.76f, 0.7f, 1f),
            new Color(0.8f, 0.9f, 0.8f, 1f), new Color(0.88f, 0.88f, 0.88f, 1f), new Color(0.95f, 0.9f, 0.6f, 1f)};
        for (int side = -1; side <= 1; side += 2) {
            float yy = y0;
            while (yy < y1) {
                float len = 50 + rnd.nextInt(5) * 20, h = 40 + rnd.nextInt(8) * 14;
                float front = side * (ROAD_HALF + WALK), back = side * (ROAD_HALF + WALK + 70 + rnd.nextInt(3) * 20);
                Color c = paints[rnd.nextInt(paints.length)];
                float ya = yy, yb = Math.min(y1, yy + len);
                // the face to the street, the end walls and the roof
                quadWall(walls, front, ya, front, yb, h, c, 1f);
                quadWall(walls, back, ya, front, ya, h, c, 0.7f);
                quadWall(walls, front, yb, back, yb, h, c, 0.8f);
                float u = 0.5f;
                walls.quad(Math.min(front, back), ya, h, Math.max(front, back), ya, h, Math.max(front, back), yb, h,
                    Math.min(front, back), yb, h, u, u, u, u, u, u, u, u, new Color(c).mul(0.6f, 0.6f, 0.62f, 1f));
                yy = yb + (rnd.nextInt(4) == 0 ? 16 : 2);
            }
        }
        walls.finish();
        blocks.add(b);
    }

    private static void quadWall(Scenery3D.Builder w, float ax, float ay, float bx, float by, float h, Color paint, float shade) {
        float ua = (ax == bx ? ay : ax) / 64f, ub = (ax == bx ? by : bx) / 64f;
        Color c = new Color(paint.r * shade, paint.g * shade, paint.b * shade, 1f);
        w.quad(ax, ay, 0, bx, by, 0, bx, by, h, ax, ay, h, ua, 0, ub, 0, ub, -h / 48f, ua, -h / 48f, c);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void render(float delta) {
        delta = Math.min(delta, 0.05f);
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            game.setScreen(new MapSelectScreen(game, carNumber));
            return;
        }
        if (running) {
            update(delta);
        } else if (!over && (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) || Gdx.input.isKeyJustPressed(Input.Keys.ENTER))) {
            begin();
        }
        // the chase camera: behind and above, swinging gently after the car
        camX = MathUtils.lerp(camX, x * 0.75f, 1f - (float) Math.exp(-6 * delta));
        float sx = MathUtils.random(-shake, shake), sz = MathUtils.random(-shake, shake) * 0.5f;
        cam.position.set(camX + sx, y - 58, 30 + z * 0.4f + sz);
        cam.lookAt(camX * 0.6f + x * 0.4f, y + 70, 6 + z * 0.3f);
        cam.up.set(0, 0, 1);
        cam.update();

        Gdx.gl.glClearColor(skyLow.r, skyLow.g, skyLow.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        drawSky();
        drawGround();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        drawBuildings();
        drawThings(delta);
        drawCar(carArt, x, y, z, 0, nitro > 0);
        drawCar(copArt, MathUtils.lerp(x, 0, 0.3f), y - copGap, 0, 0, false);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }

    private void prepareFog() {
        fog.bind();
        fog.setUniformf("u_eye", cam.position);
        fog.setUniformf("u_fogRange", 450f, 1050f);
        fog.setUniformf("u_fogColor", skyLow.r, skyLow.g, skyLow.b);
        fog.setUniformf("u_cutout", 0f);
    }

    private void drawSky() {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        screen.setToOrtho2D(0, 0, w, h);
        Gdx.gl.glViewport(0, 0, w, h);
        shapes.setProjectionMatrix(screen);
        shapes.setTransformMatrix(m.idt());
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.rect(0, h * 0.45f, w, h * 0.55f, skyLow, skyLow, skyTop, skyTop);
        shapes.end();
    }

    private void drawGround() {
        prepareFog();
        batch.setShader(fog);
        batch.setProjectionMatrix(cam.combined);
        batch.setTransformMatrix(m.idt());
        batch.begin();
        float y0 = y - 120, len = AHEAD + 300;
        // the land either side (pavement grey), then the road with its lanes and kerbs
        batch.setColor(0.55f, 0.55f, 0.56f, 1f);
        batch.draw(white, -700, y0, 1400, len);
        batch.setColor(Color.WHITE);
        float width = (ROAD_HALF + WALK) * 2;
        batch.draw(road, -width / 2f, y0, width, len, 0f, -y0 / 64f, 1f, -(y0 + len) / 64f);
        batch.end();
        batch.setShader(null);
    }

    private void drawBuildings() {
        prepareFog();
        fog.setUniformMatrix("u_projTrans", cam.combined);
        fog.setUniformi("u_texture", 0);
        facade.bind(0);
        for (Block b : blocks) {
            for (Mesh mesh : b.meshes) {
                mesh.render(fog, GL20.GL_TRIANGLES);
            }
        }
    }

    private void drawThings(float delta) {
        for (Thing t : things) {
            if (t.y > y + AHEAD || t.y < y - 80) {
                continue;
            }
            if (t.kind == TRAFFIC) {
                drawCar(trafficArt[t.sprite], t.x, t.y, 0, 0, false);
                continue;
            }
            m.idt().translate(t.x, t.y, t.z);
            if (t.kind == COIN || t.kind == NITRO || t.kind == MAGNET) {
                m.rotate(0, 0, 1, clock * 200 + t.y);
            }
            shapes.setProjectionMatrix(cam.combined);
            shapes.setTransformMatrix(m);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            switch (t.kind) {
                case BARRIER:
                    // a striped road block on two legs
                    for (int k = 0; k < 4; k++) {
                        shapes.setColor(k % 2 == 0 ? new Color(0.9f, 0.15f, 0.12f, 1f) : Color.WHITE);
                        CarModels3D.box(shapes, -11 + k * 5.5f, -1.5f, 3.5f, 5.5f, 3f, 3.5f);
                    }
                    shapes.setColor(0.3f, 0.3f, 0.32f, 1f);
                    CarModels3D.box(shapes, -10, -1, 0, 1.5f, 2, 3.5f);
                    CarModels3D.box(shapes, 8.5f, -1, 0, 1.5f, 2, 3.5f);
                    break;
                case RAMP:
                    // a yellow ramp rising toward its far end (stacked slabs)
                    for (int k = 0; k < 6; k++) {
                        shapes.setColor(k % 2 == 0 ? new Color(0.98f, 0.8f, 0.15f, 1f) : new Color(0.15f, 0.15f, 0.17f, 1f));
                        CarModels3D.box(shapes, -12, -36 + k * 6, 0, 24, 6, (k + 1) * 10f / 6f);
                    }
                    break;
                case COIN:
                    shapes.setColor(1f, 0.82f, 0.2f, 1f);
                    CarModels3D.box(shapes, -3.5f, -0.6f, -3.5f, 7, 1.2f, 7);
                    shapes.setColor(1f, 0.95f, 0.6f, 1f);
                    CarModels3D.box(shapes, -1.5f, -0.7f, -1.5f, 3, 1.4f, 3);
                    break;
                default:
                    // power-ups: a glowing box, cyan for nitro, purple for the magnet
                    if (t.kind == NITRO) {
                        shapes.setColor(0.3f, 0.85f, 1f, 1f);
                    } else {
                        shapes.setColor(0.75f, 0.4f, 1f, 1f);
                    }
                    CarModels3D.box(shapes, -4, -4, -4, 8, 8, 8);
                    shapes.setColor(1f, 1f, 1f, 1f);
                    CarModels3D.box(shapes, -2, -4.2f, -2, 4, 8.4f, 4);
                    break;
            }
            shapes.end();
        }
    }

    /** a car built to its own shape (CarModels3D) with its paint job on top, pointing up the street */
    private void drawCar(Texture art, float cx, float cy, float cz, float turn, boolean glow) {
        CarModels3D.Shape sh = shape.get(art);
        if (sh == null) {
            sh = CarModels3D.shapeOf(art);
            shape.put(art, sh);
        }
        float w = CAR_W, h = CAR_L;
        if (art == trafficArt[7]) {
            w = 14;
            h = 34;     // the double-decker's bigger
        }
        Color body = new Color(paintOf(art));
        if (glow) {
            body.lerp(new Color(0.4f, 0.9f, 1f, 1f), 0.4f + 0.2f * MathUtils.sin(clock * 20));
        }
        m.idt().translate(cx, cy, cz).rotate(0, 0, 1, turn);
        shapes.setProjectionMatrix(cam.combined);
        shapes.setTransformMatrix(m);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        CarModels3D.draw(shapes, sh, w, h, body, false, clock);
        shapes.end();
        if (!sh.has("BIKE")) {
            m.translate(0, 0, sh.deck + 0.02f);
            batch.setProjectionMatrix(cam.combined);
            batch.setTransformMatrix(m);
            batch.begin();
            batch.draw(art, -w / 2f, -h / 2f, w, h);
            batch.end();
        }
    }

    private Color paintOf(Texture art) {
        Color c = paint.get(art);
        if (c != null) {
            return c;
        }
        c = new Color(0.5f, 0.5f, 0.5f, 1f);
        try {
            if (art.getTextureData() instanceof FileTextureData) {
                Pixmap p = new Pixmap(((FileTextureData) art.getTextureData()).getFileHandle());
                float r = 0, g = 0, b = 0;
                int n = 0;
                Color px = new Color();
                for (int yy = p.getHeight() / 3; yy < p.getHeight() * 2 / 3; yy++) {
                    for (int xx = 0; xx < p.getWidth(); xx++) {
                        Color.rgba8888ToColor(px, p.getPixel(xx, yy));
                        if (px.a > 0.5f && px.r + px.g + px.b > 0.25f) {
                            r += px.r;
                            g += px.g;
                            b += px.b;
                            n++;
                        }
                    }
                }
                p.dispose();
                if (n > 0) {
                    c.set(r / n, g / n, b / n, 1f);
                }
            }
        } catch (RuntimeException e) {
            // keep grey
        }
        paint.put(art, c);
        return c;
    }

    /** the street, across its width: pavements, kerbs, asphalt and dashed lane lines (repeats along y) */
    private static Texture roadTexture() {
        int w = 132, h = 64;
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        java.util.Random r = new java.util.Random(4);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float u = px / (float) w * (ROAD_HALF + WALK) * 2 - (ROAD_HALF + WALK);
                float n = (r.nextFloat() - 0.5f) * 0.05f;
                Color c;
                if (Math.abs(u) > ROAD_HALF + 2) {
                    boolean slab = (py % 16 == 0) || ((int) Math.abs(u) % 12 == 0);
                    c = slab ? new Color(0.6f, 0.6f, 0.6f, 1f) : new Color(0.72f + n, 0.72f + n, 0.7f + n, 1f);
                } else if (Math.abs(u) > ROAD_HALF) {
                    c = (py / 8) % 2 == 0 ? new Color(0.85f, 0.2f, 0.17f, 1f) : Color.WHITE;   // the kerb
                } else if (Math.abs(Math.abs(u) - LANE / 2f) < 1.2f && py % 32 < 18) {
                    c = new Color(0.95f, 0.95f, 0.92f, 1f);                                    // lane dashes
                } else {
                    c = new Color(0.27f + n, 0.27f + n, 0.29f + n, 1f);
                }
                p.setColor(c);
                p.drawPixel(px, py);
            }
        }
        Texture t = new Texture(p);
        p.dispose();
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.Repeat);
        return t;
    }

    private static Texture load(String path) {
        Texture t = new Texture(Gdx.files.internal(path));
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    private static Preferences prefs() {
        try {
            return Gdx.app.getPreferences("var-races");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int best() {
        Preferences p = prefs();
        return p == null ? 0 : p.getInteger("chaseBest", 0);
    }

    public void resize(int width, int height) {
        cam.viewportWidth = Math.max(1, width);
        cam.viewportHeight = Math.max(1, height);
        cam.update();
        stage.getViewport().update(width, height, true);
    }

    public void hide() {
        dispose();
    }

    public void dispose() {
        for (Block b : blocks) {
            for (Mesh mesh : b.meshes) {
                mesh.dispose();
            }
        }
        stage.dispose();
        skin.dispose();
        batch.dispose();
        shapes.dispose();
        fog.dispose();
        carArt.dispose();
        copArt.dispose();
        road.dispose();
        facade.dispose();
        white.dispose();
        for (Texture t : trafficArt) {
            t.dispose();
        }
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
