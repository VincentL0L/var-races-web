package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.List;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

public class Opponent {

    private String name;

    private Vector2 position;
    private Vector2 previousPosition;
    private Vector2 velocity;
    private float rotation; 
    //private float speed;

    private int lapCount = 0;
    //private float totalTime = 0f;
    private static final int LAPS_TO_WIN = 3;
    private boolean isFinished = false;
    private boolean shouldStopAtNextWaypoint = false;
    private Vector2 finishPosition = null;

    private List<Vector2> waypoints;
    private int currentWaypointIndex = 0;

    private float speedMultiplier;


    private float distanceToNextWaypoint;
    /**
     * 3-Arg Constructor for Opponent
     * @param name passed in name, sets field
     * @param waypoints passed in waypoints of map, sets field
     * @param startPosition passed in startPosition of CPUS, sets field
     */
    public Opponent(String name, List<Vector2> waypoints, Vector2 startPosition) {
        this.name = name;
        this.waypoints = waypoints;
        this.position = new Vector2(startPosition);
        this.previousPosition = new Vector2(position);
        this.velocity = new Vector2();
        this.rotation = 0f;
        this.speedMultiplier = 0.9f + MathUtils.random() * 0.2f;
        this.distanceToNextWaypoint = 0f;
    }
    /**
     * Called every frame, updates CPU position and rotation if needed
     * Updates currentwaypoint index, lap count, distance to nxt waypoint
     * @param delta amount of time between frames
     * @param raceInProgress boolean status if race is finished or not
     */
    public void update(float delta, boolean raceInProgress) {
        if (!raceInProgress || isFinished) {
            velocity.setZero();
            return;
        }

        if (waypoints == null || waypoints.isEmpty()) return;

        Vector2 currentWaypoint = waypoints.get(currentWaypointIndex);
        
        distanceToNextWaypoint = position.dst(currentWaypoint);

        Vector2 toWaypoint = new Vector2(currentWaypoint).sub(position);
        float distance = toWaypoint.len();
        
        if (distance < 50f) {
            currentWaypointIndex++;
            if (currentWaypointIndex == waypoints.size()) {
                lapCount++;
                if (lapCount >= LAPS_TO_WIN) {
                    shouldStopAtNextWaypoint = true;
                }
                currentWaypointIndex = 0;
            }
            

            if (shouldStopAtNextWaypoint && currentWaypointIndex == 1) {
                isFinished = true;
                velocity.setZero();
                return;
            }
        }

        toWaypoint.nor();
        
        float speedMultiplierToUse = shouldStopAtNextWaypoint ? 
            speedMultiplier * Math.max(0.3f, distance / 200f) : 
            speedMultiplier;
            
        velocity.set(toWaypoint).scl(200 * speedMultiplierToUse);
        previousPosition.set(position);
        position.add(velocity.x * delta, velocity.y * delta);
        
        float targetRotation = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.x));
        rotation = targetRotation;
    }
    /**
     * @return x, y position vector for opponent
     */
    public Vector2 getPosition() {
        return position;
    }
    /**
     * @return x, y previous position for opponent
     */
    public Vector2 getPreviousPosition() {
        return previousPosition;
    }
    /**
     * @return rotation of the opponent
     */
    public float getRotation() {
        return rotation;
    }
    /**
     * @return current waypoint index
     */
    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }
    /**
     * @return current lap count
     */
    public int getLapCount() {
        return lapCount;
    }
    /**
     * @return name of opponent
     */
    public String getName() {
        return name;
    }
    /**
     * @return float distance to next waypoint
     */
    public float getDistanceToNextWaypoint() {
        return distanceToNextWaypoint;
    }
    /**
     * @return boolean status if race is finished
     */
    public boolean isFinished() {
        return isFinished;
    }
}
