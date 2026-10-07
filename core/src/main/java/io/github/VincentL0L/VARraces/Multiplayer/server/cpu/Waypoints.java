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
