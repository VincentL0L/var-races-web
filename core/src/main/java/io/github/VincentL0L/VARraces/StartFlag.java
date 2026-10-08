package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;

/**
 * The red "VAR RACES" flag shown once everyone is ready: a white flash, the flag
 * rippling for a moment, then it is waved off to the left to show the cars on the grid.
 * The lobby starts it and hands it to GameScreen while the screen is fully red.
 *
 * The flag is drawn once into a picture, then put on screen as thin vertical strips
 * that each bob up and down a little out of step, which makes it look like cloth.
 */
public class StartFlag {
    private static final Color RED = new Color(0.91f, 0.157f, 0.118f, 1f);
    private static final Color TEXT_SHADOW = new Color(0.45f, 0.04f, 0.03f, 1f);
    private static final int STRIPS = 28;
    private static final float FLASH = 0.15f;
    private static final float HOLD = 0.8f;
    /** the wave-off takes whatever is left of the flag's time */
    private static final float WAVE = RaceManager.FLAG_TIME - FLASH - HOLD;

    private final SpriteBatch batch = new SpriteBatch();
    private final BitmapFont font = Ui.display(120);
    private final GlyphLayout layout = new GlyphLayout();
    private final Texture white;
    private FrameBuffer flag;
    private float time = 0f;

    public StartFlag() {
        Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(Color.WHITE);
        pixel.fill();
        white = new Texture(pixel);
        pixel.dispose();
    }

    /**
     * @return true while the flag fills the whole screen (after the flash, before the wave-off),
     * the moment to swap the screen behind it
     */
    public boolean isCovering() {
        return time >= FLASH + HOLD * 0.5f;
    }

    /**
     * @return true once the flag has left the screen
     */
    public boolean isDone() {
        return time >= RaceManager.FLAG_TIME;
    }

    /**
     * moves the animation on and draws it over everything
     * @param delta seconds since last frame
     */
    public void render(float delta) {
        time += delta;
        if (isDone()) {
            return;
        }
        int w = Math.max(1, Gdx.graphics.getBackBufferWidth());
        int h = Math.max(1, Gdx.graphics.getBackBufferHeight());
        if (flag == null || flag.getWidth() != w || flag.getHeight() != h) {
            paintFlag(w, h);
        }

        // 0 while the flag hangs, then 0..1 as it is waved off
        float wave = MathUtils.clamp((time - FLASH - HOLD) / WAVE, 0f, 1f);
        float ripple = h * (0.006f + 0.03f * wave);
        float stripWidth = (float) w / STRIPS;

        Gdx.gl.glViewport(0, 0, w, h);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        Texture texture = flag.getColorBufferTexture();
        // the whole flag slides off together (so no gaps open up), speeding up as it goes
        float slide = wave * wave * (3f - 2f * wave) * (w + ripple * 4f);
        for (int i = 0; i < STRIPS; i++) {
            float phase = time * 9f - i * 0.45f;
            float x = i * stripWidth - slide;
            // the trailing (right) edge lifts and flaps more, like cloth being pulled away
            float lift = wave * (float) i / STRIPS;
            float y = MathUtils.sin(phase) * ripple * (0.4f + lift) + lift * ripple * 0.8f;
            if (x + stripWidth < 0f) {
                continue;
            }
            // folds facing away from the light are a little darker
            float shade = 1f - (0.05f + 0.12f * wave) * (0.5f + 0.5f * MathUtils.cos(phase));
            batch.setColor(shade, shade, shade, 1f);
            int srcX = (int) (i * stripWidth);
            int srcW = (int) Math.ceil(stripWidth) + 1;
            // the buffer is stored upside down, so flip it while drawing
            // a little taller than the screen so the bobbing never shows a gap at the edges
            batch.draw(texture, x, y - ripple * 2.2f, srcW, h + ripple * 4.4f,
                srcX, 0, srcW, h, false, true);
        }
        if (time < FLASH) {
            batch.setColor(1f, 1f, 1f, 1f - time / FLASH);
            batch.draw(white, 0, 0, w, h);
        }
        batch.setColor(Color.WHITE);
        batch.end();
    }

    /**
     * draws the red flag with VAR RACES in the middle into a picture the size of the screen
     */
    private void paintFlag(int w, int h) {
        if (flag != null) {
            flag.dispose();
        }
        flag = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
        flag.begin();
        Gdx.gl.glClearColor(RED.r, RED.g, RED.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // lay the text out in points so it is the same size on every screen
        float pw = w / Ui.density, ph = h / Ui.density;
        float scale = Math.min(1f, pw * 0.8f / 900f);
        font.getData().setScale(120f / 64f * scale);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, pw, ph);
        batch.begin();
        layout.setText(font, "VAR RACES");
        float x = (pw - layout.width) / 2f;
        float y = (ph + layout.height) / 2f;
        font.setColor(TEXT_SHADOW);
        font.draw(batch, "VAR RACES", x + 5f * scale, y - 6f * scale);
        font.setColor(Color.WHITE);
        font.draw(batch, "VAR RACES", x, y);
        batch.end();
        flag.end();
    }

    public void dispose() {
        if (flag != null) {
            flag.dispose();
        }
        batch.dispose();
        white.dispose();
    }
}
