package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;

/**
 * Phone and tablet controls, laid out like a real car's:
 *  - left half of the screen: a floating joystick (put a thumb down anywhere, drag left or
 *    right to steer, the further the sharper), like Brawl Stars.
 *  - bottom right: a brake pedal and a gas pedal, side by side like in a car's footwell.
 *    Double tap and hold the gas for boost (when it runs out it carries on as gas).
 *  - above the pedals: the gear selector, D (drive) or R (reverse). Like a real automatic
 *    it only changes gear when the car is (almost) stopped.
 * The car behaves like an automatic gas car (see Player): it creeps at idle, lifting off the
 * gas coasts with engine braking, and the brake stops it without rolling backwards.
 */
public class TouchControls {
    private static final float STICK_RADIUS = 64f;
    private static final float DEAD_ZONE = 0.12f;
    private static final float TAP_TIME = 0.3f;
    private static final float DOUBLE_TAP_GAP = 0.3f;
    /** touches this close to the top are for the HUD menu, not driving */
    private static final float TOP_ZONE = 0.3f;
    /** gear changes only below this speed (map pixels per second, about 4 mph) */
    private static final float SHIFT_SPEED = 10f;

    private static final Color GOLD = new Color(1f, 0.8f, 0.28f, 1f);
    private static final Color GOLD_DARK = new Color(0.55f, 0.32f, 0.08f, 1f);
    private static final Color FACE = new Color(0.17f, 0.1f, 0.06f, 1f);
    private static final Color BOOST = new Color(0.25f, 0.68f, 1f, 1f);
    private static final Color BRAKE = new Color(1f, 0.36f, 0.24f, 1f);

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont font = Ui.display(15);
    private final GlyphLayout layout = new GlyphLayout();
    private final boolean[] wasTouched = new boolean[10];

    // joystick
    private int stickPointer = -1;
    private float baseX, baseY, knobX, knobY;
    // pedals and gear selector, in points: x, y, width, height
    private final float[] gas = new float[4];
    private final float[] brake = new float[4];
    private final float[] gear = new float[4];
    private boolean gasDown, brakeDown, boosting, reverseGear;
    private int gasPointer = -1;
    private float gasDownAt = -10f;
    private float lastTapEnd = -10f;
    private boolean lastWasTap = false;
    private float clock = 0f;

    /**
     * reads the fingers, drives the car and draws the controls
     * @param player the car to drive
     * @param enabled false hides the controls and lets go of everything (paused, finished)
     */
    public void update(Player player, boolean enabled) {
        clock += Gdx.graphics.getDeltaTime();
        float width = Ui.width();
        float height = Ui.height();
        layout(width);
        if (!enabled) {
            stickPointer = -1;
            gasPointer = -1;
            gasDown = brakeDown = boosting = false;
            player.setTouchInput(true, 0f, false, false, false, reverseGear);
            for (int p = 0; p < wasTouched.length; p++) {
                wasTouched[p] = Gdx.input.isTouched(p);
            }
            return;
        }
        readFingers(player, width, height);

        float steer = 0f;
        if (stickPointer >= 0) {
            float dx = (knobX - baseX) / STICK_RADIUS;
            if (Math.abs(dx) > DEAD_ZONE) {
                steer = -Math.signum(dx) * (Math.abs(dx) - DEAD_ZONE) / (1f - DEAD_ZONE);
            }
        }
        // once the boost runs dry, the held pedal is just gas until the finger lifts
        if (boosting && player.getMana() <= 0.5f) {
            boosting = false;
        }
        player.setTouchInput(true, steer, gasDown, gasDown && boosting && !reverseGear, brakeDown, reverseGear);
        draw(width, height);
    }

    /** pedals in the bottom right corner (brake wider, gas taller, like real ones), gear above */
    private void layout(float width) {
        float pad = 26f;
        gas[2] = 74f;
        gas[3] = 128f;
        gas[0] = width - pad - gas[2];
        gas[1] = pad;
        brake[2] = 112f;
        brake[3] = 92f;
        brake[0] = gas[0] - 18f - brake[2];
        brake[1] = pad;
        gear[2] = 112f;
        gear[3] = 38f;
        gear[0] = brake[0];
        gear[1] = brake[1] + brake[3] + 18f;
    }

    private void readFingers(Player player, float width, float height) {
        boolean anyGas = false, anyBrake = false;
        // the line between the two pedals: right of it is gas, left of it (on the right half) brake
        float split = (brake[0] + brake[2] + gas[0]) / 2f;
        for (int p = 0; p < wasTouched.length; p++) {
            boolean touched = Gdx.input.isTouched(p);
            float x = Gdx.input.getX(p) / Ui.density;
            float y = height - Gdx.input.getY(p) / Ui.density;
            boolean justDown = touched && !wasTouched[p];
            wasTouched[p] = touched;

            if (p == stickPointer) {
                if (touched) {
                    float dx = x - baseX, dy = y - baseY;
                    float len = (float) Math.hypot(dx, dy);
                    float scale = len > STICK_RADIUS ? STICK_RADIUS / len : 1f;
                    knobX = baseX + dx * scale;
                    knobY = baseY + dy * scale;
                } else {
                    stickPointer = -1;
                }
                continue;
            }
            if (p == gasPointer && !touched) {
                gasPointer = -1;
                lastWasTap = clock - gasDownAt < TAP_TIME && !boosting;
                lastTapEnd = clock;
                boosting = false;
            }
            if (!touched) {
                continue;
            }
            if (justDown && y > height * (1f - TOP_ZONE)) {
                continue;   // a tap on the HUD menu
            }
            if (justDown && x < width / 2f) {
                if (stickPointer < 0) {
                    stickPointer = p;
                    baseX = MathUtils.clamp(x, STICK_RADIUS + 10f, width / 2f - STICK_RADIUS);
                    baseY = MathUtils.clamp(y, STICK_RADIUS + 10f, height * (1f - TOP_ZONE));
                    knobX = baseX;
                    knobY = baseY;
                }
                continue;
            }
            if (x < width / 2f) {
                continue;
            }
            if (justDown && inside(gear, x, y, 10f)) {
                // like a real automatic: change gear only when stopped
                if (player.getVelocity().len() < SHIFT_SPEED) {
                    reverseGear = !reverseGear;
                }
                continue;
            }
            if (inside(gear, x, y, 10f)) {
                continue;
            }
            // fingers can slide from one pedal to the other, like a foot
            if (x >= split) {
                anyGas = true;
                if (justDown) {
                    gasPointer = p;
                    boosting = lastWasTap && clock - lastTapEnd < DOUBLE_TAP_GAP;
                    gasDownAt = clock;
                }
            } else {
                anyBrake = true;
            }
        }
        gasDown = anyGas;
        brakeDown = anyBrake;
        if (!gasDown) {
            boosting = false;
        }
    }

    private static boolean inside(float[] r, float x, float y, float margin) {
        return x >= r[0] - margin && x <= r[0] + r[2] + margin && y >= r[1] - margin && y <= r[1] + r[3] + margin;
    }

    private void draw(float width, float height) {
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        boolean held = stickPointer >= 0;
        float sx = held ? baseX : 40f + STICK_RADIUS;
        float sy = held ? baseY : 40f + STICK_RADIUS;
        float kx = held ? knobX : sx;
        float ky = held ? knobY : sy;
        float alpha = held ? 0.9f : 0.45f;

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // joystick
        shapes.setColor(GOLD_DARK.r, GOLD_DARK.g, GOLD_DARK.b, alpha * 0.8f);
        shapes.circle(sx, sy - 3f, STICK_RADIUS + 8f, 48);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, alpha * 0.8f);
        shapes.circle(sx, sy, STICK_RADIUS + 8f, 48);
        shapes.setColor(FACE.r, FACE.g, FACE.b, alpha * 0.55f);
        shapes.circle(sx, sy, STICK_RADIUS + 2f, 48);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, alpha);
        float a = 9f, edge = STICK_RADIUS - 12f;
        shapes.triangle(sx - edge - a, sy, sx - edge + a * 0.6f, sy + a, sx - edge + a * 0.6f, sy - a);
        shapes.triangle(sx + edge + a, sy, sx + edge - a * 0.6f, sy + a, sx + edge - a * 0.6f, sy - a);
        shapes.setColor(GOLD_DARK.r, GOLD_DARK.g, GOLD_DARK.b, alpha);
        shapes.circle(kx, ky - 3f, 28f, 32);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, alpha);
        shapes.circle(kx, ky, 28f, 32);

        // pedals: a gold frame, a dark face with grip ridges that light up when pressed
        pedal(brake, brakeDown, BRAKE);
        pedal(gas, gasDown, boosting && gasDown ? BOOST : GOLD);

        // gear selector: D and R halves, the chosen one lit
        float half = gear[2] / 2f;
        frame(gear[0], gear[1], gear[2], gear[3], 0.85f);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
        float litX = reverseGear ? gear[0] + half : gear[0];
        shapes.rect(litX + 4f, gear[1] + 4f, half - 8f, gear[3] - 8f);
        shapes.end();

        batch.begin();
        label("D", gear[0] + half / 2f, gear[1] + gear[3] / 2f, reverseGear ? GOLD : Ui.TEXT_DARK);
        label("R", gear[0] + half * 1.5f, gear[1] + gear[3] / 2f, reverseGear ? Ui.TEXT_DARK : GOLD);
        label("BRAKE", brake[0] + brake[2] / 2f, brake[1] + 18f, brakeDown ? BRAKE : GOLD);
        label(boosting && gasDown ? "BOOST" : "GAS", gas[0] + gas[2] / 2f, gas[1] + 18f,
            boosting && gasDown ? BOOST : GOLD);
        batch.end();
    }

    /** the gilded frame (gold with a darker lower edge) and a dark face inside it */
    private void frame(float x, float y, float w, float h, float alpha) {
        shapes.setColor(GOLD_DARK.r, GOLD_DARK.g, GOLD_DARK.b, alpha);
        shapes.rect(x, y - 3f, w, h);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, alpha);
        shapes.rect(x, y, w, h);
        shapes.setColor(FACE.r, FACE.g, FACE.b, alpha);
        shapes.rect(x + 4f, y + 4f, w - 8f, h - 8f);
    }

    private void pedal(float[] r, boolean down, Color color) {
        // a pressed pedal sinks a little, like it's being pushed
        float sink = down ? 3f : 0f;
        frame(r[0], r[1] - sink, r[2], r[3], down ? 0.95f : 0.6f);
        shapes.setColor(color.r, color.g, color.b, down ? 0.95f : 0.35f);
        for (float ry = r[1] + 34f; ry < r[1] + r[3] - 12f; ry += 12f) {
            shapes.rect(r[0] + 12f, ry - sink, r[2] - 24f, 4f);
        }
    }

    private void label(String text, float cx, float cy, Color color) {
        font.setColor(color);
        layout.setText(font, text);
        font.draw(batch, text, cx - layout.width / 2f, cy + layout.height / 2f);
    }

    public void dispose() {
        batch.dispose();
        shapes.dispose();
        font.dispose();
    }
}
