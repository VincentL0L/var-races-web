package io.github.VincentL0L.VARraces.soccer;

/** Server-authoritative car soccer simulation, shared by desktop/web builds and tests. */
public final class SoccerMatch {
    public static final float WIDTH = 1400, HEIGHT = 900, GOAL_WIDTH = 280, GOAL_HEIGHT = 105, GOAL_DEPTH = 80;
    public static final float BALL_RADIUS = 26, CAR_RADIUS = 15;
    public static final int WAITING = 0, KICKOFF = 1, PLAYING = 2, GOAL = 3, FINISHED = 4;
    public static final int WIN_SCORE = 5;
    public final int teamSize;
    public final Car[] cars;
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
        private float inputAge;
        Car(int slot) { this.slot = slot; team = slot % 2; }
    }

    public static final class Ball {
        public float x = WIDTH / 2, y = HEIGHT / 2, z = BALL_RADIUS, vx, vy, vz;
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
        for (Car car : cars) {
            if (car == null) continue;
            car.inputAge += dt;
            // A lost connection or backgrounded tab cannot keep holding the accelerator.
            if (car.inputAge > 0.35f) { car.throttle = car.steer = 0; car.boosting = car.jumpQueued = false; }
            float fx = cos(car.heading), fy = sin(car.heading);
            float forward = car.vx * fx + car.vy * fy;
            float sideways = -car.vx * fy + car.vy * fx;
            boolean boost = car.boosting && car.boost > 0 && car.throttle > 0;
            if (car.throttle != 0) {
                float acceleration = car.throttle * forward < -5 ? 650 : boost ? 480 : 300;
                forward += car.throttle * acceleration * dt;
            } else forward *= (float) Math.exp(-1.5f * dt);
            forward = clamp(forward, -150, boost ? 560 : 380);
            sideways *= (float) Math.exp(-12 * dt);
            car.boost = clamp(car.boost + (boost ? -30 : 14) * dt, 0, 100);
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
        }
        for (int i = 0; i < cars.length; i++) {
            for (int j = i + 1; j < cars.length; j++) {
                if (cars[i] != null && cars[j] != null) collideCars(cars[i], cars[j]);
            }
        }
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
        for (Car c : cars) if (c != null) { c.throttle = c.steer = 0; c.boosting = false; }
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
        return s.append("]}").toString();
    }

    private static float clamp(float value, float low, float high) {
        return Float.isFinite(value) ? Math.max(low, Math.min(high, value)) : 0;
    }
    private static float cos(float a) { return (float) Math.cos(Math.toRadians(a)); }
    private static float sin(float a) { return (float) Math.sin(Math.toRadians(a)); }
}
