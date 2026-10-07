package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.List;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/**
 * A CPU driver. Instead of jumping between waypoints at a fixed speed, each CPU
 * drives like a car: it has a heading and a speed, can only turn so fast, brakes
 * before corners and speeds up on straights.
 *
 * Every CPU gets its own random personality (top speed, cornering, preferred line,
 * reaction time) and picks a slightly different line through each corner every lap,
 * so races play out differently each time.
 */
public class Opponent {
    private static final int LAPS_TO_WIN = 3;
    private static final float STEP = 1f / 60f;
    /** how far off the middle of the road a CPU may drive (the road is about 125 wide) */
    private static final float MAX_LINE_OFFSET = 28f;

    private String name;

    private Vector2 position;
    private Vector2 previousPosition;
    private float heading = 90f;         // degrees, 90 = up the screen
    private float speed = 0f;            // pixels per second

    private int lapCount = 0;
    private boolean isFinished = false;
    private boolean shouldStopAtNextWaypoint = false;

    private List<Vector2> waypoints;
    private int currentWaypointIndex = 0;
    private float distanceToNextWaypoint;

    // the point the CPU is aiming at: the waypoint moved sideways onto its chosen line
    private final Vector2 target = new Vector2();
    private float arriveRadius;

    // personality, picked once per CPU
    private final float topSpeed;
    private final float acceleration;
    private final float braking;
    private final float turnRate;        // degrees per second
    private final float cornerRadius;    // how tight a corner it aims to drive at speed (smaller = braver)
    private final float lineBias;        // -1 prefers the left side of the road, 1 the right
    private float reactionDelay;         // seconds before it reacts to GO

    // small random wandering so CPUs don't drive perfectly straight
    private float wobble = 0f;
    private float wobbleTarget = 0f;
    private float wobbleTimer = 0f;
    private float throttleNoise = 1f;

    private float stepTimer = 0f;

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

        topSpeed = MathUtils.random(400f, 455f);
        acceleration = MathUtils.random(85f, 110f);
        braking = MathUtils.random(340f, 420f);
        turnRate = MathUtils.random(150f, 200f);
        cornerRadius = MathUtils.random(48f, 60f);
        lineBias = MathUtils.random(-1f, 1f);
        reactionDelay = MathUtils.random(0.05f, 0.45f);

        pickTarget();
    }

    /**
     * Called every server tick, updates CPU position and rotation.
     * Runs the driving in small fixed steps so it behaves the same at any tick rate.
     * @param delta amount of time between updates
     * @param raceInProgress boolean status if race is finished or not
     */
    public void update(float delta, boolean raceInProgress) {
        if (!raceInProgress || isFinished || waypoints == null || waypoints.isEmpty()) {
            speed = 0f;
            return;
        }
        previousPosition.set(position);
        stepTimer += Math.min(delta, 0.25f);
        while (stepTimer >= STEP) {
            stepTimer -= STEP;
            step(STEP);
        }
        distanceToNextWaypoint = position.dst(waypoints.get(currentWaypointIndex));
    }

    /**
     * one small step of driving
     * @param dt step length in seconds
     */
    private void step(float dt) {
        if (reactionDelay > 0) {
            reactionDelay -= dt;
            return;
        }

        // reached the current waypoint: move on and pick a new line for the next one
        if (position.dst(target) < arriveRadius || position.dst(waypoints.get(currentWaypointIndex)) < arriveRadius) {
            currentWaypointIndex++;
            if (currentWaypointIndex == waypoints.size()) {
                currentWaypointIndex = 0;
                lapCount++;
                if (lapCount >= LAPS_TO_WIN) {
                    shouldStopAtNextWaypoint = true;
                }
            }
            if (shouldStopAtNextWaypoint && currentWaypointIndex == 1) {
                isFinished = true;
                speed = 0f;
                return;
            }
            pickTarget();
        }

        // steer toward the target, limited by how fast this car can turn
        float wanted = MathUtils.atan2(target.y - position.y, target.x - position.x) * MathUtils.radiansToDegrees;
        float diff = angleDiff(wanted, heading);
        updateWobble(dt);
        float maxTurn = turnRate * dt;
        heading += MathUtils.clamp(diff, -maxTurn, maxTurn) + wobble * dt;

        // pick a speed: full speed on straights, slow down before sharp corners
        float desired = topSpeed * throttleNoise;
        float distance = position.dst(target);
        float corner = cornerSharpness();
        if (corner > 0.05f) {
            // fastest speed that still turns tight enough: speed = turn rate x turning circle radius
            float tightestSpeed = turnRate * MathUtils.degreesToRadians * cornerRadius;
            float cornerSpeed = MathUtils.lerp(topSpeed, tightestSpeed, corner);
            // brake early enough to reach the corner speed by the time we get there
            float brakeDistance = Math.max(0f, (speed * speed - cornerSpeed * cornerSpeed) / (2f * braking));
            if (distance < brakeDistance + 40f) {
                desired = Math.min(desired, cornerSpeed);
            }
        }
        // a car pointed the wrong way slows down to turn around
        desired *= MathUtils.clamp(1f - Math.abs(diff) / 120f, 0.35f, 1f);
        if (shouldStopAtNextWaypoint && currentWaypointIndex == 1) {
            desired *= Math.max(0.3f, distance / 200f);
        }

        if (speed < desired) {
            float pull = acceleration * Math.min(1f, 1.6f * (1f - speed / (topSpeed * 1.05f)));
            speed = Math.min(desired, speed + Math.max(pull, 8f) * dt);
        } else {
            speed = Math.max(desired, speed - braking * dt);
        }

        position.x += MathUtils.cosDeg(heading) * speed * dt;
        position.y += MathUtils.sinDeg(heading) * speed * dt;
    }

    /**
     * chooses where to aim for the current waypoint: off to one side of the middle of the road
     * by a random amount (leaning toward this CPU's preferred side), and how early to turn in
     */
    private void pickTarget() {
        Vector2 point = waypoints.get(currentWaypointIndex);
        Vector2 before = waypoints.get((currentWaypointIndex - 1 + waypoints.size()) % waypoints.size());
        Vector2 dir = new Vector2(point).sub(before).nor();
        float offset = MathUtils.clamp(lineBias * 14f + MathUtils.random(-16f, 16f), -MAX_LINE_OFFSET, MAX_LINE_OFFSET);
        // perpendicular to the direction of travel
        target.set(point.x - dir.y * offset, point.y + dir.x * offset);
        arriveRadius = MathUtils.random(40f, 70f);
        throttleNoise = MathUtils.random(0.94f, 1.0f);
    }

    /**
     * @return 0 for a straight, up to 1 for a hairpin, at the current waypoint
     */
    private float cornerSharpness() {
        Vector2 point = waypoints.get(currentWaypointIndex);
        Vector2 before = waypoints.get((currentWaypointIndex - 1 + waypoints.size()) % waypoints.size());
        Vector2 after = waypoints.get((currentWaypointIndex + 1) % waypoints.size());
        Vector2 in = new Vector2(point).sub(before).nor();
        Vector2 out = new Vector2(after).sub(point).nor();
        return MathUtils.clamp(1f - in.dot(out), 0f, 1f);
    }

    /**
     * slowly drifting random steering, like a driver making small corrections
     */
    private void updateWobble(float dt) {
        wobbleTimer -= dt;
        if (wobbleTimer <= 0) {
            wobbleTimer = MathUtils.random(0.4f, 1.2f);
            wobbleTarget = MathUtils.random(-12f, 12f);
        }
        wobble += (wobbleTarget - wobble) * Math.min(1f, 3f * dt);
    }

    /**
     * @return a - b wrapped to -180..180 degrees
     */
    private static float angleDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
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
     * @return rotation of the opponent in degrees (0 = right, 90 = up)
     */
    public float getRotation() {
        return heading;
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
