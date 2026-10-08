package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/**
 * Moves all the CPU cars of one race together in small steps (at most 1/60 s), and after
 * every step pushes apart any cars that touch. CPUs are never left overlapping each
 * other, and are never pushed off the road.
 *
 * Human players are known by their latest reported position: CPUs avoid and get pushed
 * away from them (each player's own game pushes the player away from the CPUs).
 */
public class CpuTraffic {
    private static final float MAX_STEP = 1f / 60f;
    private static final int SEPARATION_PASSES = 8;
    /** pushes leave this tiny gap so cars end up just apart, not exactly touching */
    private static final float CLEARANCE = 0.05f;

    private final List<Opponent> cpus;
    private final Track track;
    private final Map<String, PlayerCar> players = new LinkedHashMap<>();
    private final Vector2 push = new Vector2();

    // filled in by findCarAhead
    float aheadAlong;
    float aheadLateral;
    float aheadSpeed;
    float aheadX;
    float aheadY;

    /**
     * @param cpus the race's CPU cars
     * @param track road check used so pushes never shove a car off the road (null = no check)
     */
    public CpuTraffic(List<Opponent> cpus, Track track) {
        this.cpus = cpus;
        this.track = track;
    }

    /**
     * updates where a human player is
     * @param id player id
     * @param x image x   @param y image y
     * @param heading direction in degrees (90 = up)
     */
    public void setPlayer(String id, float x, float y, float heading) {
        setPlayer(id, x, y, heading, 1f);
    }

    /**
     * @param mass the player's car weight (1 = standard), so a heavy car shoves CPUs harder
     */
    public void setPlayer(String id, float x, float y, float heading, float mass) {
        PlayerCar p = players.get(id);
        if (p == null) {
            p = new PlayerCar();
            p.x = x;
            p.y = y;
            players.put(id, p);
        }
        p.newX = x;
        p.newY = y;
        p.heading = heading;
        p.mass = mass;
    }

    /**
     * @param id player who left the race
     */
    public void removePlayer(String id) {
        players.remove(id);
    }

    /**
     * moves every CPU for this tick and resolves bumps
     * @param delta seconds since the last update
     * @param raceStarted false before GO (cars wait on the grid)
     */
    public void update(float delta, boolean raceStarted) {
        // estimate each player's speed from how far they moved since last update
        for (PlayerCar p : players.values()) {
            float moved = Vector2.dst(p.x, p.y, p.newX, p.newY);
            p.speed = delta > 0 ? moved / delta : 0f;
            p.x = p.newX;
            p.y = p.newY;
        }
        if (!raceStarted) {
            return;
        }
        for (Opponent cpu : cpus) {
            cpu.beginTick();
        }
        // split this update into equal small steps (at most 1/60 s each), so the cars move
        // the same distance every frame and look smooth at any frame rate
        float time = Math.min(delta, 0.25f);
        int steps = Math.max(1, (int) Math.ceil(time / MAX_STEP - 1e-4f));
        float dt = time / steps;
        for (int s = 0; s < steps; s++) {
            for (Opponent cpu : cpus) {
                cpu.drive(dt, this);
            }
            resolveCollisions();
        }
        for (Opponent cpu : cpus) {
            cpu.endTick();
        }
    }

    /**
     * Looks for the nearest car in front of a CPU, in its lane.
     * Sets aheadAlong (distance ahead), aheadLateral (+ = to its right) and aheadSpeed.
     * @param me the CPU looking
     * @param range how far ahead to look
     * @return true if a car was found
     */
    boolean findCarAhead(Opponent me, float range) {
        float h = me.getRotation();
        float fx = MathUtils.cosDeg(h), fy = MathUtils.sinDeg(h);
        float best = Float.MAX_VALUE;
        for (Opponent other : cpus) {
            if (other != me && !other.isFinished()) {
                best = consider(me, fx, fy, other.getPosition().x, other.getPosition().y,
                    other.getSpeed() * (MathUtils.cosDeg(other.getRotation()) * fx + MathUtils.sinDeg(other.getRotation()) * fy),
                    range, best);
            }
        }
        for (PlayerCar p : players.values()) {
            float along = p.speed * (MathUtils.cosDeg(p.heading) * fx + MathUtils.sinDeg(p.heading) * fy);
            best = consider(me, fx, fy, p.x, p.y, along, range, best);
        }
        return best < Float.MAX_VALUE;
    }

    private float consider(Opponent me, float fx, float fy, float ox, float oy, float otherSpeedAlong, float range, float best) {
        float rx = ox - me.getPosition().x;
        float ry = oy - me.getPosition().y;
        float along = rx * fx + ry * fy;
        float lateral = rx * fy - ry * fx;   // positive = to the right of travel
        if (along > 0f && along < range && along < best && Math.abs(lateral) < CarBody.WIDTH + 4f) {
            aheadAlong = along;
            aheadLateral = lateral;
            aheadX = ox;
            aheadY = oy;
            aheadSpeed = Math.max(0f, otherSpeedAlong);
            return along;
        }
        return best;
    }

    /**
     * pushes touching cars apart; repeated a few times so chains of cars settle
     */
    private void resolveCollisions() {
        for (int pass = 0; pass < SEPARATION_PASSES; pass++) {
            boolean any = false;
            for (int i = 0; i < cpus.size(); i++) {
                Opponent a = cpus.get(i);
                for (int j = i + 1; j < cpus.size(); j++) {
                    Opponent b = cpus.get(j);
                    if (CarBody.separation(a.getPosition().x, a.getPosition().y, a.getRotation(),
                            b.getPosition().x, b.getPosition().y, b.getRotation(), push)) {
                        any = true;
                        addClearance(push);
                        separate(a, b, push);
                    }
                }
                for (PlayerCar p : players.values()) {
                    if (CarBody.separation(a.getPosition().x, a.getPosition().y, a.getRotation(),
                            p.x, p.y, p.heading, push)) {
                        any = true;
                        addClearance(push);
                        // the player's own game moves the player; here only the CPU moves
                        // the heavier the player's car, the more of the push and the bump the CPU takes
                        float share = p.mass / (1f + p.mass);
                        push.scl(Math.min(1.5f, 2f * share));
                        moveIfOnRoad(a, push.x, push.y);
                        bump(a, push, p.speed * MathUtils.cosDeg(p.heading), p.speed * MathUtils.sinDeg(p.heading), share);
                    }
                }
            }
            if (!any) {
                return;
            }
        }
    }

    private static void addClearance(Vector2 push) {
        float len = push.len();
        if (len > 1e-6f) {
            push.scl((len + CLEARANCE) / len);
        }
    }

    /**
     * splits the push between two CPUs; if one would go off the road the other takes all of it
     */
    private void separate(Opponent a, Opponent b, Vector2 pushA) {
        float hx = pushA.x / 2f, hy = pushA.y / 2f;
        boolean aOk = fits(a, hx, hy);
        boolean bOk = fits(b, -hx, -hy);
        if (aOk && bOk) {
            a.push(hx, hy);
            b.push(-hx, -hy);
        } else if (aOk) {
            moveIfOnRoad(a, pushA.x, pushA.y);
        } else if (bOk) {
            moveIfOnRoad(b, -pushA.x, -pushA.y);
        }
        // trade momentum: the faster car shoves the slower one along
        float avx = a.getSpeed() * MathUtils.cosDeg(a.getRotation()), avy = a.getSpeed() * MathUtils.sinDeg(a.getRotation());
        float bvx = b.getSpeed() * MathUtils.cosDeg(b.getRotation()), bvy = b.getSpeed() * MathUtils.sinDeg(b.getRotation());
        bump(a, pushA, bvx, bvy, 0.5f);
        bump(b, new Vector2(-pushA.x, -pushA.y), avx, avy, 0.5f);
    }

    /** a little bounce in every bump (0 = cars stick together, 1 = perfect bounce) */
    private static final float BOUNCE = 0.25f;

    /**
     * A bump between two cars, along the line between them: each car takes a share of the
     * speed they were closing at (half for cars of the same weight, less for the heavier
     * one). So a fast car that rear-ends a slow one shoves it forward and only loses part
     * of its own speed. The car keeps pointing the
     * way it was going (the sideways part is just the positions being pushed apart).
     * @param car this car
     * @param pushAway the direction it's being pushed (away from the other car)
     * @param otherVx other car's velocity x   @param otherVy other car's velocity y
     * @param take this car's share of the speed change: the other car's share of the total
     * weight (0.5 for two cars of the same weight)
     */
    static void bump(Opponent car, Vector2 pushAway, float otherVx, float otherVy, float take) {
        float len = pushAway.len();
        if (len < 1e-4f) {
            return;
        }
        float nx = pushAway.x / len, ny = pushAway.y / len;
        float fx = MathUtils.cosDeg(car.getRotation()), fy = MathUtils.sinDeg(car.getRotation());
        float vx = car.getSpeed() * fx, vy = car.getSpeed() * fy;
        float closing = (vx - otherVx) * nx + (vy - otherVy) * ny;   // negative = moving toward each other
        if (closing >= 0f) {
            return;
        }
        vx -= nx * closing * take * (1f + BOUNCE);
        vy -= ny * closing * take * (1f + BOUNCE);
        car.setSpeed(Math.max(0f, vx * fx + vy * fy));
    }

    /**
     * @return true if a car whose image starts at (x, y) would be on the road
     */
    boolean carOnRoad(float x, float y) {
        return track == null || track.onRoad(x + CarBody.WIDTH / 2f, y + CarBody.LENGTH / 2f);
    }

    private void moveIfOnRoad(Opponent car, float dx, float dy) {
        if (fits(car, dx, dy)) {
            car.push(dx, dy);
        }
    }

    private boolean fits(Opponent car, float dx, float dy) {
        if (track == null) {
            return true;
        }
        Vector2 p = car.getPosition();
        return track.onRoad(p.x + dx + CarBody.WIDTH / 2f, p.y + dy + CarBody.LENGTH / 2f);
    }

    /**
     * @return the CPUs in this race
     */
    public List<Opponent> getCpus() {
        return new ArrayList<>(cpus);
    }

    /** latest known state of a human player */
    private static class PlayerCar {
        float x, y, newX, newY, heading, speed, mass = 1f;
    }
}
