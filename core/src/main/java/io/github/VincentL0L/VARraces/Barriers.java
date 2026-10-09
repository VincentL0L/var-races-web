package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;

/**
 * Walls around the track on maps that have them (San Francisco: the buildings), so
 * nobody can skip part of the track.
 *
 * Next to the road there's a strip you can drive on (the sidewalk, slowly, see Player),
 * and past that a wall. The wall follows the road's shape, worked out from the road mask:
 * every spot gets its distance to the nearest road, and anything further than RUNOFF is
 * behind the wall.
 */
public class Barriers {
    /** how far from the road a car's center can go, in map pixels (keeps the car on the sidewalk) */
    public static final float RUNOFF = 12f;
    /** the distances are worked out on a grid of this many map pixels per cell */
    private static final int CELL = 2;
    // chamfer distance steps (straight and diagonal, about 3 : 4.24)
    private static final int STRAIGHT = 3;
    private static final int DIAGONAL = 4;

    /** which road mask the distances were worked out for */
    private static Pixmap builtFor;
    private static int gridWidth;
    private static int gridHeight;
    private static int mapHeight;
    private static int cellPixels = CELL;
    /** a cell's size in map pixels */
    private static int cellSize = CELL;
    /** distance from each cell to the road, in chamfer steps (3 per cell) */
    private static int[] distance;

    /**
     * works out the distances once from the road mask
     * @param roadMask road_mask.png (white = road)
     */
    public static void build(Pixmap roadMask) {
        build(roadMask, 1);
    }

    /**
     * @param maskScale map pixels per mask pixel (big maps store a smaller mask)
     */
    public static void build(Pixmap roadMask, int maskScale) {
        if (distance != null && builtFor == roadMask) {
            return;
        }
        builtFor = roadMask;
        // a cell is CELL mask pixels on a full-size mask, one mask pixel on a small one
        cellPixels = maskScale > 1 ? 1 : CELL;
        cellSize = cellPixels * maskScale;
        mapHeight = roadMask.getHeight() * maskScale;
        gridWidth = roadMask.getWidth() / cellPixels;
        gridHeight = roadMask.getHeight() / cellPixels;
        int[] d = new int[gridWidth * gridHeight];
        int far = Integer.MAX_VALUE / 2;
        Color c = new Color();
        for (int gy = 0; gy < gridHeight; gy++) {
            for (int gx = 0; gx < gridWidth; gx++) {
                Color.rgba8888ToColor(c, roadMask.getPixel(gx * cellPixels + cellPixels / 2, gy * cellPixels + cellPixels / 2));
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
        int gx = (int) (x / cellSize);
        int gy = (int) ((mapHeight - y) / cellSize);
        if (distance == null || gx < 0 || gy < 0 || gx >= gridWidth || gy >= gridHeight) {
            return Float.MAX_VALUE;
        }
        return distance[gy * gridWidth + gx] * (float) cellSize / STRAIGHT;
    }

    /**
     * @return true if a car's center can be at (x, y): on the road or the grass strip beside it
     */
    public static boolean drivable(float x, float y) {
        return distanceToRoad(x, y) <= RUNOFF;
    }
}
