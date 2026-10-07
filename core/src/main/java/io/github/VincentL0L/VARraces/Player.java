package io.github.VincentL0L.VARraces;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;

/**
 * Player is in charge of the car physics: engine, brakes, steering that depends on speed,
 * tire grip (a little drift when boosting) and sliding along walls
 */
public class Player {
    private static Pixmap roadMask;
    private Texture car;
    private Image i;
    private Vector2 velocity;
    private Sound oof;
    private Music sound;

    // ---- car tuning (distances in track pixels, times in seconds) ----
    // The engine works like a real one: at low speed the tires limit how hard the car can
    // launch (TRACTION); once moving, engine power divided by speed limits it, so the pull
    // fades the faster you go. Air drag grows with speed squared and sets the top speed.
    // On the speedometer 1 mph = 2.5 track pixels/s: about 0-60 in 2 s, 0-100 in 4.5 s,
    // top speed about 190 mph (about 235 mph boosting).
    private static final float TRACTION = 100f;
    private static final float ENGINE_POWER = 12000f;
    private static final float BOOST_TRACTION = 130f;
    private static final float BOOST_POWER_MULT = 1.7f;
    private static final float BRAKE_DECEL = 420f;
    private static final float REVERSE_ACCEL = 170f;
    private static final float REVERSE_TOP_SPEED = 90f;
    /** tire rolling resistance, always there */
    private static final float ROLLING_DRAG = 8f;
    /** extra slow-down from the engine when you let off the gas */
    private static final float ENGINE_BRAKING = 22f;
    /** wind resistance, grows with speed squared */
    private static final float AIR_DRAG = 0.000077f;
    /** fastest the car can rotate, in degrees per second */
    private static final float MAX_YAW_RATE = 200f;
    /** how quickly the wheels turn toward the pressed direction */
    private static final float STEER_RESPONSE = 8f;
    /** how strongly the tires stop sideways sliding (higher = grippier) */
    private static final float GRIP = 9f;
    private static final float BOOST_GRIP = 5.5f;
    /** fraction of speed kept when hitting a wall hard */
    private static final float WALL_SCRAPE = 0.6f;
    /** extra slow-down per second while grinding along a wall */
    private static final float SCRAPE_FRICTION = 2.5f;
    /** physics runs in fixed steps so the car drives the same at any frame rate */
    private static final float STEP = 1f / 120f;

    private final float manaRegenRate = 10f;
    private final float boostCost = 20f;

    private float mana = 100f;
    private boolean isBoosting = false;
    private float steer = 0f;
    private float stepTimer = 0f;
    private float bumpCooldown = 0f;

    private int lapCount = 0;
    private int currentWaypointIndex = 0;

    private Vector2 prevPos = null;
    private boolean inputEnabled = false;

    /**
     * Creates a player class and initializes textures, sounds, and other fields
     * @param stage stage that holds actors
     * @param carNumber selected car number to determine car skin
     */
    public Player(Stage stage, int carNumber) {
        this.currentWaypointIndex = 0; 
        
        car = new Texture(Gdx.files.internal("ui/car" + carNumber + ".png"));
        float scaleFactor = 10f / car.getWidth();
        i = new Image(car);
        i.setSize(car.getWidth() * scaleFactor, car.getHeight() * scaleFactor);
        i.setOrigin(i.getWidth() / 2, i.getHeight() / 2);

        velocity = new Vector2();

        roadMask = new Pixmap(Gdx.files.internal("ui/road_mask.png"));

        sound = Gdx.audio.newMusic(Gdx.files.internal("accelerate.mp3"));
        sound.setLooping(true);
        oof = Gdx.audio.newSound(Gdx.files.internal("oof.mp3"));

        i.setPosition(200, 300);

        stage.addActor(i);
    }

    /**
     * updates position of car using physics based on inputs
     * @param delta time since last frame
     */
    public void render(float delta){
        if (prevPos == null) {
            prevPos = new Vector2(getX(), getY());
        }

        boolean gas = inputEnabled && Gdx.input.isKeyPressed(Input.Keys.W);
        boolean brake = inputEnabled && Gdx.input.isKeyPressed(Input.Keys.S);
        boolean left = inputEnabled && Gdx.input.isKeyPressed(Input.Keys.A);
        boolean right = inputEnabled && Gdx.input.isKeyPressed(Input.Keys.D);
        boolean boost = inputEnabled && Gdx.input.isKeyPressed(Input.Keys.SPACE);

        if (gas) {
            sound.play();
        } else {
            sound.stop();
        }

        bumpCooldown -= delta;
        stepTimer += Math.min(delta, 0.1f);
        while (stepTimer >= STEP) {
            stepTimer -= STEP;
            step(STEP, gas, brake, left, right, boost);
        }

        Vector2 currentPos = new Vector2(getX(), getY());
        RaceManager raceManager = new RaceManager();

        if (raceManager.crossedFinishLineBackwards(prevPos, currentPos)) {
            velocity.y = -velocity.y * 0.5f;
            i.setY(301);
            bump();
        }

        prevPos.set(getX(), getY());
    }

    /**
     * one fixed physics step: engine, brakes, drag, steering, tire grip and walls
     */
    private void step(float dt, boolean gas, boolean brake, boolean left, boolean right, boolean boost) {
        // split velocity into "along the car" and "sideways" parts
        float heading = (i.getRotation() + 90) * MathUtils.degreesToRadians;
        float fx = MathUtils.cos(heading);
        float fy = MathUtils.sin(heading);
        float forward = velocity.x * fx + velocity.y * fy;
        float sideways = velocity.x * -fy + velocity.y * fx;

        isBoosting = gas && boost && mana > 0;
        if (isBoosting) {
            mana = Math.max(0f, mana - boostCost * dt);
        } else {
            mana = Math.min(100f, mana + manaRegenRate * dt);
        }

        if (brake) {
            if (forward > 5f) {
                forward = Math.max(0f, forward - BRAKE_DECEL * dt);
            } else {
                // stopped and still holding brake: reverse
                forward = Math.max(-REVERSE_TOP_SPEED, forward - REVERSE_ACCEL * dt);
            }
        } else if (gas) {
            if (forward < 0) {
                forward = Math.min(0f, forward + BRAKE_DECEL * dt);  // going backwards: gas acts as a brake first
            } else {
                float traction = isBoosting ? BOOST_TRACTION : TRACTION;
                float power = ENGINE_POWER * (isBoosting ? BOOST_POWER_MULT : 1f);
                forward += Math.min(traction, power / Math.max(forward, 1f)) * dt;
            }
        } else {
            forward = approachZero(forward, ENGINE_BRAKING * dt);
        }
        forward = approachZero(forward, ROLLING_DRAG * dt);
        forward -= Math.signum(forward) * AIR_DRAG * forward * forward * dt;

        // tires resist sliding sideways; less grip while boosting so the car drifts a bit
        sideways *= (float) Math.exp(-(isBoosting ? BOOST_GRIP : GRIP) * dt);

        velocity.set(fx * forward - fy * sideways, fy * forward + fx * sideways);

        // steering: wheels turn smoothly, and a car can only rotate while it's moving
        float steerTarget = (left ? 1f : 0f) - (right ? 1f : 0f);
        steer += (steerTarget - steer) * Math.min(1f, STEER_RESPONSE * dt);
        float speed = Math.abs(forward);
        float speedFactor = MathUtils.clamp(speed / 70f, 0f, 1f) / (1f + speed / 550f);
        float direction = forward >= 0 ? 1f : -1f;  // steering flips when reversing, like a real car
        i.rotateBy(steer * MAX_YAW_RATE * speedFactor * direction * dt);
        if (speed < 1f && !gas && !brake) {
            velocity.setZero();
        }

        moveWithWalls(dt);
    }

    /**
     * moves the car; on hitting a wall it slides along it instead of bouncing backwards
     */
    private void moveWithWalls(float dt) {
        float x = i.getX();
        float y = i.getY();
        float w = i.getWidth();
        float h = i.getHeight();
        float nx = x + velocity.x * dt;
        float ny = y + velocity.y * dt;

        if (onRoad(nx, ny, w, h)) {
            i.setPosition(nx, ny);
            return;
        }
        float impact;
        if (onRoad(nx, y, w, h)) {
            // wall is above or below: keep sliding sideways along it
            impact = Math.abs(velocity.y);
            velocity.y = 0f;
            velocity.x *= (float) Math.exp(-SCRAPE_FRICTION * dt);
            i.setPosition(nx, y);
        } else if (onRoad(x, ny, w, h)) {
            impact = Math.abs(velocity.x);
            velocity.x = 0f;
            velocity.y *= (float) Math.exp(-SCRAPE_FRICTION * dt);
            i.setPosition(x, ny);
        } else {
            // head-on: stop with a small knock back
            impact = velocity.len();
            velocity.scl(-0.2f);
        }
        if (impact > 60f) {
            velocity.scl(WALL_SCRAPE);
            bump();
        }
    }

    /**
     * plays the crash sound, at most a couple of times a second
     */
    private void bump() {
        if (bumpCooldown <= 0f) {
            oof.play();
            bumpCooldown = 0.5f;
        }
    }

    /**
     * moves a value toward 0 by amount without passing it
     */
    private static float approachZero(float value, float amount) {
        if (value > 0) return Math.max(0f, value - amount);
        return Math.min(0f, value + amount);
    }

    /**
     * checks if car is on road with given inputs
     * @param x x coordinate of car
     * @param y y coordinate of car
     * @param width width of car
     * @param height height of car
     * @return true if car is on the road; false if not
     */
    public static boolean onRoad(float x, float y, float width, float height) {
        int X = (int)(x + width / 2);
        int Y = roadMask.getHeight() - (int)(y + height/2);

        int pixel = roadMask.getPixel(X, Y);
        Color color = new Color();
        Color.rgba8888ToColor(color, pixel);
        
        return color.r >=  0.85f && color.g >=  0.85f && color.b >=  0.85f;
    }

    /**
     * clears memory
     */
    public void dispose(){
        car.dispose();
        roadMask.dispose();
        sound.stop();
        sound.dispose();
    }

    public Vector2 getPrevPos(){
        return prevPos;
    }

    public float getX() {
        return i.getX();
    }

    public float getY() {
        return i.getY();
    }

    public Image getImage(){
        return i;
    }

    public float getWidth() {
        return i.getWidth();
    }

    public float getHeight() {
        return i.getHeight();
    }

    public float getRotation() {
        return i.getRotation();
    }

    public Vector2 getVelocity() {
        return velocity;
    }

    public float getMana() {
        return mana;
    }

    public int getLapCount() {
        return lapCount;
    }

    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }
    
    public void incrementLap() {
        lapCount++;
    }

    public void setInputEnabled(boolean enabled) {
        this.inputEnabled = enabled;
    }
}
