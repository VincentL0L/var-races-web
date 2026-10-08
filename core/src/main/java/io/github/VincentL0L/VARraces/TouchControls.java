package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * On-screen driving buttons for phones and tablets, in the gilded style: steer left and
 * right with the left thumb, gas, brake and boost with the right. Several fingers can be
 * down at once (gas + steer + boost).
 */
public class TouchControls {
    private static final int LEFT = 0, RIGHT = 1, GAS = 2, BRAKE = 3, BOOST = 4;
    private static final String[] NAMES = {"", "", "GAS", "BRAKE", "BOOST"};
    /** fingers further than this from a button (times its radius) don't press it */
    private static final float REACH = 1.35f;

    private static final Color RING = new Color(1f, 0.8f, 0.28f, 1f);
    private static final Color RING_DARK = new Color(0.55f, 0.32f, 0.08f, 1f);
    private static final Color FACE = new Color(0.17f, 0.1f, 0.06f, 1f);
    private static final Color FACE_DOWN = new Color(0.45f, 0.3f, 0.1f, 1f);
    private static final Color BOOST_COLOR = new Color(0.25f, 0.68f, 1f, 1f);

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont font = Ui.display(16);
    private final GlyphLayout layout = new GlyphLayout();
    // button centers and radii in points, worked out every frame for the screen size
    private final float[] bx = new float[5];
    private final float[] by = new float[5];
    private final float[] br = new float[5];
    private final boolean[] down = new boolean[5];

    /**
     * reads the fingers, passes the held buttons to the car and draws the buttons
     * @param player the car to drive
     * @param enabled false hides the buttons (paused, finished)
     */
    public void update(Player player, boolean enabled) {
        float width = Ui.width();
        float height = Ui.height();
        layoutButtons(width, height);
        for (int i = 0; i < down.length; i++) {
            down[i] = false;
        }
        if (enabled) {
            for (int pointer = 0; pointer < 10; pointer++) {
                if (Gdx.input.isTouched(pointer)) {
                    float x = Gdx.input.getX(pointer) / Ui.density;
                    float y = height - Gdx.input.getY(pointer) / Ui.density;
                    press(x, y);
                }
            }
        }
        player.setTouchInput(down[GAS], down[BRAKE], down[LEFT], down[RIGHT], down[BOOST]);
        if (enabled) {
            draw(width, height);
        }
    }

    private void layoutButtons(float width, float height) {
        float s = Math.min(1f, height / 620f) * 1.15f;
        float pad = 26f * s;
        // left thumb: steering
        br[LEFT] = br[RIGHT] = 50f * s;
        by[LEFT] = by[RIGHT] = pad + br[LEFT];
        bx[LEFT] = pad + br[LEFT];
        bx[RIGHT] = bx[LEFT] + br[LEFT] * 2f + 22f * s;
        // right thumb: big gas pedal in the corner, brake to its left, boost above it
        br[GAS] = 58f * s;
        bx[GAS] = width - pad - br[GAS];
        by[GAS] = pad + br[GAS];
        br[BRAKE] = 42f * s;
        bx[BRAKE] = bx[GAS] - br[GAS] - 18f * s - br[BRAKE];
        by[BRAKE] = pad + br[BRAKE];
        br[BOOST] = 38f * s;
        bx[BOOST] = bx[GAS] - 10f * s;
        by[BOOST] = by[GAS] + br[GAS] + 22f * s + br[BOOST];
    }

    /**
     * marks the button closest to a finger as held
     */
    private void press(float x, float y) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < down.length; i++) {
            float d = (float) Math.hypot(x - bx[i], y - by[i]) / br[i];
            if (d < REACH && d < bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        if (best >= 0) {
            down[best] = true;
        }
    }

    private void draw(float width, float height) {
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < down.length; i++) {
            float x = bx[i], y = by[i], r = br[i];
            // gold rim with a darker lower edge, like the gilded buttons, then the face
            shapes.setColor(RING_DARK.r, RING_DARK.g, RING_DARK.b, 0.9f);
            shapes.circle(x, y - 3f, r, 40);
            shapes.setColor(RING.r, RING.g, RING.b, 0.9f);
            shapes.circle(x, y, r, 40);
            Color face = down[i] ? FACE_DOWN : FACE;
            shapes.setColor(face.r, face.g, face.b, 0.82f);
            shapes.circle(x, y, r - 6f, 40);
            Color mark = i == BOOST ? BOOST_COLOR : RING;
            shapes.setColor(mark);
            float a = r * 0.36f;
            if (i == LEFT) {
                shapes.triangle(x - a, y, x + a * 0.7f, y + a, x + a * 0.7f, y - a);
            } else if (i == RIGHT) {
                shapes.triangle(x + a, y, x - a * 0.7f, y + a, x - a * 0.7f, y - a);
            }
        }
        shapes.end();

        batch.begin();
        for (int i = GAS; i <= BOOST; i++) {
            font.setColor(i == BOOST ? BOOST_COLOR : RING);
            font.getData().setScale(16f / 64f * Math.min(1.3f, br[i] / 42f));
            layout.setText(font, NAMES[i]);
            font.draw(batch, NAMES[i], bx[i] - layout.width / 2f, by[i] + layout.height / 2f);
        }
        batch.end();
    }

    public void dispose() {
        batch.dispose();
        shapes.dispose();
        font.dispose();
    }
}
