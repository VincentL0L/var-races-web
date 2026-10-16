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

/** Raised track walls and large yellow chevron boards, generated from the collision boundary. */
public final class TrackWalls {
    private static final float HEIGHT = 32f, THICKNESS = 3f;
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
        Scenery3D.Builder leftBuilder = new Scenery3D.Builder(left);
        Scenery3D.Builder rightBuilder = new Scenery3D.Builder(right);
        for (TrackGuidance.Sign sign : TrackGuidance.signs(map)) {
            float half = 36f;
            face(sign.turnRight ? rightBuilder : leftBuilder,
                sign.x - sign.rightX * half, sign.y - sign.rightY * half,
                sign.x + sign.rightX * half, sign.y + sign.rightY * half,
                7f, 31f, 1f, Color.WHITE);
        }
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

    private static Texture wallTexture() {
        Pixmap p = new Pixmap(128, 64, Pixmap.Format.RGBA8888);
        p.setColor(0.18f, 0.23f, 0.3f, 1f); p.fill();
        p.setColor(0.29f, 0.35f, 0.43f, 1f); p.fillRectangle(2, 10, 124, 44);
        p.setColor(0.1f, 0.14f, 0.19f, 1f);
        p.fillRectangle(0, 8, 3, 48); p.fillRectangle(63, 8, 3, 48);
        p.setColor(1f, 0.76f, 0.12f, 1f);
        p.fillRectangle(0, 0, 128, 7); p.fillRectangle(0, 57, 128, 7);
        Texture t = new Texture(p); p.dispose();
        t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.ClampToEdge);
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
