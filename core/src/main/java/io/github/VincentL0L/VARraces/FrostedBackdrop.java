package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;

/**
 * Frosted-glass background: whatever is drawn between begin() and end() is blurred and
 * lightened when draw() puts it on screen.
 *
 * The blur works by halving the picture's size a few times and then doubling it back up,
 * smoothing it at every step (much cheaper than a real blur, and it looks just as soft).
 */
public class FrostedBackdrop {
    /** how many times the picture is halved: 4 = down to 1/16 size */
    private static final int LEVELS = 4;
    private static final Color FROST = new Color(0.93f, 0.95f, 1f, 0.16f);
    private static final Color SHADE = new Color(0.08f, 0.05f, 0.03f, 0.18f);

    private final FrameBuffer[] buffers = new FrameBuffer[LEVELS + 1];
    private final SpriteBatch batch = new SpriteBatch();
    private final Texture white;
    private int width = -1;
    private int height = -1;

    public FrostedBackdrop() {
        Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(Color.WHITE);
        pixel.fill();
        white = new Texture(pixel);
        pixel.dispose();
    }

    /**
     * start drawing the scene that will be frosted
     */
    public void begin() {
        int w = Math.max(1, Gdx.graphics.getBackBufferWidth());
        int h = Math.max(1, Gdx.graphics.getBackBufferHeight());
        if (w != width || h != height) {
            createBuffers(w, h);
        }
        buffers[0].begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    }

    /**
     * stop drawing the scene
     */
    public void end() {
        buffers[0].end();
    }

    /**
     * puts the scene on screen
     * @param amount 1 = fully frosted, 0 = sharp (in between fades from one to the other)
     */
    public void draw(float amount) {
        batch.disableBlending();
        // shrink step by step...
        for (int i = 1; i <= LEVELS; i++) {
            copy(buffers[i - 1], buffers[i]);
        }
        // ...and grow back up, smoothing each time
        for (int i = LEVELS - 1; i >= 1; i--) {
            copy(buffers[i + 1], buffers[i]);
        }

        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        batch.begin();
        if (amount < 1f) {
            // the sharp scene underneath, so the frost can fade out
            batch.draw(buffers[0].getColorBufferTexture(), 0, 0, width, height, 0f, 0f, 1f, 1f);
        }
        batch.enableBlending();
        batch.setColor(1f, 1f, 1f, amount);
        batch.draw(buffers[1].getColorBufferTexture(), 0, 0, width, height, 0f, 0f, 1f, 1f);
        // a light frost, then a touch of shade so gold panels still stand out
        batch.setColor(FROST.r, FROST.g, FROST.b, FROST.a * amount);
        batch.draw(white, 0, 0, width, height);
        batch.setColor(SHADE.r, SHADE.g, SHADE.b, SHADE.a * amount);
        batch.draw(white, 0, 0, width, height);
        batch.setColor(Color.WHITE);
        batch.end();
    }

    /**
     * draws one buffer into another (different size), smoothing as it scales
     */
    private void copy(FrameBuffer from, FrameBuffer to) {
        to.begin();
        batch.getProjectionMatrix().setToOrtho2D(0, 0, to.getWidth(), to.getHeight());
        batch.begin();
        // uv 0..1 keeps the buffer the right way up (buffers are stored bottom row first)
        batch.draw(from.getColorBufferTexture(), 0, 0, to.getWidth(), to.getHeight(), 0f, 0f, 1f, 1f);
        batch.end();
        to.end();
    }

    private void createBuffers(int w, int h) {
        disposeBuffers();
        width = w;
        height = h;
        for (int i = 0; i <= LEVELS; i++) {
            int bw = Math.max(1, w >> i);
            int bh = Math.max(1, h >> i);
            buffers[i] = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
            buffers[i].getColorBufferTexture().setFilter(TextureFilter.Linear, TextureFilter.Linear);
        }
    }

    private void disposeBuffers() {
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) {
                buffers[i].dispose();
                buffers[i] = null;
            }
        }
    }

    /**
     * frees the buffers
     */
    public void dispose() {
        disposeBuffers();
        batch.dispose();
        white.dispose();
    }
}
