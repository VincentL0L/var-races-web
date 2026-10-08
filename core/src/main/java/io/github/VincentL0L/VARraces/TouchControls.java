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
 * Phone and tablet controls, like Brawl Stars:
 *  - left half of the screen: a floating joystick. Put a thumb down anywhere and drag left
 *    or right to steer (the further, the sharper). Pull it down to reverse.
 *  - right half: the pedal. Touch and hold for gas, double tap and hold for boost (when the
 *    boost runs out it carries on as gas). Letting go brakes.
 */
public class TouchControls {
    /** how far the joystick knob can move from its base, in points */
    private static final float STICK_RADIUS = 64f;
    /** small movements around the middle don't steer */
    private static final float DEAD_ZONE = 0.12f;
    /** pulling the stick down this far (of its radius) reverses */
    private static final float REVERSE_PULL = 0.6f;
    /** a double tap: the first tap is shorter than this... */
    private static final float TAP_TIME = 0.3f;
    /** ...and the second touch comes within this long of it */
    private static final float DOUBLE_TAP_GAP = 0.3f;
    /** touches this close to the top are for the HUD menu, not driving */
    private static final float TOP_ZONE = 0.3f;
    private static final float HINT_TIME = 9f;

    private static final Color GOLD = new Color(1f, 0.8f, 0.28f, 1f);
    private static final Color GOLD_DARK = new Color(0.55f, 0.32f, 0.08f, 1f);
    private static final Color FACE = new Color(0.17f, 0.1f, 0.06f, 1f);
    private static final Color BOOST = new Color(0.25f, 0.68f, 1f, 1f);

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont font = Ui.display(15);
    private final BitmapFont hintFont = Ui.font(11);
    private final GlyphLayout layout = new GlyphLayout();
    private final boolean[] wasTouched = new boolean[10];

    // joystick: which finger, where its base is and where the thumb is now
    private int stickPointer = -1;
    private float baseX, baseY, knobX, knobY;
    // pedal: which finger, and when the last touch started and ended (for double taps)
    private int pedalPointer = -1;
    private boolean boosting = false;
    private float pedalDownAt = -10f;
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
        if (!enabled) {
            stickPointer = -1;
            pedalPointer = -1;
            boosting = false;
            player.setTouchInput(true, 0f, false, false, false);
            for (int p = 0; p < wasTouched.length; p++) {
                wasTouched[p] = Gdx.input.isTouched(p);
            }
            return;
        }
        readFingers(width, height);

        // steering from how far the knob is pushed sideways
        float steer = 0f;
        boolean reverse = false;
        if (stickPointer >= 0) {
            float dx = (knobX - baseX) / STICK_RADIUS;
            float dy = (knobY - baseY) / STICK_RADIUS;
            if (Math.abs(dx) > DEAD_ZONE) {
                steer = -Math.signum(dx) * (Math.abs(dx) - DEAD_ZONE) / (1f - DEAD_ZONE);
            }
            reverse = dy < -REVERSE_PULL && pedalPointer < 0;
        }
        boolean gas = pedalPointer >= 0;
        // once the boost runs dry, the held pedal is just gas until the finger lifts
        if (boosting && player.getMana() <= 0.5f) {
            boosting = false;
        }
        player.setTouchInput(true, steer, gas, gas && boosting, reverse);
        draw(width, height, gas, reverse);
    }

    /**
     * new fingers on the left grab the joystick, on the right the pedal; lifted fingers let go
     */
    private void readFingers(float width, float height) {
        for (int p = 0; p < wasTouched.length; p++) {
            boolean touched = Gdx.input.isTouched(p);
            float x = Gdx.input.getX(p) / Ui.density;
            float y = height - Gdx.input.getY(p) / Ui.density;
            if (touched && !wasTouched[p] && y < height * (1f - TOP_ZONE)) {
                if (x < width / 2f && stickPointer < 0) {
                    stickPointer = p;
                    // the stick appears under the thumb (kept fully on screen)
                    baseX = MathUtils.clamp(x, STICK_RADIUS + 10f, width / 2f - STICK_RADIUS);
                    baseY = MathUtils.clamp(y, STICK_RADIUS + 10f, height * (1f - TOP_ZONE));
                } else if (x >= width / 2f && pedalPointer < 0) {
                    pedalPointer = p;
                    // a quick tap just before this touch makes it a boost
                    boosting = lastWasTap && clock - lastTapEnd < DOUBLE_TAP_GAP;
                    pedalDownAt = clock;
                }
            }
            if (p == stickPointer) {
                if (touched) {
                    // the knob follows the thumb but stays inside the ring
                    float dx = x - baseX, dy = y - baseY;
                    float len = (float) Math.hypot(dx, dy);
                    float scale = len > STICK_RADIUS ? STICK_RADIUS / len : 1f;
                    knobX = baseX + dx * scale;
                    knobY = baseY + dy * scale;
                } else {
                    stickPointer = -1;
                }
            }
            if (p == pedalPointer && !touched) {
                pedalPointer = -1;
                lastWasTap = clock - pedalDownAt < TAP_TIME && !boosting;
                lastTapEnd = clock;
                boosting = false;
            }
            wasTouched[p] = touched;
        }
    }

    private void draw(float width, float height, boolean gas, boolean reverse) {
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // joystick: faint in its resting spot until a thumb picks it up
        boolean held = stickPointer >= 0;
        float sx = held ? baseX : 40f + STICK_RADIUS;
        float sy = held ? baseY : 40f + STICK_RADIUS;
        float kx = held ? knobX : sx;
        float ky = held ? knobY : sy;
        float alpha = held ? 0.9f : 0.45f;
        // pedal: bottom right, gold for gas, blue while boosting
        float px = width - 40f - STICK_RADIUS;
        float py = 40f + STICK_RADIUS;
        Color pedal = boosting && gas ? BOOST : GOLD;

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        ring(sx, sy, STICK_RADIUS + 8f, GOLD, alpha * 0.8f);
        shapes.setColor(FACE.r, FACE.g, FACE.b, alpha * 0.55f);
        shapes.circle(sx, sy, STICK_RADIUS + 2f, 48);
        // left / right arrows on the ring
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, alpha);
        float a = 9f, edge = STICK_RADIUS - 12f;
        shapes.triangle(sx - edge - a, sy, sx - edge + a * 0.6f, sy + a, sx - edge + a * 0.6f, sy - a);
        shapes.triangle(sx + edge + a, sy, sx + edge - a * 0.6f, sy + a, sx + edge - a * 0.6f, sy - a);
        // the knob
        shapes.setColor(GOLD_DARK.r, GOLD_DARK.g, GOLD_DARK.b, alpha);
        shapes.circle(kx, ky - 3f, 28f, 32);
        shapes.setColor(reverse ? Color.WHITE : GOLD);
        shapes.getColor().a = alpha;
        shapes.circle(kx, ky, 28f, 32);

        ring(px, py, STICK_RADIUS, pedal, gas ? 0.95f : 0.45f);
        shapes.setColor(gas ? pedal.r * 0.45f : FACE.r, gas ? pedal.g * 0.45f : FACE.g, gas ? pedal.b * 0.45f : FACE.b, gas ? 0.85f : 0.5f);
        shapes.circle(px, py, STICK_RADIUS - 7f, 48);
        shapes.end();

        batch.begin();
        String label = gas ? (boosting ? "BOOST" : "GAS") : reverse ? "REVERSE" : "GAS";
        font.setColor(gas && boosting ? BOOST : GOLD);
        font.getColor().a = gas ? 1f : 0.6f;
        layout.setText(font, label);
        font.draw(batch, label, px - layout.width / 2f, py + layout.height / 2f);
        if (clock < HINT_TIME) {
            // how-to for the first few seconds
            float fade = MathUtils.clamp(HINT_TIME - clock, 0f, 1f);
            hintFont.setColor(1f, 0.95f, 0.84f, fade);
            String left = "Drag to steer";
            layout.setText(hintFont, left);
            hintFont.draw(batch, left, 40f + STICK_RADIUS - layout.width / 2f, 30f);
            String right = "Hold = gas   Double tap + hold = boost";
            layout.setText(hintFont, right);
            hintFont.draw(batch, right, Math.min(px - layout.width / 2f, width - layout.width - 12f), 30f);
        }
        batch.end();
    }

    /** a gold ring with a darker lower edge, like the gilded buttons */
    private void ring(float x, float y, float r, Color color, float alpha) {
        shapes.setColor(color.r * 0.55f, color.g * 0.45f, color.b * 0.3f, alpha);
        shapes.circle(x, y - 3f, r, 48);
        shapes.setColor(color.r, color.g, color.b, alpha);
        shapes.circle(x, y, r, 48);
    }

    public void dispose() {
        batch.dispose();
        shapes.dispose();
        font.dispose();
        hintFont.dispose();
    }
}
