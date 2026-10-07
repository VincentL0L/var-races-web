package io.github.VincentL0L.VARraces.Multiplayer.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Track;

/**
 * Road check for single player races, read from ui/road_mask.png (white = road)
 */
public class PixmapTrack implements Track {
    private final Pixmap mask = new Pixmap(Gdx.files.internal("ui/road_mask.png"));
    private final Color color = new Color();

    public boolean onRoad(float x, float y) {
        int px = (int) x;
        int py = mask.getHeight() - (int) y;
        if (px < 0 || py < 0 || px >= mask.getWidth() || py >= mask.getHeight()) {
            return false;
        }
        Color.rgba8888ToColor(color, mask.getPixel(px, py));
        return color.r >= 0.85f && color.g >= 0.85f && color.b >= 0.85f;
    }

    /**
     * frees the image
     */
    public void dispose() {
        mask.dispose();
    }
}
