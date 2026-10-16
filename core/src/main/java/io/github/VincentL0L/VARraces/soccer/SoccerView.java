package io.github.VincentL0L.VARraces.soccer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/** A dedicated driver's-seat stadium view. Soccer has no overhead camera toggle. */
final class SoccerView {
    static final Color BLUE = new Color(0.12f, 0.55f, 1f, 1f);
    static final Color ORANGE = new Color(1f, 0.42f, 0.08f, 1f);
    final PerspectiveCamera camera = new PerspectiveCamera(60, 1280, 720);
    private final ShapeRenderer shapes = new ShapeRenderer(20000);
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 identity = new Matrix4();

    SoccerView() { camera.near = 0.5f; camera.far = 2500; resize(1280, 720); }

    void resize(int width, int height) {
        camera.viewportWidth = Math.max(1, width); camera.viewportHeight = Math.max(1, height);
        float aspect = camera.viewportWidth / camera.viewportHeight;
        camera.fieldOfView = MathUtils.clamp(2 * (float) Math.toDegrees(Math.atan(1.05f / aspect)), 45, 85);
        camera.update();
    }

    void render(float[][] cars, float[] ball, int me) {
        float[] driver = me >= 0 && cars[me] != null ? cars[me] : new float[] {0, 260, 450, 0, 0, 100, 0, 0};
        float heading = driver[4];
        camera.position.set(driver[1] + MathUtils.cosDeg(heading) * 3,
            driver[2] + MathUtils.sinDeg(heading) * 3, driver[3] + 13.3f);
        camera.direction.set(MathUtils.cosDeg(heading), MathUtils.sinDeg(heading), -0.035f).nor();
        camera.up.set(0, 0, 1); camera.update();
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(0.19f, 0.32f, 0.49f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST); Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE); Gdx.gl.glDisable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(camera.combined); shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        box(-100, -100, -3, 1500, 1000, -0.2f, new Color(0.06f, 0.12f, 0.17f, 1));
        for (int i = 0; i < 10; i++) {
            shapes.setColor(i % 2 == 0 ? 0.08f : 0.1f, i % 2 == 0 ? 0.36f : 0.4f, 0.25f, 1);
            shapes.rect(i * 140, 0, 140, 900);
        }
        float low = 310, high = 590;
        for (int end = 0; end < 2; end++) {
            float x = end == 0 ? -9 : 1400;
            Color team = end == 0 ? BLUE : ORANGE;
            box(x, -9, 0, x + 9, low, 52, team);
            box(x, high, 0, x + 9, 909, 52, team);
            // Above the crossbar is also a wall; high shots rebound instead of scoring.
            box(x, low, 105, x + 9, high, 300, new Color(0.09f, 0.15f, 0.23f, 1));
            box(x - 3, low - 5, 0, x + 10, low + 5, 105, team);
            box(x - 3, high - 5, 0, x + 10, high + 5, 105, team);
            box(x - 3, low, 99, x + 10, high, 108, team);
            float back = end == 0 ? -80 : 1480;
            box(Math.min(x, back), low, -1, Math.max(x + 9, back), high, 0, team);
        }
        for (int side = 0; side < 2; side++) {
            float y = side == 0 ? -9 : 900;
            for (int i = 0; i < 14; i++) {
                Color color = i < 7 ? BLUE : ORANGE;
                box(i * 100, y, 0, (i + 1) * 100, y + 9, 52, color.cpy().mul(0.5f, 0.5f, 0.5f, 1));
                box(i * 100, y - 1, 49, (i + 1) * 100, y + 10, 53, color);
                // Grandstands beyond the walls; they never obscure the pitch.
                float standY = side == 0 ? -80 : 950;
                box(i * 100, standY, 0, i * 100 + 90, standY + 35, 80, new Color(0.09f, 0.14f, 0.22f, 1));
                box(i * 100 + 4, standY + 3, 80, i * 100 + 85, standY + 20, 88, color);
            }
        }
        shapes.setTransformMatrix(transform.idt().translate(0, 0, 0.08f));
        shapes.setColor(0.78f, 0.94f, 0.89f, 1);
        shapes.rect(698, 0, 4, 900);
        shapes.rect(0, 0, 1400, 3); shapes.rect(0, 897, 1400, 3);
        shapes.rect(0, 0, 3, 900); shapes.rect(1397, 0, 3, 900);
        shapes.rect(0, 250, 180, 3); shapes.rect(0, 647, 180, 3); shapes.rect(177, 250, 3, 400);
        shapes.rect(1220, 250, 180, 3); shapes.rect(1220, 647, 180, 3); shapes.rect(1220, 250, 3, 400);
        shapes.setColor(0.025f, 0.12f, 0.08f, 1);
        shapes.circle(ball[0], ball[1], 19, 20);
        shapes.setTransformMatrix(identity);
        drawBall(ball[0], ball[1], ball[2]);
        for (int i = 0; i < cars.length; i++) if (cars[i] != null) car(cars[i], i == me);
        shapes.end();
        shapes.setTransformMatrix(identity);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(0.8f, 0.97f, 0.92f, 1);
        for (int i = 0; i < 64; i++) {
            float a = i * MathUtils.PI2 / 64, b = (i + 1) * MathUtils.PI2 / 64;
            shapes.line(700 + 110 * MathUtils.cos(a), 450 + 110 * MathUtils.sin(a), 0.12f,
                700 + 110 * MathUtils.cos(b), 450 + 110 * MathUtils.sin(b), 0.12f);
        }
        for (int end = 0; end < 2; end++) {
            float back = end == 0 ? -80 : 1480, front = end == 0 ? 0 : 1400;
            shapes.setColor(end == 0 ? BLUE : ORANGE);
            for (int y = 310; y <= 590; y += 20) shapes.line(back, y, 0, back, y, 105);
            for (int z = 0; z <= 105; z += 15) {
                shapes.line(back, 310, z, back, 590, z);
                shapes.line(front, 310, z, back, 310, z);
                shapes.line(front, 590, z, back, 590, z);
            }
        }
        // A light cage makes the side-wall/ceiling collision limits visible.
        shapes.setColor(0.29f, 0.43f, 0.55f, 1);
        for (int x = 0; x <= 1400; x += 100) {
            shapes.line(x, 0, 52, x, 0, 300); shapes.line(x, 900, 52, x, 900, 300);
            shapes.line(x, 0, 300, x, 900, 300);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST); Gdx.gl.glEnable(GL20.GL_BLEND);
    }

    private void car(float[] c, boolean own) {
        Color team = ((int) c[0]) % 2 == 0 ? BLUE : ORANGE;
        shapes.setTransformMatrix(transform.idt().translate(c[1], c[2], c[3]).rotate(Vector3.Z, c[4] - 90));
        box(-11, -18, 2, 11, 19, 7, team);
        box(-2, -18, 7, 2, 19, 7.15f, Color.WHITE);
        if (!own) {
            box(-9, -10, 7, 9, 3, 13, new Color(0.06f, 0.13f, 0.2f, 1));
            box(-9, -10, 13, 9, 3, 14, team);
        }
        for (int side = -1; side <= 1; side += 2) {
            box(side * 12 - 2, -14, 0, side * 12 + 2, -7, 6, Color.DARK_GRAY);
            box(side * 12 - 2, 9, 0, side * 12 + 2, 16, 6, Color.DARK_GRAY);
        }
        shapes.setTransformMatrix(identity);
    }

    private void box(float x0, float y0, float z0, float x1, float y1, float z1, Color color) {
        shapes.setColor(color); shapes.box(x0, y0, z1, x1 - x0, y1 - y0, z1 - z0);
    }

    private void drawBall(float x, float y, float z) {
        for (int lat = 0; lat < 8; lat++) for (int lon = 0; lon < 16; lon++) {
            float p0 = lat * MathUtils.PI / 8, p1 = (lat + 1) * MathUtils.PI / 8;
            float t0 = lon * MathUtils.PI2 / 16, t1 = (lon + 1) * MathUtils.PI2 / 16;
            Color color = (lat + lon / 2) % 3 == 0 ? new Color(0.12f, 0.18f, 0.25f, 1) : Color.WHITE;
            vertexBall(x, y, z, p0, t0, color); vertexBall(x, y, z, p1, t0, color); vertexBall(x, y, z, p1, t1, color);
            vertexBall(x, y, z, p0, t0, color); vertexBall(x, y, z, p1, t1, color); vertexBall(x, y, z, p0, t1, color);
        }
    }

    private void vertexBall(float x, float y, float z, float p, float t, Color color) {
        ImmediateModeRenderer r = shapes.getRenderer();
        r.color(color);
        r.vertex(x + 26 * MathUtils.sin(p) * MathUtils.cos(t), y + 26 * MathUtils.sin(p) * MathUtils.sin(t), z + 26 * MathUtils.cos(p));
    }

    void dispose() { shapes.dispose(); }
}
