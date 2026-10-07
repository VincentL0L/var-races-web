package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;
import com.badlogic.gdx.math.Vector2;
public  class RacerInfo {
        public String name;
        public int lapCount;
        public int currentWaypointIndex;
        public float distanceToNextWaypoint;
        public Vector2 position;
        /**
         * 5-arg constructor for RacerInfo
         * @param name name for racer
         * @param lapCount lapCount for racer
         * @param waypointIndex wayPointIndex for racer
         * @param distanceToNextWaypoint distance to next waypoint for racer
         * @param position current position(x, y) for racer
         */
        public RacerInfo(String name, int lapCount, int waypointIndex, float distanceToNextWaypoint, Vector2 position) {
            this.name = name;
            this.lapCount = lapCount;
            this.currentWaypointIndex = waypointIndex;
            this.distanceToNextWaypoint = distanceToNextWaypoint;
            this.position = position;
        }
    }
