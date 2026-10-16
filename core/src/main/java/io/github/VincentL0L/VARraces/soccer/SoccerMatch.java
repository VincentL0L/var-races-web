package io.github.VincentL0L.VARraces.soccer;

/** Server-authoritative car soccer simulation, shared by desktop/web builds and tests. */
public final class SoccerMatch {
    public static final float WIDTH = 1400, HEIGHT = 900, GOAL_WIDTH = 280, GOAL_HEIGHT = 105, GOAL_DEPTH = 80;
    public static final float BALL_RADIUS = 26, CAR_RADIUS = 15;
    public static final int WAITING = 0, KICKOFF = 1, PLAYING = 2, GOAL = 3, FINISHED = 4;
    public static final int WIN_SCORE = 5;
    public static final int BOOST_PAD = 1, FULL_BOOST = 2, NITRO = 3, POWER_SHOT = 4, MAGNET = 5;
    public static final float MAGNET_RANGE = 240;
    public final int teamSize;
    public final Car[] cars;
    public final Pickup[] pickups = createPickups();
    public final Ball ball = new Ball();
    public int blue, orange, phase = WAITING;
    public float remaining = 180, phaseTime;
    public boolean overtime;
    public String event = "Waiting for players";
    public long tick;

    public static final class Car {
        public final int slot, team;
        public float x, y, z, vx, vy, vz, heading, boost = 100;
        public float throttle, steer;
        public boolean boosting, jumpQueued, jumpHeld;
        public float nitroTime, shotTime, magnetTime;
        public boolean boostActive;
        public int pickupSerial, lastPickup;
        private float inputAge;
        Car(int slot) { this.slot = slot; team = slot % 2; }
    }

    public static final class Ball {
        public float x = WIDTH / 2, y = HEIGHT / 2, z = BALL_RADIUS, vx, vy, vz;
    }

    /** Shared placement data; only the server changes cooldowns or awards effects. */
    public static final class Pickup {
        public final float x, y;
        public final int type;
        public float cooldown;
        Pickup(float x, float y, int type) { this.x = x; this.y = y; this.type = type; }
        public float respawnSeconds() { return type == BOOST_PAD ? 4 : type == FULL_BOOST ? 10 : 12; }
        public float radius() { return type == BOOST_PAD ? 14 : type == FULL_BOOST ? 24 : 20; }
    }

    public static Pickup[] createPickups() {
        return new Pickup[] {
            new Pickup(440, 450, BOOST_PAD), new Pickup(960, 450, BOOST_PAD),
            new Pickup(350, 220, BOOST_PAD), new Pickup(700, 220, BOOST_PAD), new Pickup(1050, 220, BOOST_PAD),
            new Pickup(350, 680, BOOST_PAD), new Pickup(700, 680, BOOST_PAD), new Pickup(1050, 680, BOOST_PAD),
            new Pickup(140, 130, FULL_BOOST), new Pickup(1260, 130, FULL_BOOST),
            new Pickup(140, 770, FULL_BOOST), new Pickup(1260, 770, FULL_BOOST),
            new Pickup(430, 160, NITRO), new Pickup(970, 740, NITRO),
            new Pickup(510, 450, POWER_SHOT), new Pickup(890, 450, POWER_SHOT),
            new Pickup(700, 110, MAGNET), new Pickup(700, 790, MAGNET)
        };
    }

    public static String pickupName(int type) {
        switch (type) {
            case BOOST_PAD: return "BOOST +25";
            case FULL_BOOST: return "BOOST FULL";
            case NITRO: return "NITRO";
            case POWER_SHOT: return "POWER SHOT";
            case MAGNET: return "MAGNET";
            default: return "";
        }
    }

    public SoccerMatch(int teamSize) {
        if (teamSize != 1 && teamSize != 2) throw new IllegalArgumentException("Choose 1v1 or 2v2");
        this.teamSize = teamSize;
        cars = new Car[teamSize * 2];
    }

    public int count() {
        int n = 0;
        for (Car car : cars) if (car != null) n++;
        return n;
    }

    public void add(int slot) {
        if (slot < 0 || slot >= cars.length || cars[slot] != null) throw new IllegalArgumentException("Slot unavailable");
        cars[slot] = new Car(slot);
        resetPositions();
        if (count() == cars.length) restart();
    }

    public void remove(int slot) {
        cars[slot] = null;
        phase = WAITING;
        event = "Player left. Waiting for a replacement";
        resetPositions();
    }

    public void restart() {
        if (count() != cars.length) return;
        blue = orange = 0; remaining = 180; overtime = false;
        kickoff();
    }

    private void kickoff() {
        resetPositions();
        phase = KICKOFF; phaseTime = 3; event = "KICKOFF";
    }

    private void resetPositions() {
        for (Pickup pickup : pickups) pickup.cooldown = 0;
        ball.x = WIDTH / 2; ball.y = HEIGHT / 2; ball.z = BALL_RADIUS;
        ball.vx = ball.vy = ball.vz = 0;
        for (Car car : cars) {
            if (car == null) continue;
            car.x = car.team == 0 ? 260 : WIDTH - 260;
            car.y = HEIGHT / 2 + (teamSize == 1 ? 0 : (car.slot / 2 == 0 ? -145 : 145));
            car.heading = car.team == 0 ? 0 : 180;
            car.z = car.vx = car.vy = car.vz = 0;
            car.boost = 100;
            car.throttle = car.steer = 0;
            car.boosting = car.jumpQueued = car.jumpHeld = false;
            car.nitroTime = car.shotTime = car.magnetTime = 0;
            car.boostActive = false;
        }
    }

    public void input(int slot, float throttle, float steer, boolean boost, boolean jump) {
        Car car = cars[slot];
        if (car == null) return;
        car.throttle = clamp(throttle, -1, 1);
        car.steer = clamp(steer, -1, 1);
        car.boosting = boost;
        car.jumpQueued |= jump && !car.jumpHeld;
        car.jumpHeld = jump;
        car.inputAge = 0;
    }

    public void step(float delta) {
        if (!Float.isFinite(delta) || delta <= 0) return;
        delta = Math.min(delta, 0.1f);
        tick++;
        if (phase == WAITING || phase == FINISHED) return;
        if (phase == KICKOFF || phase == GOAL) {
            phaseTime -= delta;
            if (phaseTime <= 0) {
                if (phase == GOAL) kickoff();
                else { phase = PLAYING; event = overtime ? "OVERTIME - next goal wins" : "GO!"; }
            }
            return;
        }
        remaining = Math.max(0, remaining - delta);
        int steps = Math.max(1, (int) Math.ceil(delta * 120));
        float dt = delta / steps;
        for (int i = 0; i < steps && phase == PLAYING; i++) physics(dt);
        if (phase == PLAYING && remaining <= 0) {
            if (blue != orange) finish();
            else { overtime = true; event = "OVERTIME - next goal wins"; }
        }
    }

    private void physics(float dt) {
        for (Pickup pickup : pickups) pickup.cooldown = Math.max(0, pickup.cooldown - dt);
        for (Car car : cars) {
            if (car == null) continue;
            car.nitroTime = Math.max(0, car.nitroTime - dt);
            car.shotTime = Math.max(0, car.shotTime - dt);
            car.magnetTime = Math.max(0, car.magnetTime - dt);
            car.inputAge += dt;
            // A lost connection or backgrounded tab cannot keep holding the accelerator.
            if (car.inputAge > 0.35f) { car.throttle = car.steer = 0; car.boosting = car.jumpQueued = false; }
            float fx = cos(car.heading), fy = sin(car.heading);
            float forward = car.vx * fx + car.vy * fy;
            float sideways = -car.vx * fy + car.vy * fx;
            boolean boost = car.boosting && car.boost > 0 && car.throttle > 0;
            boolean nitro = car.nitroTime > 0 && car.throttle > 0;
            car.boostActive = boost || nitro;
            if (car.throttle != 0) {
                float acceleration = car.throttle * forward < -5 ? 650 : nitro ? 680 : boost ? 480 : 300;
                forward += car.throttle * acceleration * dt;
            } else forward *= (float) Math.exp(-1.5f * dt);
            forward = clamp(forward, -150, nitro ? 760 : boost ? 560 : 380);
            sideways *= (float) Math.exp(-12 * dt);
            car.boost = clamp(car.boost + (boost && !nitro ? -30 : 14) * dt, 0, 100);
            car.heading += car.steer * 125 * Math.min(1, Math.abs(forward) / 110) * (forward < 0 ? -1 : 1) * dt;
            car.heading = (car.heading % 360 + 360) % 360;
            fx = cos(car.heading); fy = sin(car.heading);
            car.vx = fx * forward - fy * sideways;
            car.vy = fy * forward + fx * sideways;
            if (car.jumpQueued && car.z < 0.1f) car.vz = 100;
            car.jumpQueued = false;
            car.vz -= 230 * dt;
            car.z = Math.max(0, car.z + car.vz * dt);
            if (car.z == 0) car.vz = 0;
            car.x += car.vx * dt; car.y += car.vy * dt;
            constrainCar(car);
            collectPickups(car);
        }
        for (int i = 0; i < cars.length; i++) {
            for (int j = i + 1; j < cars.length; j++) {
                if (cars[i] != null && cars[j] != null) collideCars(cars[i], cars[j]);
            }
        }
        for (Car car : cars) if (car != null && car.magnetTime > 0) attractBall(car, dt);
        ball.vz -= 230 * dt;
        ball.x += ball.vx * dt; ball.y += ball.vy * dt; ball.z += ball.vz * dt;
        float drag = (float) Math.exp(-(ball.z <= BALL_RADIUS + 1 ? 0.24f : 0.025f) * dt);
        ball.vx *= drag; ball.vy *= drag;
        if (ball.z < BALL_RADIUS) {
            ball.z = BALL_RADIUS;
            ball.vz = Math.abs(ball.vz) > 12 ? Math.abs(ball.vz) * 0.62f : 0;
        }
        if (ball.z > 300 - BALL_RADIUS) { ball.z = 300 - BALL_RADIUS; ball.vz = -Math.abs(ball.vz) * 0.8f; }
        for (Car car : cars) if (car != null) hitBall(car);
        if (ball.y < BALL_RADIUS) { ball.y = BALL_RADIUS; ball.vy = Math.abs(ball.vy) * 0.82f; }
        if (ball.y > HEIGHT - BALL_RADIUS) { ball.y = HEIGHT - BALL_RADIUS; ball.vy = -Math.abs(ball.vy) * 0.82f; }
        boolean mouth = Math.abs(ball.y - HEIGHT / 2) < GOAL_WIDTH / 2 - BALL_RADIUS
            && ball.z + BALL_RADIUS < GOAL_HEIGHT;
        if (mouth && ball.x < -BALL_RADIUS) score(1);
        else if (mouth && ball.x > WIDTH + BALL_RADIUS) score(0);
        else if (!mouth && ball.x < BALL_RADIUS) { ball.x = BALL_RADIUS; ball.vx = Math.abs(ball.vx) * 0.82f; }
        else if (!mouth && ball.x > WIDTH - BALL_RADIUS) { ball.x = WIDTH - BALL_RADIUS; ball.vx = -Math.abs(ball.vx) * 0.82f; }
    }

    private void collectPickups(Car car) {
        if (car.z > 12) return;
        for (Pickup pickup : pickups) {
            if (pickup.cooldown > 0) continue;
            float dx = car.x - pickup.x, dy = car.y - pickup.y;
            float radius = CAR_RADIUS + pickup.radius();
            if (dx * dx + dy * dy > radius * radius) continue;
            if ((pickup.type == BOOST_PAD || pickup.type == FULL_BOOST) && car.boost >= 99.9f) continue;
            switch (pickup.type) {
                case BOOST_PAD: car.boost = Math.min(100, car.boost + 25); break;
                case FULL_BOOST: car.boost = 100; break;
                case NITRO: car.nitroTime = 6; break;
                case POWER_SHOT: car.shotTime = 8; break;
                case MAGNET: car.magnetTime = 6; break;
                default: continue;
            }
            pickup.cooldown = pickup.respawnSeconds();
            car.pickupSerial++;
            car.lastPickup = pickup.type;
        }
    }

    private void attractBall(Car car, float dt) {
        float bx = ball.x - car.x, by = ball.y - car.y;
        if (bx * bx + by * by > MAGNET_RANGE * MAGNET_RANGE || Math.abs(ball.z - car.z) > 150) return;
        // Pull toward a point in front of the bumper, not through the car. Multiple
        // magnets add real forces; none of them teleports or claims ownership of the ball.
        float dx = car.x + cos(car.heading) * 65 - ball.x;
        float dy = car.y + sin(car.heading) * 65 - ball.y;
        float dz = car.z + 32 - ball.z;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1) return;
        float force = Math.min(720, length * 5) * dt / length;
        ball.vx += dx * force; ball.vy += dy * force; ball.vz += dz * force;
    }

    private void constrainCar(Car c) {
        float low = CAR_RADIUS, high = WIDTH - CAR_RADIUS;
        if (Math.abs(c.y - HEIGHT / 2) < GOAL_WIDTH / 2 - CAR_RADIUS) {
            low -= GOAL_DEPTH; high += GOAL_DEPTH;
        }
        float x = clamp(c.x, low, high), y = clamp(c.y, CAR_RADIUS, HEIGHT - CAR_RADIUS);
        if (x != c.x) c.vx *= -0.2f;
        if (y != c.y) c.vy *= -0.2f;
        c.x = x; c.y = y;
    }

    private void collideCars(Car a, Car b) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist >= 2 * CAR_RADIUS || Math.abs(a.z - b.z) > 20) return;
        if (dist < 0.001f) { dx = 1; dy = 0; dist = 1; }
        float nx = dx / dist, ny = dy / dist, push = (2 * CAR_RADIUS - dist) / 2;
        a.x -= nx * push; a.y -= ny * push; b.x += nx * push; b.y += ny * push;
        float closing = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
        if (closing < 0) {
            float impulse = -closing * 0.65f;
            a.vx -= nx * impulse; a.vy -= ny * impulse;
            b.vx += nx * impulse; b.vy += ny * impulse;
        }
        constrainCar(a); constrainCar(b);
    }

    private void hitBall(Car car) {
        float dx = ball.x - car.x, dy = ball.y - car.y, dz = ball.z - (car.z + 8);
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float radius = BALL_RADIUS + CAR_RADIUS;
        if (dist >= radius) return;
        if (dist < 0.001f) { dx = 1; dy = dz = 0; dist = 1; }
        float nx = dx / dist, ny = dy / dist, nz = dz / dist;
        ball.x += nx * (radius - dist); ball.y += ny * (radius - dist); ball.z += nz * (radius - dist);
        float closing = (ball.vx - car.vx) * nx + (ball.vy - car.vy) * ny + (ball.vz - car.vz) * nz;
        if (closing < 0) {
            float impulse = -closing * 1.65f;
            ball.vx += nx * impulse; ball.vy += ny * impulse; ball.vz += nz * impulse;
            car.vx -= nx * impulse * 0.12f; car.vy -= ny * impulse * 0.12f;
            if (car.shotTime > 0 && closing < -25) {
                float flatLength = (float) Math.sqrt(nx * nx + ny * ny);
                if (flatLength > 0.1f) {
                    ball.vx += nx / flatLength * 260;
                    ball.vy += ny / flatLength * 260;
                    ball.vz = Math.min(100, ball.vz + 18);
                    car.shotTime = 0;
                }
            }
        }
    }

    private void score(int team) {
        if (team == 0) blue++; else orange++;
        event = team == 0 ? "BLUE SCORES!" : "ORANGE SCORES!";
        if (overtime || blue >= WIN_SCORE || orange >= WIN_SCORE) finish();
        else { phase = GOAL; phaseTime = 2.5f; }
    }

    private void finish() {
        phase = FINISHED;
        event = blue > orange ? "BLUE WINS!" : "ORANGE WINS!";
        for (Car c : cars) if (c != null) { c.throttle = c.steer = 0; c.boosting = c.boostActive = false; }
    }

    public String snapshot() {
        StringBuilder s = new StringBuilder("{\"phase\":").append(phase).append(",\"time\":").append(remaining)
            .append(",\"countdown\":").append(phaseTime).append(",\"blue\":").append(blue).append(",\"orange\":").append(orange)
            .append(",\"size\":").append(teamSize).append(",\"tick\":").append(tick).append(",\"event\":\"").append(event)
            .append("\",\"ball\":[").append(ball.x).append(',').append(ball.y).append(',').append(ball.z).append("],\"cars\":[");
        boolean first = true;
        for (Car c : cars) {
            if (c == null) continue;
            if (!first) s.append(',');
            first = false;
            s.append('[').append(c.slot).append(',').append(c.x).append(',').append(c.y).append(',').append(c.z)
                .append(',').append(c.heading).append(',').append(c.boost).append(',').append(c.vx).append(',').append(c.vy).append(']');
        }
        s.append("],\"pickups\":[");
        for (int i = 0; i < pickups.length; i++) {
            if (i > 0) s.append(',');
            s.append(pickups[i].cooldown);
        }
        s.append("],\"effects\":[");
        first = true;
        for (Car c : cars) {
            if (c == null) continue;
            if (!first) s.append(','); first = false;
            s.append('[').append(c.slot).append(',').append(c.nitroTime).append(',').append(c.shotTime)
                .append(',').append(c.magnetTime).append(',').append(c.boostActive ? 1 : 0)
                .append(',').append(c.pickupSerial).append(',').append(c.lastPickup).append(']');
        }
        return s.append("]}").toString();
    }

    private static float clamp(float value, float low, float high) {
        return Float.isFinite(value) ? Math.max(low, Math.min(high, value)) : 0;
    }
    private static float cos(float a) { return (float) Math.cos(Math.toRadians(a)); }
    private static float sin(float a) { return (float) Math.sin(Math.toRadians(a)); }
}
