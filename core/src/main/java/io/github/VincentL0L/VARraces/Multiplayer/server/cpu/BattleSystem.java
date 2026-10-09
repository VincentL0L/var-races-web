package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.packets.Entry;

/**
 * Battle mode, a demolition derby: everyone starts with 100 health, ramming another car at
 * speed hurts it (heavier cars hit harder and take less), items do damage too. A car at 0 is
 * knocked out. Last car driving wins; if time runs out, whoever has the most health left.
 *
 * Whoever runs the race runs this (the game in single player, the server online). The
 * standings go out like a race's: progress = 10000 + health while alive (the time it was
 * knocked out once it's out), lapCount = knockouts scored, and once the battle is over every
 * car gets a "finish time" in finishing order.
 */
public class BattleSystem {
    public static final float MAX_HP = 100f;
    /** seconds before the battle ends on health */
    public static final float TIME_LIMIT = 150f;
    /** progress at or above this means the car is still in (progress - ALIVE = health) */
    public static final float ALIVE = 10000f;
    /** damage per px/s of ramming speed (a full-speed T-bone does about 40) */
    private static final float RAM_DAMAGE = 0.085f;
    /** slower bumps than this don't hurt */
    private static final float RAM_THRESHOLD = 70f;
    /** the same two cars can't hurt each other again for this long (one crash = one hit) */
    private static final float PAIR_COOLDOWN = 1.0f;
    /** a knockout counts for whoever hit the car within this many seconds */
    private static final float CREDIT_TIME = 3f;
    public static final float ROCKET_DAMAGE = 30f;
    public static final float OIL_DAMAGE = 14f;
    public static final float PULSE_DAMAGE = 10f;
    public static final float FROST_DAMAGE = 7f;
    public static final float FIRE_DAMAGE = 11f;

    private static class Car {
        String id;
        float hp = MAX_HP;
        float x, y, vx, vy, mass = 1f;
        boolean out = false;
        float outTime;
        int kills;
        String lastHitBy;
        float lastHitAt = -100f;
        Opponent cpu;
        boolean seen = false;
    }

    private final Map<String, Car> cars = new LinkedHashMap<>();
    private final Map<String, Float> cooldowns = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private float time = 0f;
    private boolean over = false;

    /**
     * moves the battle on: works out every car's speed, finds crashes and hands out damage
     * @param dt seconds
     * @param racers every car (center positions)
     * @param masses each car's weight (1 = standard)
     */
    public void update(float dt, List<ItemSystem.Racer> racers, Map<String, Float> masses) {
        update(dt, racers, masses, true);
    }

    /**
     * @param live false before GO: the cars are only registered (so the standings show them)
     */
    public void update(float dt, List<ItemSystem.Racer> racers, Map<String, Float> masses, boolean live) {
        if (over) {
            return;
        }
        for (ItemSystem.Racer r : racers) {
            Car c = cars.get(r.id);
            if (c == null) {
                c = new Car();
                c.id = r.id;
                cars.put(r.id, c);
            }
            if (r.cpu != null) {
                c.vx = MathUtils.cosDeg(r.cpu.getRotation()) * r.cpu.getSpeed();
                c.vy = MathUtils.sinDeg(r.cpu.getRotation()) * r.cpu.getSpeed();
            } else if (c.seen) {
                // a player's speed, from how far they moved (smoothed, positions arrive in steps)
                float vx = (r.x - c.x) / dt, vy = (r.y - c.y) / dt;
                if (Math.abs(vx) < 2000f && Math.abs(vy) < 2000f) {
                    c.vx += (vx - c.vx) * 0.5f;
                    c.vy += (vy - c.vy) * 0.5f;
                }
            }
            c.x = r.x;
            c.y = r.y;
            c.seen = true;
            c.cpu = r.cpu;
            Float m = masses.get(r.id);
            c.mass = m == null ? 1f : m;
        }
        if (!live || dt <= 0f) {
            return;
        }
        time += dt;
        for (Map.Entry<String, Float> e : new ArrayList<>(cooldowns.entrySet())) {
            float left = e.getValue() - dt;
            if (left <= 0f) {
                cooldowns.remove(e.getKey());
            } else {
                cooldowns.put(e.getKey(), left);
            }
        }

        // crashes: two cars touching, and closing on each other fast enough
        List<Car> list = new ArrayList<>(cars.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Car a = list.get(i), b = list.get(j);
                if (a.out && b.out) {
                    continue;
                }
                float dx = b.x - a.x, dy = b.y - a.y;
                float dist = (float) Math.hypot(dx, dy);
                if (dist > CarBody.LENGTH + 4f || dist < 1e-3f) {
                    continue;
                }
                String key = a.id.compareTo(b.id) < 0 ? a.id + "|" + b.id : b.id + "|" + a.id;
                if (cooldowns.containsKey(key)) {
                    continue;
                }
                float nx = dx / dist, ny = dy / dist;
                // how fast each car was driving into the other
                float aInto = a.vx * nx + a.vy * ny;
                float bInto = -(b.vx * nx + b.vy * ny);
                float total = a.mass + b.mass;
                float toB = Math.max(0f, aInto - RAM_THRESHOLD) * RAM_DAMAGE * (2f * a.mass / total);
                float toA = Math.max(0f, bInto - RAM_THRESHOLD) * RAM_DAMAGE * (2f * b.mass / total);
                // the one doing the ramming feels it a little too
                toA += toB * 0.2f;
                toB += toA * 0.2f;
                if (toA + toB < 2f) {
                    continue;
                }
                cooldowns.put(key, PAIR_COOLDOWN);
                if (toB >= 2f) {
                    hurt(b, toB, a.out ? null : a.id);
                }
                if (toA >= 2f) {
                    hurt(a, toA, b.out ? null : b.id);
                }
            }
        }

        int alive = 0;
        for (Car c : cars.values()) {
            if (!c.out) {
                alive++;
            }
        }
        if ((alive <= 1 && cars.size() > 1) || time >= TIME_LIMIT) {
            over = true;
            events.add("BATTLEOVER");
        }
    }

    /**
     * damage from an item
     * @param id the car hit   @param amount health lost   @param by who fired it (or null)
     */
    public void damage(String id, float amount, String by) {
        Car c = cars.get(id);
        if (c != null && !over) {
            hurt(c, amount, by);
        }
    }

    private void hurt(Car c, float amount, String by) {
        if (c.out) {
            return;
        }
        c.hp = Math.max(0f, c.hp - amount);
        if (by != null && !by.equals(c.id)) {
            c.lastHitBy = by;
            c.lastHitAt = time;
        }
        events.add("DMG|" + c.id + "|" + Math.round(amount));
        if (c.hp <= 0f) {
            c.out = true;
            c.outTime = time;
            String killer = c.lastHitBy != null && time - c.lastHitAt <= CREDIT_TIME ? c.lastHitBy : null;
            if (killer != null && cars.containsKey(killer)) {
                cars.get(killer).kills++;
            }
            if (c.cpu != null) {
                c.cpu.knockOut();
            }
            events.add("OUT|" + c.id + "|" + (killer == null ? "" : killer));
        }
    }

    /**
     * @param id a car
     * @return true if it's been knocked out
     */
    public boolean isOut(String id) {
        Car c = cars.get(id);
        return c != null && c.out;
    }

    public boolean isOver() {
        return over;
    }

    /**
     * @return what happened since the last call (DMG|id|amount, OUT|id|by, BATTLEOVER), cleared
     */
    public List<String> takeEvents() {
        List<String> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /**
     * @return car ids, best first: still in (most health first), then by how long they lasted
     */
    public List<String> order() {
        List<Car> list = new ArrayList<>(cars.values());
        Collections.sort(list, new Comparator<Car>() {
            public int compare(Car a, Car b) {
                return Float.compare(score(b), score(a));
            }
        });
        List<String> ids = new ArrayList<>();
        for (Car c : list) {
            ids.add(c.id);
        }
        return ids;
    }

    private static float score(Car c) {
        return c.out ? c.outTime : ALIVE + c.hp;
    }

    /**
     * @return the standings in race form (see the class comment)
     */
    public List<Entry> toEntries() {
        List<Entry> entries = new ArrayList<>();
        List<String> ids = order();
        for (int place = 0; place < ids.size(); place++) {
            Car c = cars.get(ids.get(place));
            float finish = over ? time + place * 0.01f : -1f;
            entries.add(new Entry(c.id, c.kills, score(c), finish));
        }
        return entries;
    }

    /**
     * @return seconds into the battle
     */
    public float getTime() {
        return time;
    }

    /**
     * @return the ids of the cars still in
     */
    public List<String> alive() {
        List<String> ids = new ArrayList<>();
        for (Car c : cars.values()) {
            if (!c.out) {
                ids.add(c.id);
            }
        }
        return ids;
    }

    /** where every car is, for the CPUs to hunt (unused by players) */
    Vector2 positionOf(String id) {
        Car c = cars.get(id);
        return c == null ? null : new Vector2(c.x, c.y);
    }
}
