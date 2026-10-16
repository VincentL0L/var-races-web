package io.github.VincentL0L.VARraces;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Image;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * First-person driving: the flat map seen from the driver's seat through a perspective
 * camera, with the trees and buildings stood up (Scenery3D), a sky with clouds and a sun,
 * haze toward the horizon, and the cars built as little models: wheels, body, glass cabin,
 * roof and lights, with their top-down paint job on the bonnet and boot. We sit inside our
 * own car, looking out over the bonnet under the roof. Purely a way of looking: the race
 * is the same.
 */
public class FirstPersonView {
    /** the bonnet camera: just above the roof line, at the front of the cabin */
    private static final float EYE_HEIGHT = 6.3f, EYE_BACK = -3.5f;
    private static final float FAR = 2600f;

    private final PerspectiveCamera cam = new PerspectiveCamera(72f, 640f, 360f);
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final ShaderProgram fog;
    private final Matrix4 transform = new Matrix4();
    private final Matrix4 screen = new Matrix4();
    private final Map<Texture, Color> bodyColors = new HashMap<>();
    /** sky at the top, and at the horizon (also the haze colour) */
    private final Color skyTop, skyLow;
    private final boolean space;
    private float heading = Float.NaN;
    private final Scenery3D scenery;
    /** clouds (or stars): direction, height above the horizon (degrees), size */
    private final float[][] clouds;

    public FirstPersonView(TrackMap map, Texture mapPicture) {
        fog = new ShaderProgram(Scenery3D.VERTEX, Scenery3D.FRAGMENT);
        if (!fog.isCompiled()) {
            Gdx.app.error("FirstPersonView", fog.getLog());
        }
        scenery = new Scenery3D(map, mapPicture, fog);
        cam.near = 0.3f;
        cam.far = FAR;
        cam.up.set(0f, 0f, 1f);
        space = map.id.equals(TrackMap.MOON);
        if (space) {
            skyTop = new Color(0.01f, 0.01f, 0.04f, 1f);
            skyLow = new Color(0.07f, 0.07f, 0.13f, 1f);
        } else if (map.id.equals(TrackMap.ROOFTOP)) {
            skyTop = new Color(0.23f, 0.25f, 0.55f, 1f);       // dusk over the rooftops
            skyLow = new Color(0.98f, 0.66f, 0.5f, 1f);
        } else {
            skyTop = new Color(0.27f, 0.52f, 0.9f, 1f);
            skyLow = new Color(0.74f, 0.86f, 0.97f, 1f);
        }
        java.util.Random rnd = new java.util.Random(map.id.hashCode());
        clouds = new float[space ? 160 : 16][];
        for (int i = 0; i < clouds.length; i++) {
            clouds[i] = space
                ? new float[] {rnd.nextFloat() * 360f, rnd.nextFloat() * 50f, 1f + rnd.nextFloat() * 1.5f}
                : new float[] {rnd.nextFloat() * 360f, 3f + rnd.nextFloat() * 16f, 0.6f + rnd.nextFloat() * 0.9f};
        }
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    public void resize(int width, int height) {
        cam.viewportWidth = Math.max(1, width);
        cam.viewportHeight = Math.max(1, height);
        // about 90 degrees across, whatever the shape of the screen (a tall phone sees less sky and bonnet)
        float aspect = cam.viewportWidth / cam.viewportHeight;
        cam.fieldOfView = MathUtils.clamp(2f * (float) Math.toDegrees(Math.atan(1f / aspect)), 40f, 75f);
        cam.update();
    }

    /**
     * puts the camera in our driver's seat
     * @param x car center x   @param y car center y
     * @param headingDeg the way the car points (degrees, 0 = +x)
     * @param shake a crash's shake (map pixels)
     */
    public void update(float x, float y, float headingDeg, float delta, float shake) {
        // the view swings round smoothly instead of snapping with every twitch of the wheel
        heading = Float.isNaN(heading) ? headingDeg
            : MathUtils.lerpAngleDeg(heading, headingDeg, 1f - (float) Math.exp(-18f * delta));
        float fx = MathUtils.cosDeg(heading), fy = MathUtils.sinDeg(heading);
        float sx = shake > 0.05f ? MathUtils.random(-shake, shake) * 0.15f : 0f;
        float sz = shake > 0.05f ? MathUtils.random(-shake, shake) * 0.1f : 0f;
        clock += delta;
        cam.position.set(x - fx * EYE_BACK - fy * sx, y - fy * EYE_BACK + fx * sx, ownEye + sz);
        cam.direction.set(fx, fy, -0.045f).nor();
        cam.up.set(0f, 0f, 1f);
        cam.update();
    }

    /** for drawing the items in the world (ItemRenderer.draw3D) */
    public SpriteBatch getBatch() {
        return batch;
    }

    public ShapeRenderer getShapes() {
        return shapes;
    }

    public PerspectiveCamera getCamera() {
        return cam;
    }

    /** @return the haze colour (the sky at the horizon) */
    public Color getSky() {
        return skyLow;
    }

    /**
     * the haze shader, ready for drawing the ground (hand it to the ground's SpriteBatch)
     */
    public ShaderProgram groundShader() {
        fog.bind();
        fog.setUniformf("u_eye", cam.position);
        fog.setUniformf("u_fogRange", Scenery3D.FOG_START, Scenery3D.FOG_END);
        fog.setUniformf("u_fogColor", skyLow.r, skyLow.g, skyLow.b);
        fog.setUniformf("u_cutout", 0f);
        return fog;
    }

    // ---------------------------------------------------------------- sky

    /** the sky: a gradient down to the horizon, clouds (stars on the moon) and the sun */
    public void drawSky() {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        // where the horizon is on screen: a point far ahead at eye level
        Vector3 p = new Vector3(cam.position.x + cam.direction.x * 5000f, cam.position.y + cam.direction.y * 5000f, cam.position.z);
        cam.project(p, 0, 0, w, h);
        float horizon = MathUtils.clamp(p.y, -h, h * 2f);
        screen.setToOrtho2D(0, 0, w, h);
        Gdx.gl.glViewport(0, 0, w, h);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        shapes.setProjectionMatrix(screen);
        shapes.setTransformMatrix(transform.idt());
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.rect(0, Math.max(0, horizon), w, h - Math.max(0, horizon), skyLow, skyLow, skyTop, skyTop);
        shapes.setColor(skyLow);
        shapes.rect(0, 0, w, Math.max(0, horizon));
        float pxPerDeg = w / (2f * hfov());
        // the sun (or the Earth, from the moon)
        float sunAz = 35f, sunEl = space ? 22f : 30f;
        float rel = wrap(sunAz - heading);
        if (Math.abs(rel) < hfov() + 20f) {
            float sx = w / 2f - rel * pxPerDeg, sy = horizon + sunEl * pxPerDeg;
            float r = (space ? 34f : 26f) * w / 1280f;
            if (space) {
                shapes.setColor(0.2f, 0.45f, 0.95f, 1f);
                shapes.circle(sx, sy, r, 40);
                shapes.setColor(0.25f, 0.65f, 0.3f, 1f);
                shapes.circle(sx - r * 0.3f, sy + r * 0.2f, r * 0.4f, 20);
                shapes.circle(sx + r * 0.35f, sy - r * 0.3f, r * 0.3f, 20);
            } else {
                shapes.setColor(1f, 0.97f, 0.8f, 0.25f);
                shapes.circle(sx, sy, r * 2.2f, 40);
                shapes.setColor(1f, 0.98f, 0.88f, 1f);
                shapes.circle(sx, sy, r, 40);
            }
        }
        for (float[] c : clouds) {
            rel = wrap(c[0] - heading);
            if (Math.abs(rel) > hfov() + 30f) {
                continue;
            }
            float cx = w / 2f - rel * pxPerDeg, cy = horizon + c[1] * pxPerDeg;
            if (space) {
                shapes.setColor(1f, 1f, 1f, 0.5f + c[2] * 0.3f);
                shapes.rect(cx, cy, c[2] * 1.5f, c[2] * 1.5f);
                continue;
            }
            float s = c[2] * w / 9f;
            // a flat-bottomed cloud: shaded underside, then the bright puffs
            shapes.setColor(skyLow.r * 0.92f, skyLow.g * 0.93f, skyLow.b * 0.97f, 0.95f);
            shapes.ellipse(cx - s * 0.55f, cy - s * 0.06f, s * 1.1f, s * 0.2f, 24);
            shapes.setColor(1f, 1f, 1f, 0.95f);
            shapes.ellipse(cx - s * 0.45f, cy, s * 0.5f, s * 0.22f, 24);
            shapes.ellipse(cx - s * 0.15f, cy + s * 0.04f, s * 0.5f, s * 0.3f, 24);
            shapes.ellipse(cx + s * 0.1f, cy, s * 0.42f, s * 0.2f, 24);
        }
        shapes.end();
    }

    private float hfov() {
        // half the horizontal field of view, in degrees
        float aspect = cam.viewportWidth / cam.viewportHeight;
        return (float) Math.toDegrees(Math.atan(Math.tan(Math.toRadians(cam.fieldOfView / 2f)) * aspect));
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg > 180f) {
            deg -= 360f;
        } else if (deg < -180f) {
            deg += 360f;
        }
        return deg;
    }

    /** the map's trees and buildings, in 3D (depth testing stays on for the cars) */
    public void drawScenery() {
        groundShader();
        scenery.render(cam);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    // ---------------------------------------------------------------- cars

    /** one car to draw: where it is, which way it points, its art and tint */
    public static class CarShape {
        final float x, y, rotation;
        final Image img;

        public CarShape(Image img) {
            this.img = img;
            this.x = img.getX() + img.getWidth() / 2f;
            this.y = img.getY() + img.getHeight() / 2f;
            this.rotation = img.getRotation();
        }
    }

    /**
     * the other cars, as models (the depth buffer sorts out what hides what)
     * @param others every car but ours
     */
    public void drawCars(List<CarShape> others) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        for (CarShape c : others) {
            float dx = c.x - cam.position.x, dy = c.y - cam.position.y;
            if (dx * dx + dy * dy > FAR * FAR) {
                continue;
            }
            drawModel(c.img, c.x, c.y, c.rotation, false);
        }
    }

    /** our own car: just the bonnet in front of us */
    public void drawOwnCar(Image img) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        drawModel(img, img.getX() + img.getWidth() / 2f, img.getY() + img.getHeight() / 2f, img.getRotation(), true);
    }

    private final Map<Texture, CarModels3D.Shape> carShapes = new HashMap<>();
    private float clock = 0f;
    /** how high our eyes are in our own car (a bus sits far higher than a scooter) */
    private float ownEye = EYE_HEIGHT;

    private CarModels3D.Shape shapeFor(Texture art) {
        CarModels3D.Shape sh = carShapes.get(art);
        if (sh == null) {
            sh = CarModels3D.shapeOf(art);
            carShapes.put(art, sh);
        }
        return sh;
    }

    /**
     * one car, built to its own shape (CarModels3D), then its paint job laid over the top
     * (local x across the car, y toward its nose)
     */
    private void drawModel(Image img, float x, float y, float rotation, boolean inside) {
        Texture art = artOf(img);
        Color tint = img.getColor();
        Color body = art == null ? new Color(0.5f, 0.5f, 0.55f, 1f) : new Color(bodyColor(art));
        body.mul(tint.r, tint.g, tint.b, 1f);
        CarModels3D.Shape shape = shapeFor(art);
        if (inside) {
            ownEye = CarModels3D.eyeHeight(shape);
        }
        float w = img.getWidth(), h = img.getHeight();
        transform.idt().translate(x, y, 0f).rotate(0f, 0f, 1f, rotation);
        shapes.setProjectionMatrix(cam.combined);
        shapes.setTransformMatrix(transform);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        CarModels3D.draw(shapes, shape, w, h, body, inside, clock);
        shapes.end();
        if (art != null && !shape.has("BIKE")) {
            // the top-down paint job across the bonnet and boot (the cabin hides its middle)
            transform.translate(0f, 0f, shape.deck + 0.02f);
            batch.setProjectionMatrix(cam.combined);
            batch.setTransformMatrix(transform);
            Texture.TextureFilter min = art.getMinFilter(), mag = art.getMagFilter();
            art.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            batch.begin();
            batch.setColor(tint);
            batch.draw(art, -w / 2f, -h / 2f, w, h);
            batch.setColor(Color.WHITE);
            batch.end();
            art.setFilter(min, mag);
        }
    }

    /** a solid box standing on z and rising d (ShapeRenderer's own box hangs down from z) */
    private void up(float x, float y, float z, float w, float h, float d) {
        shapes.box(x, y, z + d, w, h, d);
    }

    /** a batch lifted off the ground, for things in the air (rockets, shots, shields) */
    public SpriteBatch airBatch(float height) {
        batch.setProjectionMatrix(cam.combined);
        batch.setTransformMatrix(transform.idt().translate(0f, 0f, height));
        return batch;
    }

    /** a batch lying on the ground */
    public SpriteBatch groundBatch() {
        return airBatch(0.05f);
    }

    /**
     * @return true if a point on the map is in front of us and on screen
     * (out = its screen position, in back-buffer pixels)
     */
    public boolean project(float x, float y, float z, Vector3 out) {
        out.set(x, y, z);
        float fx = cam.direction.x, fy = cam.direction.y;
        if ((x - cam.position.x) * fx + (y - cam.position.y) * fy < 2f) {
            return false;
        }
        cam.project(out, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        return out.x >= 0 && out.x <= Gdx.graphics.getBackBufferWidth()
            && out.y >= 0 && out.y <= Gdx.graphics.getBackBufferHeight();
    }

    private static Texture artOf(Image img) {
        if (img.getDrawable() instanceof com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable) {
            return ((com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable) img.getDrawable())
                .getRegion().getTexture();
        }
        return null;
    }

    /** the main colour of a car's paint, from the middle of its art */
    private Color bodyColor(Texture art) {
        Color c = bodyColors.get(art);
        if (c != null) {
            return c;
        }
        c = new Color(0.5f, 0.5f, 0.5f, 1f);
        try {
            if (art.getTextureData() instanceof com.badlogic.gdx.graphics.glutils.FileTextureData) {
                Pixmap p = new Pixmap(((com.badlogic.gdx.graphics.glutils.FileTextureData) art.getTextureData()).getFileHandle());
                float r = 0, g = 0, b = 0;
                int n = 0;
                Color px = new Color();
                // sample the middle band (the doors), ignoring see-through and near-black pixels
                for (int yy = p.getHeight() / 3; yy < p.getHeight() * 2 / 3; yy++) {
                    for (int xx = 0; xx < p.getWidth(); xx++) {
                        Color.rgba8888ToColor(px, p.getPixel(xx, yy));
                        if (px.a > 0.5f && px.r + px.g + px.b > 0.25f) {
                            r += px.r;
                            g += px.g;
                            b += px.b;
                            n++;
                        }
                    }
                }
                p.dispose();
                if (n > 0) {
                    c.set(r / n, g / n, b / n, 1f);
                }
            }
        } catch (RuntimeException e) {
            // keep grey
        }
        bodyColors.put(art, c);
        return c;
    }

    public void dispose() {
        scenery.dispose();
        batch.dispose();
        shapes.dispose();
        fog.dispose();
    }

    // ---- the setting, remembered between visits ----

    private static Boolean enabled;

    /** @return true when races are driven in first person */
    public static boolean isEnabled() {
        if (enabled == null) {
            Preferences p = prefs();
            enabled = p != null && p.getBoolean("firstPerson", false);
        }
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        Preferences p = prefs();
        if (p != null) {
            p.putBoolean("firstPerson", value);
            p.flush();
        }
    }

    private static Preferences prefs() {
        try {
            return Gdx.app.getPreferences("var-races");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
