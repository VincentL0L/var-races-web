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
    public static final String CHICAGO = "chicago";
    public static final String LONDON = "london";
    public static final String PARIS = "paris";
    public static final String TAIPEI = "taipei";
    public static final String SUMMIT = "summit";
    public static final String RUSH_HOUR = "rushhour";
    public static final String ROOFTOP = "rooftop";
    public static final String MOON = "moon";

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
    /** size of the map picture in map pixels */
    public int width = 1920, height = 1080;
    /**
     * true for a sprint from A to B instead of laps of a circuit. The waypoints then run from
     * the start line to the finish line, plus one last point in the run-off past the finish
     * where the CPUs pull up.
     */
    public boolean pointToPoint = false;
    /**
     * the map picture and road mask are stored this many times smaller than the map and
     * drawn scaled up (pixel art made of 3 x 3 blocks loses nothing), so big maps stay small
     */
    public int scale = 1;
    /** tire grip and launch traction on this map (1 = normal; the moon is slippery) */
    public float grip = 1f, traction = 1f;

    private TrackMap sprint(int w, int h) {
        width = w;
        height = h;
        pointToPoint = true;
        scale = 3;
        return this;
    }

    private TrackMap surface(float gripScale, float tractionScale) {
        grip = gripScale;
        traction = tractionScale;
        return this;
    }

    /**
     * @return the finish line point (the start line is waypoint 0 on a sprint, the last waypoint on a circuit)
     */
    public Vector2 finishPoint() {
        return pointToPoint ? waypoints.get(waypoints.size() - 2) : waypoints.get(waypoints.size() - 1);
    }

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
            maps.add(city(CHICAGO, "Chicago", "Over the river, past the Bean and Willis Tower, chased by squad cars.",
                Waypoints.getChicagoWaypoints(), "ui/cop.png", "COP"));
            maps.add(city(LONDON, "London", "Westminster to Tower Bridge, against red double-deckers.",
                Waypoints.getLondonWaypoints(), "ui/bus.png", "BUS"));
            maps.add(city(PARIS, "Paris", "Along the Seine, round the Eiffel Tower and the Arc, against 2CVs.",
                Waypoints.getParisWaypoints(), "ui/2cv.png", "2CV"));
            maps.add(city(TAIPEI, "Taipei", "Past Taipei 101 and the night market, against a swarm of scooters.",
                Waypoints.getTaipeiWaypoints(), "ui/moto.png", "MOTO"));
            // sprints: one run from A to B
            maps.add(city(SUMMIT, "Summit Sprint", "Hairpins up a mountain pass. The road narrows near the top.",
                Waypoints.getSummitWaypoints(), null, "RALLY").sprint(1920, 7200));
            maps.add(city(RUSH_HOUR, "Rush Hour", "Rob the bank, lose the cops in the alley and the car park.",
                Waypoints.getRushHourWaypoints(), "ui/cop.png", "COP").sprint(3840, 3840));
            maps.add(city(ROOFTOP, "Rooftop Run", "Skyscraper roofs and narrow sky bridges. Don't look down.",
                Waypoints.getRooftopWaypoints(), null, "RIVAL").sprint(7200, 1800));
            maps.add(city(MOON, "Moon Base", "Low grip, big craters, and a rocket waiting at the end.",
                Waypoints.getMoonWaypoints(), "ui/rover.png", "ROVER").sprint(5400, 3240).surface(0.45f, 0.7f));
        }
        return maps;
    }

    /** a city map in assets/maps/(id)/, with walls beside the road */
    private static TrackMap city(String id, String name, String tagline, List<Vector2> waypoints,
            String cpuSprite, String cpuName) {
        return new TrackMap(id, name, tagline, "maps/" + id + "/", id + "_road_mask.png", waypoints, true,
            cpuSprite, cpuName);
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
