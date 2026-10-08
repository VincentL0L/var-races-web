package io.github.VincentL0L.VARraces;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
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

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarBody;

/**
 * Player is in charge of the car physics: engine, brakes, steering that depends on speed,
 * tire grip (a little drift when boosting), slow grass off the road, and sliding along
 * the walls at the edge of the map
 */
public class Player {
    private static Pixmap roadMask;
    /** true on maps with walls beside the track (see Barriers) */
    private static boolean barriers = false;
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
    /** top speed on grass, about 46 mph */
    private static final float GRASS_TOP_SPEED = 115f;
    /** how hard the grass slows a fast car down to that speed */
    private static final float GRASS_DRAG = 520f;
    /** tires slide more on grass */
    private static final float GRASS_GRIP = 4f;
    /** cars can't drive closer than this to the edge of the map */
    private static final float MAP_MARGIN = 8f;
    /** fraction of speed kept when hitting a wall hard */
    private static final float WALL_SCRAPE = 0.6f;
    /** extra slow-down per second while grinding along a wall */
    private static final float SCRAPE_FRICTION = 2.5f;
    /** how bouncy car-to-car hits are (0 = dead stop, 1 = full bounce) */
    private static final float CAR_BOUNCE = 0.3f;
    /** longest physics step; each frame is split into equal steps no longer than this */
    private static final float MAX_STEP = 1f / 120f;

    private final float manaRegenRate = 10f;
    private final float boostCost = 20f;

    private float mana = 100f;
    private boolean isBoosting = false;
    private float steer = 0f;
    private float bumpCooldown = 0f;
    private boolean onGrass = false;
    private final Vector2 push = new Vector2();

    private int lapCount = 0;
    private int currentWaypointIndex = 0;

    private Vector2 prevPos = null;
    private boolean inputEnabled = false;
    // phone controls (TouchControls): joystick steering (pulled back = reverse) and the two pedals
    private boolean touchActive, touchGas, touchBoost, touchBrake, touchBackwards;
    private float touchSteer;
    /** an automatic car creeps along at idle with no pedal pressed (about 5 mph) */
    private static final float IDLE_CREEP = 12f;
    private static final float CREEP_ACCEL = 30f;

    /**
     * Creates a player class and initializes textures, sounds, and other fields
     * @param stage stage that holds actors
     * @param carNumber selected car number to determine car skin
     */
    public Player(Stage stage, int carNumber, TrackMap map) {
        this.currentWaypointIndex = 0; 
        
        car = new Texture(Gdx.files.internal("ui/car" + carNumber + ".png"));
        // smooth filtering looks best on a small rotating pixel-art sprite
        car.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        float scaleFactor = CarBody.WIDTH / car.getWidth();
        i = new Image(car);
        i.setSize(car.getWidth() * scaleFactor, car.getHeight() * scaleFactor);
        i.setOrigin(i.getWidth() / 2, i.getHeight() / 2);

        velocity = new Vector2();

        roadMask = new Pixmap(Gdx.files.internal(map.roadMask));
        barriers = map.barriers;
        if (barriers) {
            Barriers.build(roadMask);
        }

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

        // WASD or the arrow keys, or the joystick and pedals on phones and tablets
        boolean gas = inputEnabled && (touchGas || Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP));
        boolean brake = inputEnabled && (touchBrake || Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN));
        boolean left = inputEnabled && (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT));
        boolean right = inputEnabled && (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT));
        boolean boost = inputEnabled && (touchBoost || Gdx.input.isKeyPressed(Input.Keys.SPACE));
        // keys steer all the way; the joystick steers as far as it's pushed
        float steerInput = (left ? 1f : 0f) - (right ? 1f : 0f);
        if (inputEnabled && touchSteer != 0f) {
            steerInput = MathUtils.clamp(touchSteer, -1f, 1f);
        }
        // the pedals drive like a real automatic; the keyboard keeps arcade style
        // (S brakes, then reverses once stopped)
        boolean automatic = inputEnabled && touchActive;

        if (gas) {
            sound.play();
        } else {
            sound.stop();
        }

        bumpCooldown -= delta;
        // split the frame into equal small steps (at most 1/120 s each): the car moves the
        // same distance every frame, so it looks smooth, and handles the same at any frame rate
        float time = Math.min(delta, 0.1f);
        int steps = Math.max(1, (int) Math.ceil(time / MAX_STEP - 1e-4f));
        float dt = time / steps;
        for (int n = 0; n < steps; n++) {
            step(dt, gas, brake, steerInput, boost, automatic);
        }

        prevPos.set(getX(), getY());
    }

    /**
     * one fixed physics step: engine, brakes, drag, steering, tire grip and walls
     */
    private void step(float dt, boolean gas, boolean brake, float steerInput, boolean boost, boolean automatic) {
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

        boolean creeping = false;
        if (automatic) {
            creeping = automaticGearbox(dt, gas, brake, forward);
            forward = automaticForward;
        } else if (brake) {
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

        // grass (like Mario Kart): way slower. Anything over the grass top speed is scrubbed
        // off quickly, so cutting across the grass doesn't pay
        onGrass = !onRoad(i.getX(), i.getY(), i.getWidth(), i.getHeight());
        if (onGrass && Math.abs(forward) > GRASS_TOP_SPEED) {
            forward = Math.signum(forward) * Math.max(GRASS_TOP_SPEED, Math.abs(forward) - GRASS_DRAG * dt);
        }

        // tires resist sliding sideways; less grip while boosting (a little drift) and on grass
        float grip = onGrass ? GRASS_GRIP : isBoosting ? BOOST_GRIP : GRIP;
        sideways *= (float) Math.exp(-grip * dt);

        velocity.set(fx * forward - fy * sideways, fy * forward + fx * sideways);

        // steering: wheels turn smoothly, and a car can only rotate while it's moving
        float steerTarget = steerInput;
        steer += (steerTarget - steer) * Math.min(1f, STEER_RESPONSE * dt);
        float speed = Math.abs(forward);
        float speedFactor = MathUtils.clamp(speed / 70f, 0f, 1f) / (1f + speed / 550f);
        float direction = forward >= 0 ? 1f : -1f;  // steering flips when reversing, like a real car
        i.rotateBy(steer * MAX_YAW_RATE * speedFactor * direction * dt);
        if (speed < 1f && !gas && !creeping) {
            velocity.setZero();
        }

        moveWithWalls(dt);
    }

    /** result of automaticGearbox: the new forward speed */
    private float automaticForward;

    /**
     * How a real automatic gas car responds to its pedals (phone controls):
     *  - gas: the engine pulls forward; with the joystick pulled back it reverses (slowly)
     *  - brake: slows the car to a stop and holds it there; it never reverses
     *  - no pedal: the engine idles, so the car coasts down with engine braking
     *    and creeps forward at walking pace once it's slow
     * @return true while the car is creeping at idle
     */
    private boolean automaticGearbox(float dt, boolean gas, boolean brake, float forward) {
        boolean creeping = false;
        if (brake) {
            forward = approachZero(forward, BRAKE_DECEL * dt);
        } else if (gas && touchBackwards) {
            if (forward > 0f) {
                forward = approachZero(forward, BRAKE_DECEL * dt);   // still rolling forward: the gearbox fights it
            } else {
                forward = Math.max(-REVERSE_TOP_SPEED, forward - REVERSE_ACCEL * dt);
            }
        } else if (gas) {
            if (forward < 0f) {
                forward = approachZero(forward, BRAKE_DECEL * dt);
            } else {
                float traction = isBoosting ? BOOST_TRACTION : TRACTION;
                float power = ENGINE_POWER * (isBoosting ? BOOST_POWER_MULT : 1f);
                forward += Math.min(traction, power / Math.max(forward, 1f)) * dt;
            }
        } else if (!touchBackwards && forward >= 0f && forward < IDLE_CREEP) {
            // idle creep: ease up to walking pace
            creeping = true;
            forward += Math.min(IDLE_CREEP - forward, CREEP_ACCEL * dt);
        } else {
            forward = approachZero(forward, ENGINE_BRAKING * dt);
        }
        automaticForward = forward;
        return creeping;
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

        if (inBounds(nx, ny, w, h)) {
            i.setPosition(nx, ny);
            return;
        }
        float impact;
        if (inBounds(nx, y, w, h)) {
            // wall is above or below: keep sliding sideways along it
            impact = Math.abs(velocity.y);
            velocity.y = 0f;
            velocity.x *= (float) Math.exp(-SCRAPE_FRICTION * dt);
            i.setPosition(nx, y);
        } else if (inBounds(x, ny, w, h)) {
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
     * Pushes this car out of another car it's touching and takes away the speed it was
     * carrying into that car (with a little bounce).
     * @param ox other car image x   @param oy other car image y
     * @param oHeading other car's heading in degrees (90 = up)
     */
    public void collideWith(float ox, float oy, float oHeading) {
        if (!CarBody.separation(getX(), getY(), getRotation() + 90f, ox, oy, oHeading, push)) {
            return;
        }
        float nx = getX() + push.x;
        float ny = getY() + push.y;
        if (inBounds(nx, ny, getWidth(), getHeight())) {
            i.setPosition(nx, ny);
        } else if (inBounds(getX() + push.x / 2f, getY() + push.y / 2f, getWidth(), getHeight())) {
            i.setPosition(getX() + push.x / 2f, getY() + push.y / 2f);
        }
        float len = push.len();
        float dirX = push.x / len, dirY = push.y / len;
        float into = velocity.x * dirX + velocity.y * dirY;   // negative = moving into the other car
        if (into < 0f) {
            velocity.x -= dirX * into * (1f + CAR_BOUNCE);
            velocity.y -= dirY * into * (1f + CAR_BOUNCE);
            if (-into > 50f) {
                bump();
            }
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
    /**
     * The edge of the map is a wall. On maps with barriers, so is everything more than a
     * sidewalk's width from the road (see Barriers), so nobody can skip part of the track.
     * @return true if a car at (x, y) can be there
     */
    public static boolean inBounds(float x, float y, float width, float height) {
        float cx = x + width / 2, cy = y + height / 2;
        return cx >= MAP_MARGIN && cy >= MAP_MARGIN
            && cx < roadMask.getWidth() - MAP_MARGIN && cy < roadMask.getHeight() - MAP_MARGIN
            && (!barriers || Barriers.drivable(cx, cy));
    }

    /**
     * @return true while the car is off the road, on the grass
     */
    public boolean isOnGrass() {
        return onGrass;
    }

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

    /**
     * the phone controls this frame
     * @param active true when the phone controls are in use (the car then drives like an automatic)
     * @param steer joystick: -1 full right .. 1 full left, 0 straight
     * @param gas gas pedal held   @param boost gas held after a double tap
     * @param brake brake pedal held   @param backwards joystick pulled back (gas then reverses)
     */
    public void setTouchInput(boolean active, float steer, boolean gas, boolean boost, boolean brake, boolean backwards) {
        touchActive = active;
        touchSteer = steer;
        touchGas = gas;
        touchBoost = boost;
        touchBrake = brake;
        touchBackwards = backwards;
    }
}
