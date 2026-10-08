package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/**
 * The items, Mario Kart style: rows of item boxes on the long straights, and five items.
 *
 *  - NITRO: a big burst of speed
 *  - ROCKET (Bottle Rocket): flies straight ahead and spins out the first car it hits
 *  - OIL (Oil Slick): dropped behind; whoever drives over it spins out
 *  - BUBBLE (Bubble Shield): soaks up the next hit
 *  - PULSE (Static Pulse): slows down everyone ahead of you for a few seconds
 *
 * Whoever runs the race runs this (the game in single player, the server online). CPUs
 * are hit directly; what happens to human players goes out as events, which their games
 * apply to their own car:
 *   GOT|id|ITEM   BOOST|id   HIT|id|SPIN   HIT|id|SLOW   BLOCK|id
 */
public class ItemSystem {
    public enum Item {
        NITRO("Nitro"), ROCKET("Bottle Rocket"), OIL("Oil Slick"), BUBBLE("Bubble Shield"), PULSE("Static Pulse");

        public final String label;

        Item(String label) {
            this.label = label;
        }
    }

    /** where a car is this tick (its center) */
    public static class Racer {
        public String id;
        public float x, y, heading, speed;
        public Opponent cpu;      // null for human players
    }

    public static class Box {
        public final float x, y;
        public float respawn = 0f;

        Box(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public boolean isActive() {
            return respawn <= 0f;
        }
    }

    public static class Rocket {
        public float x, y, heading, age;
        String owner;
    }

    public static class Slick {
        public float x, y, age;
    }

    private static final float BOX_RADIUS = 15f;
    private static final float BOX_RESPAWN = 4f;
    private static final float ROCKET_SPEED = 720f;
    private static final float ROCKET_LIFE = 2.2f;
    private static final float HIT_RADIUS = 14f;
    private static final float SLICK_RADIUS = 13f;
    private static final float SLICK_LIFE = 25f;
    private static final float SHIELD_TIME = 8f;
    public static final float SLOW_TIME = 2.5f;

    private final List<Box> boxes = new ArrayList<>();
    private final List<Rocket> rockets = new ArrayList<>();
    private final List<Slick> slicks = new ArrayList<>();
    private final Map<String, Item> held = new HashMap<>();
    private final Map<String, Float> shields = new HashMap<>();
    /** when each CPU will use what it's holding */
    private final Map<String, Float> cpuUseIn = new HashMap<>();
    private final List<String> events = new ArrayList<>();

    /**
     * puts rows of three boxes across the middle of every long straight
     * @param waypoints the map's racing line
     */
    public ItemSystem(List<Vector2> waypoints) {
        for (Vector2[] row : boxRows(waypoints)) {
            for (Vector2 p : row) {
                boxes.add(new Box(p.x, p.y));
            }
        }
    }

    /**
     * @return the box positions, row by row (the same on every machine, so only which
     * boxes are taken has to be sent online)
     */
    public static List<Vector2[]> boxRows(List<Vector2> waypoints) {
        List<Vector2[]> rows = new ArrayList<>();
        int n = waypoints.size();
        for (int i = 0; i < n; i++) {
            Vector2 a = waypoints.get(i), b = waypoints.get((i + 1) % n);
            float len = a.dst(b);
            if (len < 420f) {
                continue;
            }
            float dx = (b.x - a.x) / len, dy = (b.y - a.y) / len;
            float cx = (a.x + b.x) / 2f, cy = (a.y + b.y) / 2f;
            Vector2[] row = new Vector2[3];
            for (int k = -1; k <= 1; k++) {
                row[k + 1] = new Vector2(cx - dy * 34f * k, cy + dx * 34f * k);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * moves rockets, ages oil, hands out items, lets CPUs use theirs
     * @param dt seconds
     * @param racers every car this tick
     * @param order racer ids, leader first
     */
    public void update(float dt, List<Racer> racers, List<String> order) {
        for (Box box : boxes) {
            box.respawn -= dt;
        }
        for (Map.Entry<String, Float> s : new ArrayList<>(shields.entrySet())) {
            float left = s.getValue() - dt;
            if (left <= 0f) {
                shields.remove(s.getKey());
            } else {
                shields.put(s.getKey(), left);
            }
        }

        // driving through a box gives an item (if you aren't holding one)
        for (Racer r : racers) {
            for (Box box : boxes) {
                if (box.isActive() && Vector2.dst(r.x, r.y, box.x, box.y) < BOX_RADIUS && !held.containsKey(r.id)) {
                    box.respawn = BOX_RESPAWN;
                    Item item = roll(order.indexOf(r.id) + 1, order.size());
                    held.put(r.id, item);
                    events.add("GOT|" + r.id + "|" + item.name());
                    if (r.cpu != null) {
                        cpuUseIn.put(r.id, MathUtils.random(0.4f, 2.5f));
                    }
                }
            }
        }

        // rockets fly straight and hit the first car they touch
        for (int i = rockets.size() - 1; i >= 0; i--) {
            Rocket rocket = rockets.get(i);
            rocket.age += dt;
            rocket.x += MathUtils.cosDeg(rocket.heading) * ROCKET_SPEED * dt;
            rocket.y += MathUtils.sinDeg(rocket.heading) * ROCKET_SPEED * dt;
            boolean gone = rocket.age > ROCKET_LIFE;
            for (Racer r : racers) {
                if (!gone && (!r.id.equals(rocket.owner) || rocket.age > 0.4f)
                        && Vector2.dst(r.x, r.y, rocket.x, rocket.y) < HIT_RADIUS) {
                    hit(r, "SPIN");
                    gone = true;
                }
            }
            if (gone) {
                rockets.remove(i);
            }
        }

        // oil slicks
        for (int i = slicks.size() - 1; i >= 0; i--) {
            Slick slick = slicks.get(i);
            slick.age += dt;
            boolean gone = slick.age > SLICK_LIFE;
            for (Racer r : racers) {
                if (!gone && slick.age > 0.5f && Vector2.dst(r.x, r.y, slick.x, slick.y) < SLICK_RADIUS) {
                    hit(r, "SPIN");
                    gone = true;
                }
            }
            if (gone) {
                slicks.remove(i);
            }
        }

        // CPUs use their items after a moment (rockets wait for someone in front)
        for (Racer r : racers) {
            if (r.cpu == null || !held.containsKey(r.id)) {
                continue;
            }
            float wait = cpuUseIn.getOrDefault(r.id, 1f) - dt;
            cpuUseIn.put(r.id, wait);
            Item item = held.get(r.id);
            boolean targetAhead = item == ItemSystem.Item.ROCKET && carAhead(r, racers);
            if (wait <= 0f && (item != Item.ROCKET || targetAhead || wait < -6f)) {
                use(r, racers, order);
            }
        }
    }

    private boolean carAhead(Racer me, List<Racer> racers) {
        float fx = MathUtils.cosDeg(me.heading), fy = MathUtils.sinDeg(me.heading);
        for (Racer o : racers) {
            if (o == me) {
                continue;
            }
            float rx = o.x - me.x, ry = o.y - me.y;
            float along = rx * fx + ry * fy, side = Math.abs(rx * fy - ry * fx);
            if (along > 20f && along < 320f && side < 18f) {
                return true;
            }
        }
        return false;
    }

    /**
     * a random item: cars at the back get the better ones
     * @param place 1 = leading
     */
    static Item roll(int place, int count) {
        float r = count <= 1 ? 0.5f : (place - 1) / (float) (count - 1);
        float[] weights = {
            1f + 3f * r,                        // NITRO
            2f,                                 // ROCKET
            2.6f - 2f * r,                      // OIL
            2f - 1.2f * r,                      // BUBBLE
            place <= 1 ? 0f : 0.3f + 2.4f * r   // PULSE
        };
        float total = 0f;
        for (float w : weights) {
            total += w;
        }
        float pick = MathUtils.random(total);
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick <= 0f) {
                return Item.values()[i];
            }
        }
        return Item.NITRO;
    }

    /**
     * uses whatever this racer is holding
     * @param me the racer
     * @param racers everyone, for the Static Pulse
     * @param order racer ids, leader first
     */
    public void use(Racer me, List<Racer> racers, List<String> order) {
        Item item = held.remove(me.id);
        if (item == null) {
            return;
        }
        float fx = MathUtils.cosDeg(me.heading), fy = MathUtils.sinDeg(me.heading);
        switch (item) {
            case NITRO:
                if (me.cpu != null) {
                    me.cpu.nitro();
                } else {
                    events.add("BOOST|" + me.id);
                }
                break;
            case ROCKET:
                Rocket rocket = new Rocket();
                rocket.x = me.x + fx * 18f;
                rocket.y = me.y + fy * 18f;
                rocket.heading = me.heading;
                rocket.owner = me.id;
                rockets.add(rocket);
                break;
            case OIL:
                Slick slick = new Slick();
                slick.x = me.x - fx * 22f;
                slick.y = me.y - fy * 22f;
                slicks.add(slick);
                break;
            case BUBBLE:
                shields.put(me.id, SHIELD_TIME);
                break;
            case PULSE:
                int myPlace = order.indexOf(me.id);
                for (Racer r : racers) {
                    int place = order.indexOf(r.id);
                    if (r != me && place >= 0 && (myPlace < 0 || place < myPlace)) {
                        hit(r, "SLOW");
                    }
                }
                break;
        }
    }

    /**
     * spins out or slows a car, unless its bubble shield takes the hit
     */
    private void hit(Racer r, String effect) {
        if (shields.remove(r.id) != null) {
            events.add("BLOCK|" + r.id);
            return;
        }
        if (r.cpu != null) {
            if (effect.equals("SPIN")) {
                r.cpu.spinOut();
            } else {
                r.cpu.slowDown(SLOW_TIME);
            }
        } else {
            events.add("HIT|" + r.id + "|" + effect);
        }
    }

    /**
     * @return events since last call (and forgets them)
     */
    public List<String> takeEvents() {
        List<String> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /**
     * @return what's on the track, for drawing:
     * boxes as a string of 1/0 | rockets x,y,heading;... | slicks x,y;... | shielded ids,
     * the same text the server sends as ITEMS|...
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Box box : boxes) {
            sb.append(box.isActive() ? '1' : '0');
        }
        sb.append('|');
        for (Rocket r : rockets) {
            sb.append((int) r.x).append(',').append((int) r.y).append(',').append((int) r.heading).append(';');
        }
        sb.append('|');
        for (Slick s : slicks) {
            sb.append((int) s.x).append(',').append((int) s.y).append(';');
        }
        sb.append('|');
        for (String id : shields.keySet()) {
            sb.append(id).append(';');
        }
        return sb.toString();
    }

    /**
     * @param id a racer
     * @return what they're holding, or null
     */
    public Item heldBy(String id) {
        return held.get(id);
    }
}
