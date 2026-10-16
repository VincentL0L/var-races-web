package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
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
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Image;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * First-person driving: the same flat track, seen from the driver's seat through a
 * perspective camera (the SNES "Mode 7" trick). The map lies on the ground (z = 0), the
 * other cars are little boxes with their top-down art on the roof, and our own car's
 * hood sits at the bottom of the view. Purely a way of looking: the race is the same.
 */
public class FirstPersonView {
    /** how high the roof of a car is (a car is 10 wide and 20 long) */
    private static final float ROOF = 6f;
    /** the driver's eyes: above the roof, toward the front, so just the end of the hood shows */
    private static final float EYE_HEIGHT = 10f, EYE_BACK = -4f;
    private static final float FAR = 2600f;

    private final PerspectiveCamera cam = new PerspectiveCamera(70f, 640f, 360f);
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final Matrix4 transform = new Matrix4();
    private final Map<Texture, Color> bodyColors = new HashMap<>();
    private final Color sky;
    private float heading = Float.NaN;

    private final Scenery3D scenery;

    public FirstPersonView(TrackMap map, Texture mapPicture) {
        scenery = new Scenery3D(map, mapPicture);
        cam.near = 1f;
        cam.far = FAR;
        cam.up.set(0f, 0f, 1f);
        sky = map.id.equals(TrackMap.MOON) ? new Color(0.02f, 0.02f, 0.07f, 1f)
            : map.battle ? new Color(0.55f, 0.66f, 0.82f, 1f) : new Color(0.47f, 0.72f, 0.96f, 1f);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    public void resize(int width, int height) {
        cam.viewportWidth = Math.max(1, width);
        cam.viewportHeight = Math.max(1, height);
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
        float sx = shake > 0.05f ? MathUtils.random(-shake, shake) * 0.4f : 0f;
        float sz = shake > 0.05f ? MathUtils.random(-shake, shake) * 0.3f : 0f;
        cam.position.set(x - fx * EYE_BACK - fy * sx, y - fy * EYE_BACK + fx * sx, EYE_HEIGHT + sz);
        cam.direction.set(fx, fy, -0.06f).nor();
        cam.up.set(0f, 0f, 1f);
        cam.update();
    }

    /** the map's trees and buildings, in 3D */
    public void drawScenery() {
        scenery.render(cam);
        Gdx.gl.glEnable(GL20.GL_BLEND);
    }

    public PerspectiveCamera getCamera() {
        return cam;
    }

    public Color getSky() {
        return sky;
    }

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
     * the other cars as boxes, farthest first so nearer ones cover them
     * @param others every car but ours
     */
    public void drawCars(List<CarShape> others) {
        List<CarShape> order = new ArrayList<>(others);
        final Vector3 eye = cam.position;
        order.sort((a, b) -> Float.compare(dist2(b, eye), dist2(a, eye)));
        Gdx.gl.glEnable(GL20.GL_BLEND);
        for (CarShape c : order) {
            if (dist2(c, eye) > FAR * FAR) {
                continue;
            }
            Texture art = artOf(c.img);
            Color tint = c.img.getColor();
            Color body = art == null ? Color.DARK_GRAY : bodyColor(art);
            float w = c.img.getWidth(), h = c.img.getHeight();
            transform.idt().translate(c.x, c.y, 0f).rotate(0f, 0f, 1f, c.rotation);
            // a soft shadow, then the sides (darker toward the bottom), then the roof art
            shapes.setProjectionMatrix(cam.combined);
            shapes.setTransformMatrix(transform);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0f, 0f, 0f, 0.3f);
            shapes.rect(-w / 2f - 1.5f, -h / 2f - 1.5f, w + 3f, h + 3f);
            shapes.setColor(body.r * 0.35f * tint.r, body.g * 0.35f * tint.g, body.b * 0.35f * tint.b, 1f);
            shapes.box(-w / 2f, -h / 2f, 0.6f, w, h, 2.4f);
            shapes.setColor(body.r * 0.75f * tint.r, body.g * 0.75f * tint.g, body.b * 0.75f * tint.b, 1f);
            shapes.box(-w / 2f, -h / 2f, 3f, w, h, ROOF - 3f);
            shapes.end();
            if (art != null) {
                transform.translate(0f, 0f, ROOF + 0.05f);
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
    }

    /** our own car: just its roof and hood, under the eyes */
    public void drawOwnCar(Image img) {
        Texture art = artOf(img);
        if (art == null) {
            return;
        }
        float w = img.getWidth(), h = img.getHeight();
        transform.idt().translate(img.getX() + w / 2f, img.getY() + h / 2f, ROOF)
            .rotate(0f, 0f, 1f, img.getRotation());
        batch.setProjectionMatrix(cam.combined);
        batch.setTransformMatrix(transform);
        // this close up, crisp pixels look far better than a blur
        Texture.TextureFilter min = art.getMinFilter(), mag = art.getMagFilter();
        art.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        batch.begin();
        batch.setColor(img.getColor());
        batch.draw(art, -w / 2f, -h / 2f, w, h);
        batch.setColor(Color.WHITE);
        batch.end();
        art.setFilter(min, mag);
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

    private static float dist2(CarShape c, Vector3 eye) {
        float dx = c.x - eye.x, dy = c.y - eye.y;
        return dx * dx + dy * dy;
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
                // sample the middle band (the doors), ignoring see-through and near-black pixels
                for (int yy = p.getHeight() / 3; yy < p.getHeight() * 2 / 3; yy++) {
                    for (int xx = 0; xx < p.getWidth(); xx++) {
                        Color px = new Color(p.getPixel(xx, yy));
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
