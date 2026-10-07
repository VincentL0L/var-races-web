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
 * Player is in charge of the car physics(accelerate, break, collision with edge of track)
 */
public class Player {
    private static Pixmap roadMask;
    private Texture car;
    private Image i;
    private Vector2 velocity;
    private Vector2 acceleration;
    private float turnSpeed;
    private Sound oof;
    private Music sound;

    private final float maxTurnSpeed = 2f;
    private final float turnAccel = 0.7f;
    private final float turnDecel = 0.3f;
    private final float brakeFactor = 0.98f;
    private final float restitution = 0.9f;
    private final float friction = 0.95f;
    private final float maxSpeed = 3000f;
    private final float manaRegenRate = 10f;
    private final float boostMult = 1.5f;
    private final float boostCost = 20f;

    private float mana = 100f;
    private boolean isBoosting = false;

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
        acceleration = new Vector2();

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
        
        if (inputEnabled) {
            if (Gdx.input.isKeyPressed(Input.Keys.W)) {
                sound.play();
            } 
            else {
                sound.stop();
            }
            boolean turn = false;
            acceleration.setZero();

            float angle = (i.getRotation() + 90) * MathUtils.degreesToRadians;

            isBoosting = false;
            if (Gdx.input.isKeyPressed(Input.Keys.W)) {
                float curBM = 1f;
                if (Gdx.input.isKeyPressed(Input.Keys.SPACE) && mana > 0) {
                    curBM = boostMult;
                    isBoosting = true;
                    mana -= boostCost * delta;
                    mana = Math.max(mana, 0);
                } 
                else {
                    mana += manaRegenRate * delta;
                    mana = Math.min(mana, 100f);
                }

                float accelScale = 1f - (velocity.len() / maxSpeed);
                accelScale = MathUtils.clamp(accelScale, 0f, 1f);
                acceleration.x = MathUtils.cos(angle) * 800 * accelScale * curBM;
                acceleration.y = MathUtils.sin(angle) * 800 * accelScale * curBM;
            } 
            else {
                velocity.x *= 0.999f;
                velocity.y *= 0.9979f;

                mana += manaRegenRate * delta;
                mana = Math.min(mana, 100f);
            }

            if (Gdx.input.isKeyPressed(Input.Keys.S)) {
                velocity.x *= brakeFactor;
                velocity.y *= brakeFactor;
            }

            if (Gdx.input.isKeyPressed(Input.Keys.A)) {
                turn = true;
                turnSpeed += turnAccel;
                turnSpeed = Math.min(turnSpeed, maxTurnSpeed);
            }
            if (Gdx.input.isKeyPressed(Input.Keys.D)) {
                turn = true;
                turnSpeed -= turnAccel;
                turnSpeed = Math.max(turnSpeed, -maxTurnSpeed);
            }
            if (!turn){
                if (turnSpeed > 0){
                    turnSpeed -= turnDecel;
                    turnSpeed = Math.max(0, turnSpeed);
                } 
                else if (turnSpeed < 0){
                    turnSpeed += turnDecel;
                    turnSpeed = Math.min(0, turnSpeed);
                }
            }
            i.rotateBy(turnSpeed);
            velocity.add(acceleration.x * delta, acceleration.y * delta);
            velocity.scl(friction);
            float currentMaxSpeed = isBoosting ? maxSpeed * boostMult : maxSpeed;
            if (velocity.len() > currentMaxSpeed) {
                velocity.setLength(currentMaxSpeed);
            }
        } 
        else {
            velocity.scl(0.98f);
            if (velocity.len() < 0.1f) {
                velocity.setZero();
            }
            sound.stop();
        }

        float newX = i.getX() + velocity.x * delta;
        float newY = i.getY() + velocity.y * delta;


        if (!onRoad(newX, newY, i.getWidth(), i.getHeight())) {
            velocity.scl(-restitution);
            newX = i.getX();
            newY = i.getY();
            oof.play();
        }

        Vector2 currentPos = new Vector2(newX, newY);
        RaceManager raceManager = new RaceManager();

        if (raceManager.crossedFinishLineBackwards(prevPos, currentPos)) {
            velocity.y = -velocity.y * restitution;
            newY = 301;
            oof.play();
        }

        i.setPosition(newX, newY);

        prevPos.set(getX(), getY());
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
