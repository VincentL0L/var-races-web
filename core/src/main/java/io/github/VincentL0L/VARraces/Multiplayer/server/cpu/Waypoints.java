package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;

/**
 * waypoints along the track that CPUs follow and players have to
 * go through in order
 */
public class Waypoints {
    /**
     * starting spots for the 3 CPUs, staggered behind the player's spot (200, 300)
     * on the start straight (all checked to be on the road)
     * @return grid positions
     */
    public static List<Vector2> getCpuGrid() {
        List<Vector2> grid = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            grid.add(getGridSlot(i));
        }
        return grid;
    }

    /** grid slots players take, in join order: middle of row 2 first, then its sides, then row 3 */
    private static final int[] PLAYER_SLOTS = {4, 3, 5, 7, 6, 8, 10, 9, 11};

    /**
     * @param index 0 for the first player, 1 for the second...
     * @return where that player's car starts (image corner), behind the CPUs
     */
    public static Vector2 getPlayerStart(int index) {
        return getGridSlot(PLAYER_SLOTS[Math.max(0, index) % PLAYER_SLOTS.length]);
    }

    /**
     * The starting grid behind the checkered line (y 300): 3 columns across the road,
     * 4 rows back, with the middle column set back a little like a real staggered grid.
     * The CPUs take the front row (slots 0-2).
     * @param slot 0-11, front row first, left to right
     * @return bottom left corner of the car's image in that slot
     */
    public static Vector2 getGridSlot(int slot) {
        int row = slot / 3;
        int column = slot % 3;
        float x = 165f + column * 36f;
        float y = 276f - row * 30f - (column == 1 ? 12f : 0f);
        return new Vector2(x, y);
    }

    /**
     * only called by gameServer to update waypoint list.
     * which is why no constructor and inefficient as it is called
     * one time in total
     * @return waypoints
     */
    public static List<Vector2> getWaypoints() {
        List<Vector2> waypoints = new ArrayList<>();

        waypoints.add(new Vector2(190, 470));
        waypoints.add(new Vector2(530, 470));
        waypoints.add(new Vector2(530, 700));
        waypoints.add(new Vector2(150, 700));
        waypoints.add(new Vector2(150, 935));
        waypoints.add(new Vector2(990, 935));
        waypoints.add(new Vector2(990, 660));
        waypoints.add(new Vector2(800, 660));
        waypoints.add(new Vector2(800, 440));
        waypoints.add(new Vector2(1240, 440));
        waypoints.add(new Vector2(1240, 700));
        waypoints.add(new Vector2(1460, 700));
        waypoints.add(new Vector2(1460, 935));
        waypoints.add(new Vector2(1780, 935));
        waypoints.add(new Vector2(1780, 440));
        waypoints.add(new Vector2(1450, 440));
        waypoints.add(new Vector2(1450, 230));
        waypoints.add(new Vector2(200, 230));
        waypoints.add(new Vector2(200, 300));

        return waypoints;
    }
}
