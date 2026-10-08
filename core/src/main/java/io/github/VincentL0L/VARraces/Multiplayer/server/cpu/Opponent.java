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

    // the point the CPU is aiming at: the waypoint moved sideways onto its chosen line,
    // plus an extra sideways shift while it's passing another car
    private final Vector2 target = new Vector2();
    private final Vector2 dodgePoint = new Vector2();
    private final Vector2 basePoint = new Vector2();
    private final Vector2 leftOfTravel = new Vector2();
    private float lineOffset;
    private float passShift = 0f;
    private float passSide = 0f;
    private float passHold = 0f;
    private float arriveRadius;

    // personality, picked once per CPU
    private float topSpeed;
    private float acceleration;
    private float braking;
    private float turnRate;              // degrees per second
    private float cornerRadius;    // how tight a corner it aims to drive at speed (smaller = braver)
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
     * Drives this CPU on its own (no other cars), in small fixed steps.
     * Races use CpuTraffic instead, which moves all CPUs together and handles collisions.
     * @param delta amount of time between updates
     * @param raceInProgress boolean status if race is finished or not
     */
    public void update(float delta, boolean raceInProgress) {
        if (!raceInProgress || isFinished || waypoints == null || waypoints.isEmpty()) {
            speed = 0f;
            return;
        }
        beginTick();
        stepTimer += Math.min(delta, 0.25f);
        while (stepTimer >= STEP) {
            stepTimer -= STEP;
            drive(STEP, null);
        }
        endTick();
    }

    /** called by CpuTraffic before a batch of steps */
    void beginTick() {
        previousPosition.set(position);
    }

    /** called by CpuTraffic after a batch of steps */
    void endTick() {
        distanceToNextWaypoint = position.dst(waypoints.get(currentWaypointIndex));
    }

    /**
     * one small step of driving
     * @param dt step length in seconds
     * @param traffic the other cars, or null when driving alone
     */
    void drive(float dt, CpuTraffic traffic) {
        if (isFinished) {
            speed = 0f;
            return;
        }
        if (reactionDelay > 0) {
            reactionDelay -= dt;
            return;
        }

        // spun out by an item: skid to a halt while spinning round twice
        if (spinTimer > 0f) {
            spinTimer -= dt;
            spinAngle += 720f / SPIN_TIME * dt;
            speed = Math.max(0f, speed - 700f * dt);
            float nx = position.x + MathUtils.cosDeg(heading) * speed * dt;
            float ny = position.y + MathUtils.sinDeg(heading) * speed * dt;
            // skid along, but stop at the edge of the road rather than sliding off it
            if (traffic == null || traffic.carOnRoad(nx, ny)) {
                position.set(nx, ny);
            } else {
                speed = 0f;
            }
            if (spinTimer <= 0f) {
                spinAngle = 0f;
            }
            return;
        }
        slowTimer -= dt;
        nitroTimer -= dt;

        // reached the current waypoint: move on and pick a new line for the next one
        if (position.dst(target) < arriveRadius || position.dst(waypoints.get(currentWaypointIndex)) < arriveRadius
                || passedCorner()) {
            currentWaypointIndex++;
            if (currentWaypointIndex == waypoints.size()) {
                currentWaypointIndex = 0;
                lapCount++;
                if (lapCount >= laps && !endless) {
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

        // traffic: keep a gap to a car in front and pull out to the side to pass it
        float followSpeed = Float.MAX_VALUE;
        boolean dodging = false;
        if (traffic != null && traffic.findCarAhead(this, 30f + speed * 0.3f)) {
            // keep a gap, but never stop completely: a crawling car can still steer around
            followSpeed = Math.max(45f, traffic.aheadSpeed + (traffic.aheadAlong - CarBody.LENGTH - 8f) * 4f);
            if (passHold <= 0f) {
                // car on our right: pass on the left, and the other way round
                passSide = traffic.aheadLateral > 0.5f ? 1f : traffic.aheadLateral < -0.5f ? -1f : (lineBias >= 0 ? -1f : 1f);
            }
            passHold = 1.2f;   // keep the passing line until we're well past it
            // right behind it: steer straight for a spot beside it (whichever side has road)
            if (traffic.aheadAlong < 70f) {
                dodging = setDodgePoint(traffic, passSide) || setDodgePoint(traffic, -passSide);
                if (!dodging) {
                    followSpeed = Math.min(followSpeed, traffic.aheadSpeed);
                }
            }
        }
        passHold -= dt;
        // no passing moves in the middle of a sharp corner: wait for the exit
        boolean inCorner = cornerSharpness() > 0.5f && position.dst(basePoint) < 140f;
        float passTarget = passHold > 0f && !inCorner ? passSide * 30f : 0f;
        passShift += MathUtils.clamp(passTarget - passShift, -60f * dt, 60f * dt);
        updateTarget();

        // steer toward the target, limited by how fast this car can turn
        Vector2 aim = dodging ? dodgePoint : target;
        float wanted = MathUtils.atan2(aim.y - position.y, aim.x - position.x) * MathUtils.radiansToDegrees;
        float diff = angleDiff(wanted, heading);
        updateWobble(dt);
        float maxTurn = turnRate * dt;
        // edge of the road coming up: steer back harder and ease off
        boolean nearEdge = traffic != null && !traffic.carOnRoad(
            position.x + MathUtils.cosDeg(heading) * speed * 0.12f,
            position.y + MathUtils.sinDeg(heading) * speed * 0.12f);
        if (nearEdge) {
            maxTurn *= 1.8f;
        }
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
        desired = Math.min(desired, followSpeed);
        if (nitroTimer > 0f && desired >= topSpeed * throttleNoise - 1f) {
            desired = topSpeed * 1.3f;    // nitro: faster on the straights (still brakes for corners)
        }
        if (slowTimer > 0f) {
            desired *= 0.55f;                               // hit by a Static Pulse
        }
        if (nearEdge) {
            desired = Math.min(desired, speed * 0.85f);
        }
        // a car pointed the wrong way slows down to turn around
        desired *= MathUtils.clamp(1f - Math.abs(diff) / 120f, 0.35f, 1f);
        if (shouldStopAtNextWaypoint && currentWaypointIndex == 1) {
            desired *= Math.max(0.3f, distance / 200f);
        }

        if (speed < desired) {
            float pull = acceleration * Math.min(1f, 1.6f * (1f - speed / (topSpeed * 1.05f)));
            if (nitroTimer > 0f) {
                pull = acceleration * 3f;
            }
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
        lineOffset = MathUtils.clamp(lineBias * 14f + MathUtils.random(-16f, 16f), -MAX_LINE_OFFSET, MAX_LINE_OFFSET);
        basePoint.set(point);
        leftOfTravel.set(-dir.y, dir.x);
        updateTarget();
        arriveRadius = MathUtils.random(40f, 70f);
        throttleNoise = MathUtils.random(0.94f, 1.0f);
    }

    /**
     * picks a point beside and a little past the car in front
     * @param side 1 = pass on its left, -1 = on its right
     * @return false if that spot isn't on the road
     */
    private boolean setDodgePoint(CpuTraffic traffic, float side) {
        float fx = MathUtils.cosDeg(heading), fy = MathUtils.sinDeg(heading);
        dodgePoint.set(traffic.aheadX - fy * side * 24f + fx * 25f, traffic.aheadY + fx * side * 24f + fy * 25f);
        // the whole way there, and a bit past it, has to be road (no cutting across a corner)
        float beyondX = dodgePoint.x + fx * 20f, beyondY = dodgePoint.y + fy * 20f;
        for (int i = 1; i <= 6; i++) {
            float t = i / 6f;
            if (!traffic.carOnRoad(MathUtils.lerp(position.x, dodgePoint.x, t), MathUtils.lerp(position.y, dodgePoint.y, t))
                    || !traffic.carOnRoad(MathUtils.lerp(dodgePoint.x, beyondX, t), MathUtils.lerp(dodgePoint.y, beyondY, t))) {
                return false;
            }
        }
        passSide = side;
        return true;
    }

    /**
     * aim point = waypoint + sideways offset (own line plus passing shift), kept on the road
     */
    private void updateTarget() {
        float offset = MathUtils.clamp(lineOffset + passShift, -MAX_LINE_OFFSET, MAX_LINE_OFFSET);
        target.set(basePoint.x + leftOfTravel.x * offset, basePoint.y + leftOfTravel.y * offset);
    }

    /**
     * moves the car (used to push cars apart after a bump)
     */
    void push(float dx, float dy) {
        position.add(dx, dy);
    }

    /**
     * @param newSpeed speed after a bump
     */
    void setSpeed(float newSpeed) {
        speed = Math.max(0f, newSpeed);
    }

    /**
     * @return current speed in pixels per second
     */
    public float getSpeed() {
        return speed;
    }

    /**
     * True once the car is already past the current waypoint, heading down the next
     * stretch (e.g. it cut inside the corner while passing someone), so it never turns back.
     */
    private boolean passedCorner() {
        Vector2 point = waypoints.get(currentWaypointIndex);
        Vector2 before = waypoints.get((currentWaypointIndex - 1 + waypoints.size()) % waypoints.size());
        Vector2 after = waypoints.get((currentWaypointIndex + 1) % waypoints.size());
        Vector2 in = new Vector2(point).sub(before).nor();
        Vector2 out = new Vector2(after).sub(point).nor();
        // the corner's bisector: the diagonal line through the waypoint halfway between the
        // direction we came from and the direction we leave in. Crossing it = past the corner.
        float nx = in.x + out.x, ny = in.y + out.y;
        float rx = position.x - point.x, ry = position.y - point.y;
        return rx * nx + ry * ny > 0f && rx * rx + ry * ry < 160f * 160f;
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
    private static final float SPIN_TIME = 1.0f;
    private float spinTimer = 0f, spinAngle = 0f, slowTimer = 0f, nitroTimer = 0f;

    /** hit by a Bottle Rocket or an Oil Slick: spin out and lose most of the speed */
    public void spinOut() {
        spinTimer = SPIN_TIME;
        speed *= 0.6f;
    }

    /**
     * hit by a Static Pulse
     * @param seconds how long to drive slower
     */
    public void slowDown(float seconds) {
        slowTimer = seconds;
    }

    /** used a Nitro: a burst of speed */
    public void nitro() {
        nitroTimer = 1.3f;
        speed = Math.min(topSpeed * 1.35f, speed + 120f);
    }

    /**
     * @return the direction to draw the car (spinning while spun out), degrees
     */
    public float getDisplayRotation() {
        return heading + spinAngle;
    }

    private int laps = 1;

    /**
     * @param count laps in this race
     */
    public void setLaps(int count) {
        laps = Math.max(1, count);
    }

    /**
     * Makes this CPU a weaker or stronger driver. Call once, right after creating it.
     * @param difficulty 0 easy, 1 normal, 2 hard
     */
    public void setDifficulty(int difficulty) {
        if (difficulty == 0) {
            // slower, softer on the gas, brakes early and takes corners wide
            topSpeed *= 0.84f;
            acceleration *= 0.8f;
            turnRate *= 0.9f;
            cornerRadius *= 1.2f;
            reactionDelay += 0.3f;
        } else if (difficulty == 2) {
            // a little faster than you, quick off the line and brave in the corners
            topSpeed *= 1.08f;
            acceleration *= 1.3f;
            braking *= 1.1f;
            turnRate *= 1.12f;
            cornerRadius *= 0.88f;
            reactionDelay *= 0.4f;
        }
    }

    /** true: keeps lapping forever (the title screen's demo race) */
    private boolean endless = false;

    /**
     * @param value true to never finish, just keep lapping
     */
    public void setEndless(boolean value) {
        endless = value;
    }

    /**
     * @return boolean status if race is finished
     */
    public boolean isFinished() {
        return isFinished;
    }
}
