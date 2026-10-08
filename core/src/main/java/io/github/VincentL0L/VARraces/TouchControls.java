package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Phone and tablet controls, laid out like a real car's:
 *  - bottom left: big left and right arrow buttons to steer.
 *  - bottom right: a brake pedal and a gas pedal, side by side like in a car's footwell.
 *    Double tap and hold the gas for boost (when it runs out it carries on as gas).
 *  - to back up, stop and keep holding the brake: after a moment it reverses.
 *  - above the gas: the ITEM button, showing the item you're holding (tap to use it).
 * The car behaves like an automatic gas car (see Player): it creeps at idle, lifting off the
 * gas coasts with engine braking, and the brake stops it without rolling backwards.
 */
public class TouchControls {
    private static final float ARROW_SIZE = 96f;
    /** holding the brake this long while stopped starts reversing */
    private static final float REVERSE_DELAY = 0.4f;
    private static final float TAP_TIME = 0.3f;
    private static final float DOUBLE_TAP_GAP = 0.3f;
    /** touches this close to the top are for the HUD menu, not driving */
    private static final float TOP_ZONE = 0.3f;

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

    // steering arrows, in points: x, y, width, height
    private final float[] left = new float[4];
    private final float[] right = new float[4];
    private boolean leftDown, rightDown;
    private float stoppedBraking = 0f;
    // pedals, in points: x, y, width, height
    private final float[] gas = new float[4];
    private final float[] brake = new float[4];
    private boolean gasDown, brakeDown, boosting;
    // the item button
    private static final float ITEM_RADIUS = 38f;
    private float itemX, itemY;
    private int itemPointer = -1;
    private boolean itemTap = false;
    private Texture itemIcon;
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
            leftDown = rightDown = false;
            gasPointer = -1;
            gasDown = brakeDown = boosting = false;
            player.setTouchInput(true, 0f, false, false, false, false);
            for (int p = 0; p < wasTouched.length; p++) {
                wasTouched[p] = Gdx.input.isTouched(p);
            }
            return;
        }
        readFingers(width, height);

        float steer = (leftDown ? 1f : 0f) - (rightDown ? 1f : 0f);
        // stopped and still holding the brake: after a moment it backs up
        boolean stopped = player.getVelocity().len() < 8f;
        stoppedBraking = brakeDown && !gasDown && (stopped || stoppedBraking > REVERSE_DELAY)
            ? stoppedBraking + Gdx.graphics.getDeltaTime() : 0f;
        boolean backwards = stoppedBraking > REVERSE_DELAY;
        if (backwards) {
            player.setTouchInput(true, steer, true, false, false, true);
            draw(width, height);
            return;
        }
        // once the boost runs dry, the held pedal is just gas until the finger lifts
        if (boosting && player.getMana() <= 0.5f) {
            boosting = false;
        }
        player.setTouchInput(true, steer, gasDown, gasDown && boosting && !backwards, brakeDown, backwards);
        draw(width, height);
    }

    /** pedals in the bottom right corner (brake wider, gas taller, like real ones) */
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
        left[0] = pad;
        left[1] = pad;
        left[2] = ARROW_SIZE;
        left[3] = ARROW_SIZE;
        right[0] = pad + ARROW_SIZE + 16f;
        right[1] = pad;
        right[2] = ARROW_SIZE;
        right[3] = ARROW_SIZE;
        itemX = gas[0] + gas[2] / 2f - 20f;
        itemY = gas[1] + gas[3] + 30f + ITEM_RADIUS;
    }

    private void readFingers(float width, float height) {
        boolean anyGas = false, anyBrake = false, anyLeft = false, anyRight = false;
        // the line between the two pedals: right of it is gas, left of it (on the right half) brake
        float split = (brake[0] + brake[2] + gas[0]) / 2f;
        for (int p = 0; p < wasTouched.length; p++) {
            boolean touched = Gdx.input.isTouched(p);
            float x = Gdx.input.getX(p) / Ui.density;
            float y = height - Gdx.input.getY(p) / Ui.density;
            boolean justDown = touched && !wasTouched[p];
            wasTouched[p] = touched;

            if (p == itemPointer) {
                if (!touched) {
                    itemPointer = -1;
                }
                continue;
            }
            if (justDown && Math.hypot(x - itemX, y - itemY) < ITEM_RADIUS * 1.25f) {
                itemPointer = p;
                itemTap = true;
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
            if (x < width / 2f) {
                // left half: whichever arrow the thumb is nearer (fingers can roll between them)
                if (y < left[1] + left[3] + 60f) {
                    if (x < (left[0] + left[2] + right[0]) / 2f) {
                        anyLeft = true;
                    } else {
                        anyRight = true;
                    }
                }
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
        leftDown = anyLeft;
        rightDown = anyRight;
        if (!gasDown) {
            boosting = false;
        }
    }

    private void draw(float width, float height) {
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        arrow(left, leftDown, true);
        arrow(right, rightDown, false);

        // pedals: a gold frame, a dark face with grip ridges that light up when pressed
        pedal(brake, brakeDown, BRAKE);
        pedal(gas, gasDown, boosting && gasDown ? BOOST : GOLD);

        // the item button
        shapes.setColor(GOLD_DARK.r, GOLD_DARK.g, GOLD_DARK.b, 0.9f);
        shapes.circle(itemX, itemY - 3f, ITEM_RADIUS, 40);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, itemIcon != null ? 0.95f : 0.5f);
        shapes.circle(itemX, itemY, ITEM_RADIUS, 40);
        shapes.setColor(FACE.r, FACE.g, FACE.b, 0.85f);
        shapes.circle(itemX, itemY, ITEM_RADIUS - 5f, 40);
        shapes.end();

        batch.begin();
        if (itemIcon != null) {
            batch.draw(itemIcon, itemX - 24f, itemY - 24f, 48f, 48f);
        } else {
            label("ITEM", itemX, itemY, new Color(GOLD.r, GOLD.g, GOLD.b, 0.5f));
        }
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

    /** a steering button: gilded frame with a big arrow that lights up when held */
    private void arrow(float[] r, boolean down, boolean pointsLeft) {
        float sink = down ? 3f : 0f;
        frame(r[0], r[1] - sink, r[2], r[3], down ? 0.95f : 0.6f);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, down ? 1f : 0.55f);
        float cx = r[0] + r[2] / 2f, cy = r[1] + r[3] / 2f - sink, a = r[2] * 0.26f;
        if (pointsLeft) {
            shapes.triangle(cx - a, cy, cx + a * 0.7f, cy + a, cx + a * 0.7f, cy - a);
        } else {
            shapes.triangle(cx + a, cy, cx - a * 0.7f, cy + a, cx - a * 0.7f, cy - a);
        }
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

    /**
     * @param icon the held item's icon, or null
     */
    public void setItem(Texture icon) {
        itemIcon = icon;
    }

    /**
     * @return true once after the ITEM button was tapped
     */
    public boolean itemTapped() {
        boolean tapped = itemTap;
        itemTap = false;
        return tapped;
    }

    public void dispose() {
        batch.dispose();
        shapes.dispose();
        font.dispose();
    }
}
