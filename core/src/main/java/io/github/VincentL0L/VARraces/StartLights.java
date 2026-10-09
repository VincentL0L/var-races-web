package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;

/**
 * The race start, like real racing games: a gantry of three lights hangs under the
 * speedometer and one more turns red on each of 3, 2, 1, then they all go green on GO!
 * The count itself pops up big in the middle of the screen.
 */
public class StartLights {
    private static final int LIGHTS = 3;
    private static final float RADIUS = 20f;
    private static final float SPACING = 64f;
    /** how long GO! and the green lights stay before fading away */
    private static final float GO_HOLD = 1f;
    private static final float FADE = 0.4f;

    private static final Color HOUSING = new Color(0.08f, 0.05f, 0.04f, 1f);
    private static final Color OFF = new Color(0.22f, 0.07f, 0.06f, 1f);
    private static final Color RED = new Color(1f, 0.18f, 0.12f, 1f);
    private static final Color GREEN = new Color(0.25f, 1f, 0.35f, 1f);
    private static final Color NUMBER = new Color(1f, 0.95f, 0.84f, 1f);

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont numberFont = Ui.displayOutlined(130);
    private final NinePatch panel = Ui.patch("panel", 6, 6, 6, 6);
    private final GlyphLayout layout = new GlyphLayout();
    private final Color color = new Color();
    private String shown = "";
    /** seconds since the count last changed */
    private float sinceChange = 0f;
    private float sinceGo = -1f;

    /**
     * @return true once GO! has been shown and faded away
     */
    public boolean isDone() {
        return sinceGo >= GO_HOLD + FADE;
    }

    /**
     * draws the lights and the count over the race
     * @param text the countdown: "" (waiting), "3", "2", "1" or "GO!"
     * @param delta seconds since last frame
     */
    public void render(String text, float delta) {
        if (!text.equals(shown)) {
            shown = text;
            if (text.equals("GO!")) {
                Sounds.play("go", 0.55f);
            } else if (!text.isEmpty()) {
                Sounds.play("beep", 0.5f);
            }
            sinceChange = 0f;
            if (text.equals("GO!")) {
                sinceGo = 0f;
            }
        }
        sinceChange += delta;
        if (sinceGo >= 0f) {
            sinceGo += delta;
        }
        if (isDone()) {
            return;
        }
        float alpha = sinceGo < GO_HOLD ? 1f : 1f - (sinceGo - GO_HOLD) / FADE;

        float width = Ui.width();
        float height = Ui.height();
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, width, height);

        // the gantry hangs just under the speedometer pod (see Overlay), scaled the same way
        float hudScale = Math.min(1f, width / 1240f);
        float panelWidth = SPACING * (LIGHTS - 1) + RADIUS * 2f + 56f;
        float panelHeight = RADIUS * 2f + 44f;
        float panelX = (width - panelWidth) / 2f;
        float panelY = height - 206f * hudScale - 10f - panelHeight;

        batch.begin();
        batch.setColor(1f, 1f, 1f, alpha);
        panel.setColor(batch.getColor());
        panel.draw(batch, panelX, panelY, panelWidth, panelHeight);
        batch.setColor(Color.WHITE);
        batch.end();

        int lit = litCount();
        boolean go = shown.equals("GO!");
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        float cy = panelY + panelHeight / 2f;
        for (int i = 0; i < LIGHTS; i++) {
            float cx = width / 2f + (i - (LIGHTS - 1) / 2f) * SPACING;
            boolean on = go || i < lit;
            Color lamp = go ? GREEN : on ? RED : OFF;
            if (on) {
                // soft glow around a lit lamp
                shapes.setColor(lamp.r, lamp.g, lamp.b, 0.22f * alpha);
                shapes.circle(cx, cy, RADIUS + 10f, 32);
            }
            shapes.setColor(HOUSING.r, HOUSING.g, HOUSING.b, alpha);
            shapes.circle(cx, cy, RADIUS + 4f, 32);
            shapes.setColor(lamp.r, lamp.g, lamp.b, alpha);
            shapes.circle(cx, cy, RADIUS, 32);
            // a little shine in the top left, like glass
            shapes.setColor(1f, 1f, 1f, (on ? 0.45f : 0.12f) * alpha);
            shapes.circle(cx - RADIUS * 0.35f, cy + RADIUS * 0.35f, RADIUS * 0.28f, 16);
        }
        shapes.end();

        if (shown.isEmpty()) {
            return;
        }
        // the count pops in big and settles, then fades just before the next one
        float pop = 1f + 0.7f * Interpolation.pow3In.apply(1f - Math.min(1f, sinceChange / 0.3f));
        float fade = go ? alpha : MathUtils.clamp((1f - sinceChange) / 0.25f, 0f, 1f);
        float size = Math.min(1f, height / 620f);
        numberFont.getData().setScale(130f / 64f * size * pop);
        color.set(go ? GREEN : NUMBER);
        color.a = fade;
        numberFont.setColor(color);
        layout.setText(numberFont, shown);
        batch.begin();
        // below our car (which sits in the middle of the screen) so it doesn't hide it
        float numberY = height / 2f - 150f * size;
        numberFont.draw(batch, layout, (width - layout.width) / 2f, numberY + layout.height / 2f);
        batch.end();
    }

    /**
     * @return how many red lights are on: 1 at "3", 2 at "2", 3 at "1"
     */
    private int litCount() {
        if (shown.equals("3")) {
            return 1;
        } else if (shown.equals("2")) {
            return 2;
        } else if (shown.equals("1")) {
            return 3;
        }
        return 0;
    }

    public void dispose() {
        batch.dispose();
        shapes.dispose();
        numberFont.dispose();
    }
}
