package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Draws the items on the track (the pixel art from tools/MakeItems.java): item boxes that
 * bob and glint, oil slicks on the road, rockets with a spark trail and shield bubbles.
 * What's there comes from ItemSystem.describe() (from the server when online).
 */
public class ItemRenderer {
    private final Texture box = load("box"), rocket = load("rocket"), oil = load("oil"), shield = load("shield");
    private final Texture[] icons = new Texture[ItemSystem.Item.values().length];
    private final List<Vector2> boxes = new ArrayList<>();
    private float clock = 0f;

    /**
     * @param map the map being raced (the boxes sit in the same places every race)
     */
    public ItemRenderer(TrackMap map) {
        for (Vector2[] row : ItemSystem.boxRows(map.waypoints, map.pointToPoint)) {
            for (Vector2 p : row) {
                boxes.add(p);
            }
        }
        String[] names = {"nitro", "rocket", "oil", "bubble", "pulse", "frost", "fire"};
        for (int i = 0; i < names.length; i++) {
            icons[i] = load(names[i]);
        }
    }

    private static Texture load(String name) {
        Texture t = new Texture(Gdx.files.internal("ui/items/" + name + ".png"));
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        return t;
    }

    /**
     * @param item an item
     * @return its HUD icon
     */
    public Texture icon(ItemSystem.Item item) {
        return icons[item.ordinal()];
    }

    /**
     * the things lying on the road: item boxes and oil slicks (drawn under the cars)
     * @param batch a batch already begun, in world coordinates
     * @param view ItemSystem.describe() text
     */
    public void drawGround(SpriteBatch batch, String view, float delta) {
        clock += delta;
        String[] parts = view.split("\\|", -1);
        if (parts.length < 4) {
            return;
        }
        for (String s : parts[2].split(";")) {
            float[] p = numbers(s);
            if (p != null) {
                batch.draw(oil, p[0] - 12f, p[1] - 12f, 24f, 24f);
            }
        }
        for (int i = 0; i < boxes.size() && i < parts[0].length(); i++) {
            if (parts[0].charAt(i) != '1') {
                continue;
            }
            Vector2 b = boxes.get(i);
            // each box bobs a little out of step with its neighbours, and glints now and then
            float bob = MathUtils.sin(clock * 3f + i * 1.7f) * 1.5f;
            float size = 15f;
            batch.setColor(0f, 0f, 0f, 0.3f);
            batch.draw(box, b.x - size / 2f + 2f, b.y - size / 2f - 3f, size, size * 0.5f);
            float glint = MathUtils.sin(clock * 5f + i) > 0.92f ? 1.3f : 1f;
            batch.setColor(glint, glint, glint, 1f);
            batch.draw(box, b.x - size / 2f, b.y - size / 2f + bob, size / 2f, size / 2f, size, size, 1f, 1f,
                MathUtils.sin(clock * 2f + i) * 8f, 0, 0, 16, 16, false, false);
            batch.setColor(1f, 1f, 1f, 1f);
        }
    }

    /**
     * the things above the road: rockets in flight and shield bubbles round protected cars
     * @param batch a batch already begun, in world coordinates
     * @param view ItemSystem.describe() text
     * @param cars car centers by racer id
     */
    public void drawAir(SpriteBatch batch, String view, Map<String, Vector2> cars) {
        String[] parts = view.split("\\|", -1);
        if (parts.length < 4) {
            return;
        }
        for (String s : parts[1].split(";")) {
            float[] p = numbers(s);
            if (p == null) {
                continue;
            }
            // sparks trailing behind
            for (int k = 1; k <= 4; k++) {
                float tx = p[0] - MathUtils.cosDeg(p[2]) * k * 6f, ty = p[1] - MathUtils.sinDeg(p[2]) * k * 6f;
                batch.setColor(1f, 0.85f - k * 0.12f, 0.3f, 0.8f - k * 0.17f);
                batch.draw(shield, tx - 2f, ty - 2f, 4f, 4f);
            }
            batch.setColor(1f, 1f, 1f, 1f);
            batch.draw(rocket, p[0] - 7f, p[1] - 7f, 7f, 7f, 14f, 14f, 1f, 1f, p[2] - 90f, 0, 0, 16, 16, false, false);
        }
        // blaster shots: an ice shard or a fireball, with a short glowing trail
        if (parts.length > 4) {
            for (String sh : parts[4].split(";")) {
                float[] p = numbers(sh);
                if (p == null) {
                    continue;
                }
                boolean fire = p[2] > 0.5f;
                for (int k = 3; k >= 0; k--) {
                    float a = 0.85f - k * 0.2f, size = 9f - k * 1.5f;
                    if (fire) {
                        batch.setColor(1f, 0.55f + k * 0.08f, 0.15f, a);
                    } else {
                        batch.setColor(0.6f, 0.9f, 1f, a);
                    }
                    batch.draw(shield, p[0] - size / 2f, p[1] - size / 2f, size, size);
                }
                batch.setColor(1f, 1f, 1f, 1f);
                batch.draw(fire ? icons[6] : icons[5], p[0] - 6f, p[1] - 6f, 12f, 12f);
            }
        }
        for (String id : parts[3].split(";")) {
            Vector2 c = cars.get(id);
            if (c != null) {
                float pulse = 1f + MathUtils.sin(clock * 6f) * 0.05f;
                float size = 30f * pulse;
                batch.draw(shield, c.x - size / 2f, c.y - size / 2f, size, size);
            }
        }
    }

    // ---------------------------------------------------------------- first person

    private final com.badlogic.gdx.math.Matrix4 m = new com.badlogic.gdx.math.Matrix4();
    private final com.badlogic.gdx.math.Vector3 eye = new com.badlogic.gdx.math.Vector3();

    /**
     * the items as things in the world, for the first-person view: spinning item cubes
     * floating over their shadows, oil on the road, rocket models with spark trails,
     * glowing blaster shots and shield bubbles round the cars
     * @param cam the first-person camera (depth testing on, so buildings and cars hide things)
     */
    public void draw3D(com.badlogic.gdx.graphics.Camera cam, SpriteBatch batch,
            com.badlogic.gdx.graphics.glutils.ShapeRenderer shapes, String view, Map<String, Vector2> cars, float delta) {
        clock += delta;
        String[] parts = view.split("\\|", -1);
        if (parts.length < 4) {
            return;
        }
        eye.set(cam.position);
        batch.setProjectionMatrix(cam.combined);
        // oil slicks, flat on the road (each lifted onto the hill where it lies)
        for (String s : parts[2].split(";")) {
            float[] p = numbers(s);
            if (p != null) {
                batch.setTransformMatrix(m.idt().translate(0f, 0f, Terrain.height(p[0], p[1]) + 0.12f));
                batch.begin();
                batch.draw(oil, p[0] - 14f, p[1] - 14f, 28f, 28f);
                batch.end();
            }
        }
        // the item boxes' shadows
        for (int i = 0; i < boxes.size() && i < parts[0].length(); i++) {
            if (parts[0].charAt(i) == '1') {
                Vector2 b = boxes.get(i);
                batch.setTransformMatrix(m.idt().translate(0f, 0f, Terrain.height(b.x, b.y) + 0.12f));
                batch.begin();
                batch.setColor(0f, 0f, 0f, 0.3f);
                batch.draw(shield, b.x - 5f, b.y - 5f, 10f, 10f);
                batch.setColor(1f, 1f, 1f, 1f);
                batch.end();
            }
        }

        // item cubes: each face is the box art; only the faces turned toward us are drawn
        float s = 8f;
        for (int i = 0; i < boxes.size() && i < parts[0].length(); i++) {
            if (parts[0].charAt(i) != '1') {
                continue;
            }
            Vector2 b = boxes.get(i);
            if (b.dst2(eye.x, eye.y) > 1800f * 1800f) {
                continue;
            }
            float zc = 7f + MathUtils.sin(clock * 3f + i * 1.7f) * 1.2f + Terrain.height(b.x, b.y);
            float spin = clock * 70f + i * 40f;
            float glint = MathUtils.sin(clock * 5f + i) > 0.92f ? 1.35f : 1f;
            batch.setColor(glint, glint, glint, 1f);
            for (int k = 0; k < 4; k++) {
                float a = spin + k * 90f;
                // the face's outward direction
                float nx = -MathUtils.sinDeg(a), ny = MathUtils.cosDeg(a);
                float fx = b.x + nx * s / 2f, fy = b.y + ny * s / 2f;
                if ((eye.x - fx) * nx + (eye.y - fy) * ny <= 0f) {
                    continue;
                }
                m.idt().translate(b.x, b.y, zc).rotate(0f, 0f, 1f, a).translate(0f, s / 2f, 0f).rotate(1f, 0f, 0f, 90f);
                face(batch, box, -s);       // seen from outside, so mirrored back
            }
            if (eye.z > zc + s / 2f) {
                m.idt().translate(b.x, b.y, zc + s / 2f).rotate(0f, 0f, 1f, spin);
                face(batch, box, s);
            }
            batch.setColor(1f, 1f, 1f, 1f);
        }

        // rockets: a little red rocket model, nose first, with sparks streaming behind
        shapes.setProjectionMatrix(cam.combined);
        for (String r : parts[1].split(";")) {
            float[] p = numbers(r);
            if (p == null) {
                continue;
            }
            m.idt().translate(p[0], p[1], Terrain.height(p[0], p[1])).rotate(0f, 0f, 1f, p[2] - 90f);
            shapes.setTransformMatrix(m);
            shapes.begin(com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.85f, 0.2f, 0.18f, 1f);
            up(shapes, -1.3f, -5f, 3.2f, 2.6f, 8f, 2.6f);
            shapes.setColor(0.97f, 0.97f, 0.95f, 1f);
            up(shapes, -0.9f, 3f, 3.6f, 1.8f, 2f, 1.8f);
            up(shapes, -0.9f, -1f, 3.1f, 1.8f, 1f, 2.8f);
            shapes.setColor(0.55f, 0.12f, 0.1f, 1f);
            up(shapes, -2.6f, -5f, 3.9f, 5.2f, 2f, 0.4f);
            up(shapes, -0.2f, -5f, 2.0f, 0.4f, 2f, 4.6f);
            shapes.end();
            for (int k = 1; k <= 6; k++) {
                float tx = p[0] - MathUtils.cosDeg(p[2]) * (4f + k * 3.5f), ty = p[1] - MathUtils.sinDeg(p[2]) * (4f + k * 3.5f);
                batch.setColor(1f, 0.9f - k * 0.1f, 0.35f, 0.9f - k * 0.13f);
                billboard(batch, shield, tx, ty, 4.4f + MathUtils.sin(clock * 30f + k) * 0.4f + Terrain.height(tx, ty), 3.2f - k * 0.3f);
            }
            batch.setColor(1f, 1f, 1f, 1f);
        }
        // blaster shots: a glowing ice shard or fireball with a short trail
        if (parts.length > 4) {
            for (String sh : parts[4].split(";")) {
                float[] p = numbers(sh);
                if (p == null) {
                    continue;
                }
                boolean fire = p[2] > 0.5f;
                for (int k = 3; k >= 0; k--) {
                    float a = 0.8f - k * 0.18f, size = 10f - k * 1.6f;
                    if (fire) {
                        batch.setColor(1f, 0.55f + k * 0.08f, 0.15f, a);
                    } else {
                        batch.setColor(0.6f, 0.9f, 1f, a);
                    }
                    billboard(batch, shield, p[0], p[1], 4.5f + Terrain.height(p[0], p[1]), size);
                }
                batch.setColor(1f, 1f, 1f, 1f);
                billboard(batch, fire ? icons[6] : icons[5], p[0], p[1], 4.5f + Terrain.height(p[0], p[1]), 7f);
            }
        }
        // shields: a shimmering bubble round the car
        for (String id : parts[3].split(";")) {
            Vector2 c = cars.get(id);
            if (c != null && c.dst2(eye.x, eye.y) > 64f) {
                float size = 26f * (1f + MathUtils.sin(clock * 6f) * 0.05f);
                batch.setColor(1f, 1f, 1f, 0.85f);
                billboard(batch, shield, c.x, c.y, 4f + Terrain.height(c.x, c.y), size);
                batch.setColor(1f, 1f, 1f, 1f);
            }
        }
    }

    /** one square face, already placed by m */
    private void face(SpriteBatch batch, Texture t, float s) {
        batch.setTransformMatrix(m);
        batch.begin();
        // a negative size draws the picture mirrored left to right
        batch.draw(t, -s / 2f, -Math.abs(s) / 2f, s, Math.abs(s));
        batch.end();
    }

    /** an upright square turned to face the camera */
    private void billboard(SpriteBatch batch, Texture t, float x, float y, float z, float size) {
        float yaw = MathUtils.atan2(eye.x - x, -(eye.y - y)) * MathUtils.radiansToDegrees;
        m.idt().translate(x, y, z).rotate(0f, 0f, 1f, yaw).rotate(1f, 0f, 0f, 90f);
        face(batch, t, size);
    }

    /** a box standing on z and rising d (ShapeRenderer's own box hangs down from z) */
    private static void up(com.badlogic.gdx.graphics.glutils.ShapeRenderer sr, float x, float y, float z, float w, float h, float d) {
        sr.box(x, y, z + d, w, h, d);
    }

    private static float[] numbers(String s) {
        if (s.isEmpty()) {
            return null;
        }
        String[] bits = s.split(",");
        float[] out = new float[bits.length];
        try {
            for (int i = 0; i < bits.length; i++) {
                out[i] = Float.parseFloat(bits[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    public void dispose() {
        box.dispose();
        rocket.dispose();
        oil.dispose();
        shield.dispose();
        for (Texture t : icons) {
            t.dispose();
        }
    }
}
