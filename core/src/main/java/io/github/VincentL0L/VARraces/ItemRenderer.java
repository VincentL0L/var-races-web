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
        for (Vector2[] row : ItemSystem.boxRows(map.waypoints)) {
            for (Vector2 p : row) {
                boxes.add(p);
            }
        }
        String[] names = {"nitro", "rocket", "oil", "bubble", "pulse"};
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
        for (String id : parts[3].split(";")) {
            Vector2 c = cars.get(id);
            if (c != null) {
                float pulse = 1f + MathUtils.sin(clock * 6f) * 0.05f;
                float size = 30f * pulse;
                batch.draw(shield, c.x - size / 2f, c.y - size / 2f, size, size);
            }
        }
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
