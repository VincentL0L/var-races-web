package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;

/**
 * One race map: its picture, road mask, racing line and a few rules.
 *
 * Every map is 1920 x 1080 and starts the same way: the last stretch comes in along the
 * bottom left, turns up at x = 200 and crosses the checkered line at y = 300, so the
 * starting grid (Waypoints.getGridSlot) and the finish line work the same on all of them.
 */
public class TrackMap {
    public static final String CLASSIC = "classic";
    public static final String SAN_FRANCISCO = "sf";
    public static final String TOKYO = "tokyo";
    public static final String NEW_YORK = "nyc";

    /** short id sent between the game and the server */
    public final String id;
    public final String name;
    /** one line under the name on the map picker */
    public final String tagline;
    /** the map picture, the road mask (white = road) and a small preview, in assets */
    public final String background;
    public final String roadMask;
    public final String preview;
    /** the road mask's file name inside the server jar */
    public final String serverMask;
    /** the racing line; the last point is on the start/finish line */
    public final List<Vector2> waypoints;
    /** true: walls a little way off the road so nobody can cut across (see Barriers) */
    public final boolean barriers;
    /** sprite for the CPU cars, or null for the usual three colors (car4-6) */
    public final String cpuSprite;
    /** what the CPUs are called in the standings: "CPU" or "WAYMO" */
    public final String cpuName;

    private TrackMap(String id, String name, String tagline, String folder, String serverMask,
            List<Vector2> waypoints, boolean barriers, String cpuSprite, String cpuName) {
        this.id = id;
        this.name = name;
        this.tagline = tagline;
        this.background = folder + "background.png";
        this.roadMask = folder + "road_mask.png";
        this.preview = folder + "preview.png";
        this.serverMask = serverMask;
        this.waypoints = waypoints;
        this.barriers = barriers;
        this.cpuSprite = cpuSprite;
        this.cpuName = cpuName;
    }

    private static List<TrackMap> maps;

    /**
     * @return every map, in the order the picker shows them
     */
    public static List<TrackMap> all() {
        if (maps == null) {
            maps = new ArrayList<>();
            maps.add(new TrackMap(CLASSIC, "Classic", "The original circuit. Cut across the grass if you dare.",
                "ui/", "road_mask.png", Waypoints.getWaypoints(), false, null, "CPU"));
            maps.add(new TrackMap(SAN_FRANCISCO, "San Francisco",
                "Over the Golden Gate and down the Embarcadero, against Waymos.",
                "maps/sf/", "sf_road_mask.png", Waypoints.getSanFranciscoWaypoints(), true,
                "ui/waymo.png", "WAYMO"));
            maps.add(new TrackMap(TOKYO, "Tokyo",
                "Through the Shibuya scramble and around Tokyo Tower, against kei cars.",
                "maps/tokyo/", "tokyo_road_mask.png", Waypoints.getTokyoWaypoints(), true,
                "ui/kei.png", "KEI"));
            maps.add(new TrackMap(NEW_YORK, "New York",
                "Past Central Park, over the Brooklyn Bridge and through Times Square, against yellow cabs.",
                "maps/nyc/", "nyc_road_mask.png", Waypoints.getNewYorkWaypoints(), true,
                "ui/cab.png", "CAB"));
        }
        return maps;
    }

    /**
     * @param id a map id (anything unknown gives the classic map)
     * @return that map
     */
    public static TrackMap get(String id) {
        for (TrackMap map : all()) {
            if (map.id.equals(id)) {
                return map;
            }
        }
        return all().get(0);
    }

    /**
     * @param racer racer id, like "Player 2" or "CPU1"
     * @return the name to show: CPUs get the map's name for them ("WAYMO 1")
     */
    public String displayName(String racer) {
        if (racer.startsWith("CPU") && racer.length() == 4) {
            return cpuName + " " + racer.charAt(3);
        }
        return racer;
    }
}
