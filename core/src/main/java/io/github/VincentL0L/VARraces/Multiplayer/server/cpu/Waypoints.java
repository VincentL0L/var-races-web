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
    /**
     * San Francisco: up the Marina, across the Golden Gate, down the Embarcadero, along
     * Market Street, up California Street and back past the Painted Ladies.
     * Same shape as tools/MakeSanFrancisco.java paints; the last point is the start line.
     * @return waypoints
     */
    public static List<Vector2> getSanFranciscoWaypoints() {
        List<Vector2> waypoints = new ArrayList<>();
        waypoints.add(new Vector2(200, 880));
        waypoints.add(new Vector2(1740, 880));
        waypoints.add(new Vector2(1740, 180));
        waypoints.add(new Vector2(1100, 180));
        waypoints.add(new Vector2(1100, 520));
        waypoints.add(new Vector2(620, 520));
        waypoints.add(new Vector2(620, 230));
        waypoints.add(new Vector2(200, 230));
        waypoints.add(new Vector2(200, 300));
        return waypoints;
    }

    /**
     * Tokyo: over the Sumida three times, through the Shibuya scramble, around Shiba Park
     * and Tokyo Tower. Same shape as tools/MakeCities.java paints; the last point is the start line.
     * @return waypoints
     */
    public static List<Vector2> getTokyoWaypoints() {
        return points(200, 860, 700, 860, 700, 620, 1200, 620, 1200, 900, 1760, 900, 1760, 200,
            1000, 200, 1000, 420, 520, 420, 520, 230, 200, 230, 200, 300);
    }

    /**
     * New York: past Central Park, over the Brooklyn Bridge and back over the Manhattan
     * Bridge, through Times Square. Same shape as tools/MakeCities.java paints.
     * @return waypoints
     */
    public static List<Vector2> getNewYorkWaypoints() {
        return points(200, 700, 560, 700, 560, 900, 1760, 900, 1760, 200, 1240, 200, 1240, 520,
            860, 520, 860, 230, 200, 230, 200, 300);
    }

    /** Chicago: over the river twice, past Willis Tower, Millennium Park and the lakefront */
    public static List<Vector2> getChicagoWaypoints() {
        return points(200, 900, 900, 900, 900, 700, 1500, 700, 1500, 160, 1100, 160, 1100, 430,
            640, 430, 640, 230, 200, 230, 200, 300);
    }

    /** London: over Westminster Bridge, past the London Eye and over Tower Bridge */
    public static List<Vector2> getLondonWaypoints() {
        return points(200, 860, 1000, 860, 1000, 960, 1760, 960, 1760, 520, 1300, 520, 1300, 180,
            760, 180, 760, 420, 480, 420, 480, 230, 200, 230, 200, 300);
    }

    /** Paris: along the Seine, around the Eiffel Tower and the Arc de Triomphe */
    public static List<Vector2> getParisWaypoints() {
        return points(200, 700, 700, 700, 700, 940, 1500, 940, 1500, 700, 1760, 700, 1760, 180,
            1100, 180, 1100, 420, 560, 420, 560, 230, 200, 230, 200, 300);
    }

    /** Taipei: over the Keelung river twice, past Taipei 101 and Longshan Temple */
    public static List<Vector2> getTaipeiWaypoints() {
        return points(200, 900, 1300, 900, 1300, 640, 1760, 640, 1760, 180, 1240, 180, 1240, 420,
            800, 420, 800, 230, 200, 230, 200, 300);
    }

    private static List<Vector2> points(int... xy) {
        List<Vector2> waypoints = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            waypoints.add(new Vector2(xy[i], xy[i + 1]));
        }
        return waypoints;
    }

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
