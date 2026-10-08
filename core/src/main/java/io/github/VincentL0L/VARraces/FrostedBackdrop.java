package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/**
 * Frosted-glass background: whatever is drawn between begin() and end() is blurred and
 * lightened when draw() puts it on screen.
 *
 * The blur is a "dual filter" blur: the picture is halved in size a few times and then
 * doubled back up, and every step mixes several nearby samples in a ring. That gives a
 * soft, even blur with no blocky steps, at a tiny cost.
 */
public class FrostedBackdrop {
    /** how many times the picture is halved: 5 = down to 1/32 size */
    private static final int LEVELS = 5;
    private static final Color FROST = new Color(0.93f, 0.95f, 1f, 0.14f);
    private static final Color SHADE = new Color(0.08f, 0.05f, 0.03f, 0.22f);

    private static final String VERTEX =
        "attribute vec4 a_position;\n"
        + "attribute vec4 a_color;\n"
        + "attribute vec2 a_texCoord0;\n"
        + "uniform mat4 u_projTrans;\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_uv;\n"
        + "void main() {\n"
        + "  v_color = a_color;\n"
        + "  v_color.a = v_color.a * (255.0 / 254.0);\n"
        + "  v_uv = a_texCoord0;\n"
        + "  gl_Position = u_projTrans * a_position;\n"
        + "}\n";

    /** shrinking step: the middle sample and four diagonal neighbours */
    private static final String DOWN =
        "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_uv;\n"
        + "uniform sampler2D u_texture;\n"
        + "uniform vec2 u_half;\n"
        + "void main() {\n"
        + "  vec4 sum = texture2D(u_texture, v_uv) * 4.0;\n"
        + "  sum += texture2D(u_texture, v_uv - u_half);\n"
        + "  sum += texture2D(u_texture, v_uv + u_half);\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(u_half.x, -u_half.y));\n"
        + "  sum += texture2D(u_texture, v_uv - vec2(u_half.x, -u_half.y));\n"
        + "  gl_FragColor = v_color * (sum / 8.0);\n"
        + "}\n";

    /** growing step: a ring of eight samples */
    private static final String UP =
        "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_uv;\n"
        + "uniform sampler2D u_texture;\n"
        + "uniform vec2 u_half;\n"
        + "void main() {\n"
        + "  vec2 h = u_half;\n"
        + "  vec4 sum = texture2D(u_texture, v_uv + vec2(-h.x * 2.0, 0.0));\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(-h.x, h.y)) * 2.0;\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(0.0, h.y * 2.0));\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(h.x, h.y)) * 2.0;\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(h.x * 2.0, 0.0));\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(h.x, -h.y)) * 2.0;\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(0.0, -h.y * 2.0));\n"
        + "  sum += texture2D(u_texture, v_uv + vec2(-h.x, -h.y)) * 2.0;\n"
        + "  gl_FragColor = v_color * (sum / 12.0);\n"
        + "}\n";

    private final FrameBuffer[] buffers = new FrameBuffer[LEVELS + 1];
    private final SpriteBatch batch = new SpriteBatch();
    private final ShaderProgram down;
    private final ShaderProgram up;
    private final Texture white;
    private int width = -1;
    private int height = -1;

    public FrostedBackdrop() {
        ShaderProgram.pedantic = false;
        down = new ShaderProgram(VERTEX, DOWN);
        up = new ShaderProgram(VERTEX, UP);
        if (!down.isCompiled() || !up.isCompiled()) {
            Gdx.app.error("FrostedBackdrop", down.getLog() + up.getLog());
        }
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
            pass(down, buffers[i - 1], buffers[i]);
        }
        // ...and grow back up
        for (int i = LEVELS - 1; i >= 1; i--) {
            pass(up, buffers[i + 1], buffers[i]);
        }

        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        if (amount < 1f) {
            // the sharp scene underneath, so the frost can fade out
            batch.setShader(null);
            batch.begin();
            batch.draw(buffers[0].getColorBufferTexture(), 0, 0, width, height, 0f, 0f, 1f, 1f);
            batch.end();
        }
        // the last growing step goes straight to the screen
        batch.enableBlending();
        batch.setShader(up);
        batch.begin();
        up.setUniformf("u_half", 0.5f / width, 0.5f / height);
        batch.setColor(1f, 1f, 1f, amount);
        batch.draw(buffers[1].getColorBufferTexture(), 0, 0, width, height, 0f, 0f, 1f, 1f);
        batch.end();
        batch.setShader(null);

        // a light frost, then a touch of shade so gold panels still stand out
        batch.begin();
        batch.setColor(FROST.r, FROST.g, FROST.b, FROST.a * amount);
        batch.draw(white, 0, 0, width, height);
        batch.setColor(SHADE.r, SHADE.g, SHADE.b, SHADE.a * amount);
        batch.draw(white, 0, 0, width, height);
        batch.setColor(Color.WHITE);
        batch.end();
    }

    /**
     * draws one buffer into another (a different size) through a blur shader
     */
    private void pass(ShaderProgram shader, FrameBuffer from, FrameBuffer to) {
        to.begin();
        batch.setShader(shader);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, to.getWidth(), to.getHeight());
        batch.begin();
        shader.setUniformf("u_half", 0.5f / to.getWidth(), 0.5f / to.getHeight());
        // uv 0..1 keeps the buffer the right way up (buffers are stored bottom row first)
        batch.draw(from.getColorBufferTexture(), 0, 0, to.getWidth(), to.getHeight(), 0f, 0f, 1f, 1f);
        batch.end();
        batch.setShader(null);
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
        down.dispose();
        up.dispose();
        white.dispose();
    }
}
