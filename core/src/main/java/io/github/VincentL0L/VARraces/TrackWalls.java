package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The track's edge in first person, generated from the collision boundary: a knee-high
 * concrete barrier topped with the same red and white kerb stripes the map paints, low
 * enough to see the city over, and small chevron boards on posts at the corners.
 */
public final class TrackWalls {
    /** a car's bonnet is 3.6 high and our eyes about 6.3: the barrier stays under the view */
    private static final float HEIGHT = 4.2f, THICKNESS = 2.5f;
    private final List<Barriers.Wall> boundary;
    private final List<Mesh> walls = new ArrayList<>(), left = new ArrayList<>(), right = new ArrayList<>();
    private final Texture concrete, leftArrow, rightArrow;

    /** Player has built this map's Barriers before the visual boundary is constructed. */
    public TrackWalls(TrackMap map) {
        boundary = map.hasBarriers() ? Barriers.walls() : new ArrayList<Barriers.Wall>();
        concrete = wallTexture();
        leftArrow = chevrons(false);
        rightArrow = chevrons(true);
        Scenery3D.Builder wallBuilder = new Scenery3D.Builder(walls);
        for (Barriers.Wall w : boundary) {
            float ox = -w.nx * THICKNESS, oy = -w.ny * THICKNESS;
            float length = Math.abs(w.bx - w.ax) + Math.abs(w.by - w.ay);
            Color shade = new Color(0.86f + w.nx * 0.08f, 0.89f + w.ny * 0.06f, 0.95f, 1f);
            face(wallBuilder, w.ax, w.ay, w.bx, w.by, 0, HEIGHT, length / 64f, shade);
            face(wallBuilder, w.bx + ox, w.by + oy, w.ax + ox, w.ay + oy, 0, HEIGHT,
                length / 64f, shade);
            wallBuilder.quad(w.ax, w.ay, HEIGHT, w.bx, w.by, HEIGHT,
                w.bx + ox, w.by + oy, HEIGHT, w.ax + ox, w.ay + oy, HEIGHT,
                0, 0, length / 64f, 0, length / 64f, 0.07f, 0, 0.07f, Color.WHITE);
        }
        wallBuilder.finish();
        Scenery3D.Builder wallBuilder2 = new Scenery3D.Builder(walls);
        Scenery3D.Builder leftBuilder = new Scenery3D.Builder(left);
        Scenery3D.Builder rightBuilder = new Scenery3D.Builder(right);
        for (TrackGuidance.Sign sign : TrackGuidance.signs(map)) {
            // a small board on two posts, its bottom at the barrier's top
            float half = 20f;
            face(sign.turnRight ? rightBuilder : leftBuilder,
                sign.x - sign.rightX * half, sign.y - sign.rightY * half,
                sign.x + sign.rightX * half, sign.y + sign.rightY * half,
                HEIGHT + 1.5f, HEIGHT + 12f, 1f, Color.WHITE);
            for (int side = -1; side <= 1; side += 2) {
                float px = sign.x + side * sign.rightX * (half - 3f), py = sign.y + side * sign.rightY * (half - 3f);
                face(wallBuilder2, px - sign.rightX * 0.6f, py - sign.rightY * 0.6f,
                    px + sign.rightX * 0.6f, py + sign.rightY * 0.6f, 0f, HEIGHT + 1.5f, 0.02f, new Color(0.35f, 0.36f, 0.4f, 1f));
            }
        }
        wallBuilder2.finish();
        leftBuilder.finish();
        rightBuilder.finish();
    }

    private static void face(Scenery3D.Builder b, float ax, float ay, float bx, float by,
            float low, float high, float repeat, Color color) {
        b.quad(ax, ay, low, bx, by, low, bx, by, high, ax, ay, high,
            0, 1, repeat, 1, repeat, 0, 0, 0, color);
    }

    public void render3D(Camera camera, ShaderProgram shader) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        shader.bind();
        shader.setUniformMatrix("u_projTrans", camera.combined);
        shader.setUniformi("u_texture", 0);
        shader.setUniformf("u_cutout", 0f);
        draw(walls, concrete, shader);
        draw(left, leftArrow, shader);
        draw(right, rightArrow, shader);
        Gdx.gl.glEnable(GL20.GL_BLEND);
    }

    private static void draw(List<Mesh> meshes, Texture texture, ShaderProgram shader) {
        texture.bind(0);
        for (Mesh mesh : meshes) mesh.render(shader, GL20.GL_TRIANGLES);
    }

    /** The same wall is visible as a yellow curb when switching to the overhead view. */
    public void renderTopDown(Batch batch) {
        for (Barriers.Wall wall : boundary) {
            batch.draw(concrete, Math.min(wall.ax, wall.bx) - 1f,
                Math.min(wall.ay, wall.by) - 1f, Math.abs(wall.bx - wall.ax) + 2f,
                Math.abs(wall.by - wall.ay) + 2f, 0, 0, 64, 6, false, false);
        }
    }

    /**
     * a concrete barrier: red and white kerb blocks along the top (as the map paints its
     * kerbs), pale concrete below with joints, darker where it meets the road
     */
    private static Texture wallTexture() {
        Pixmap p = new Pixmap(128, 64, Pixmap.Format.RGBA8888);
        p.setColor(0.78f, 0.78f, 0.76f, 1f); p.fill();
        for (int y = 40; y < 64; y++) {
            float f = 0.78f - (y - 40) * 0.012f;
            p.setColor(f, f, f * 0.98f, 1f);
            p.drawLine(0, y, 127, y);
        }
        p.setColor(0.6f, 0.6f, 0.59f, 1f);
        p.drawLine(63, 14, 63, 63);
        p.drawLine(127, 14, 127, 63);
        for (int k = 0; k < 4; k++) {
            p.setColor(k % 2 == 0 ? new Color(0.85f, 0.2f, 0.17f, 1f) : new Color(0.96f, 0.96f, 0.94f, 1f));
            p.fillRectangle(k * 32, 0, 32, 13);
        }
        p.setColor(0.45f, 0.45f, 0.45f, 1f);
        p.drawLine(0, 13, 127, 13);
        Texture t = new Texture(p); p.dispose();
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    private static Texture chevrons(boolean right) {
        Pixmap p = new Pixmap(256, 96, Pixmap.Format.RGBA8888);
        p.setColor(1f, 0.8f, 0.1f, 1f); p.fill();
        p.setColor(0.025f, 0.035f, 0.055f, 1f); p.fillRectangle(5, 5, 246, 86);
        p.setColor(1f, 0.83f, 0.12f, 1f);
        for (int n = 0; n < 3; n++) {
            int a = 22 + n * 80, b = a + 17, c = a + 48, d = a + 31;
            if (!right) { a = 255 - a; b = 255 - b; c = 255 - c; d = 255 - d; }
            p.fillTriangle(a, 14, b, 14, c, 48);
            p.fillTriangle(a, 14, c, 48, d, 48);
            p.fillTriangle(d, 48, c, 48, b, 82);
            p.fillTriangle(d, 48, b, 82, a, 82);
        }
        Texture t = new Texture(p); p.dispose();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return t;
    }

    public void dispose() {
        for (Mesh mesh : walls) mesh.dispose();
        for (Mesh mesh : left) mesh.dispose();
        for (Mesh mesh : right) mesh.dispose();
        concrete.dispose(); leftArrow.dispose(); rightArrow.dispose();
    }
}
