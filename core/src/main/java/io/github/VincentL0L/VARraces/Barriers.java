package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * Barriers around the track so nobody can skip part of it across the grass.
 *
 * Next to the road there's a strip of grass you can drive on (slowly, see Player), and
 * past that a red and white wall. The wall follows the road's shape, worked out from
 * road_mask.png: every spot gets its distance to the nearest road, and anything further
 * than RUNOFF is behind the wall. Where two stretches of track run close together, the
 * wall runs down the grass between them.
 */
public class Barriers {
    /** how far from the road a car's center can go, in map pixels */
    public static final float RUNOFF = 20f;
    /** the distances are worked out on a grid of this many map pixels per cell */
    private static final int CELL = 2;
    // chamfer distance steps (straight and diagonal, about 3 : 4.24)
    private static final int STRAIGHT = 3;
    private static final int DIAGONAL = 4;
    /** the painted wall: starts just past where a car's center stops, this many pixels thick */
    private static final float WALL_START = RUNOFF + 5f;
    private static final float WALL_THICKNESS = 8f;

    private static int gridWidth;
    private static int gridHeight;
    private static int mapHeight;
    /** distance from each cell to the road, in chamfer steps (3 per cell) */
    private static int[] distance;

    /**
     * works out the distances once from the road mask
     * @param roadMask road_mask.png (white = road)
     */
    public static void build(Pixmap roadMask) {
        if (distance != null) {
            return;
        }
        mapHeight = roadMask.getHeight();
        gridWidth = roadMask.getWidth() / CELL;
        gridHeight = mapHeight / CELL;
        int[] d = new int[gridWidth * gridHeight];
        int far = Integer.MAX_VALUE / 2;
        Color c = new Color();
        for (int gy = 0; gy < gridHeight; gy++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                Color.rgba8888ToColor(c, roadMask.getPixel(gx * CELL + CELL / 2, gy * CELL + CELL / 2));
                d[gy * gridWidth + gx] = c.r >= 0.85f && c.g >= 0.85f && c.b >= 0.85f ? 0 : far;
            }
        }
        // two sweeps (top-left to bottom-right, then back) spread the distances out
        for (int gy = 0; gy < gridHeight; gy++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                int i = gy * gridWidth + gx;
                int v = d[i];
                if (gx > 0) v = Math.min(v, d[i - 1] + STRAIGHT);
                if (gy > 0) {
                    v = Math.min(v, d[i - gridWidth] + STRAIGHT);
                    if (gx > 0) v = Math.min(v, d[i - gridWidth - 1] + DIAGONAL);
                    if (gx < gridWidth - 1) v = Math.min(v, d[i - gridWidth + 1] + DIAGONAL);
                }
                d[i] = v;
            }
        }
        for (int gy = gridHeight - 1; gy >= 0; gy--) {
            for (int gx = gridWidth - 1; gx >= 0; gx--) {
                int i = gy * gridWidth + gx;
                int v = d[i];
                if (gx < gridWidth - 1) v = Math.min(v, d[i + 1] + STRAIGHT);
                if (gy < gridHeight - 1) {
                    v = Math.min(v, d[i + gridWidth] + STRAIGHT);
                    if (gx < gridWidth - 1) v = Math.min(v, d[i + gridWidth + 1] + DIAGONAL);
                    if (gx > 0) v = Math.min(v, d[i + gridWidth - 1] + DIAGONAL);
                }
                d[i] = v;
            }
        }
        distance = d;
    }

    /**
     * @param x map x   @param y map y (up is +)
     * @return how far that spot is from the road, in map pixels
     */
    public static float distanceToRoad(float x, float y) {
        int gx = (int) (x / CELL);
        int gy = (int) ((mapHeight - y) / CELL);
        if (distance == null || gx < 0 || gy < 0 || gx >= gridWidth || gy >= gridHeight) {
            return Float.MAX_VALUE;
        }
        return distance[gy * gridWidth + gx] * (float) CELL / STRAIGHT;
    }

    /**
     * @return true if a car's center can be at (x, y): on the road or the grass strip beside it
     */
    public static boolean drivable(float x, float y) {
        return distanceToRoad(x, y) <= RUNOFF;
    }

    /**
     * paints the wall as a see-through picture the size of the map, drawn over the background
     * @return new texture
     */
    private static Texture makeTexture(Pixmap roadMask) {
        build(roadMask);
        Pixmap wall = new Pixmap(roadMask.getWidth(), mapHeight, Pixmap.Format.RGBA8888);
        wall.setBlending(Pixmap.Blending.None);
        int outline = Color.rgba8888(0.16f, 0.09f, 0.06f, 1f);
        int shadow = Color.rgba8888(0f, 0f, 0f, 0.28f);
        int red = Color.rgba8888(0.86f, 0.2f, 0.15f, 1f);
        int white = Color.rgba8888(0.95f, 0.93f, 0.88f, 1f);
        float end = WALL_START + WALL_THICKNESS;
        for (int py = 0; py < mapHeight; py++) {
            for (int px = 0; px < wall.getWidth(); px++) {
                int gx = px / CELL, gy = py / CELL;
                if (gx >= gridWidth || gy >= gridHeight) {
                    continue;
                }
                float dist = distance[gy * gridWidth + gx] * (float) CELL / STRAIGHT;
                if (dist < WALL_START - 2f || dist > end + 3f) {
                    continue;
                }
                int color;
                if (dist > end) {
                    color = shadow;            // soft shadow on the far side
                } else if (dist < WALL_START || dist > end - 2f) {
                    color = outline;           // dark edges, like the pixel-art outlines
                } else {
                    // red and white blocks, like the curbs
                    color = ((px / 8 + py / 8) % 2 == 0) ? red : white;
                }
                wall.drawPixel(px, py, color);
            }
        }
        Texture texture = new Texture(wall);
        wall.dispose();
        return texture;
    }

    private static Texture shared;

    /**
     * the wall picture, painted once and kept for the whole game (every screen shows the same map)
     */
    public static Texture texture() {
        if (shared == null) {
            Pixmap mask = new Pixmap(Gdx.files.internal("ui/road_mask.png"));
            shared = makeTexture(mask);
            mask.dispose();
        }
        return shared;
    }
}
