package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;

public class CameraController {
    private final OrthographicCamera camera;
    /** how quickly the camera catches up with the car (per second); higher = tighter */
    private static final float FOLLOW_RATE = 12f;
    /**
     * 1-Arg constructor for camera
     * @param cam camera
     */
    public CameraController(OrthographicCamera cam) {
        this.camera = cam;
    }
    /**
     * Follows the car while keeping the track in a fixed, north-up orientation.
     * @param targetX new x-position to follow
     * @param targetY new y-position to follow
     * @param delta seconds since last frame (the smoothing depends on time, not frame count)
     */
    public void update(float targetX, float targetY, float delta) {
        float smoothing = 1f - (float) Math.exp(-FOLLOW_RATE * delta);
        float newX = MathUtils.lerp(camera.position.x, targetX, smoothing);
        float newY = MathUtils.lerp(camera.position.y, targetY, smoothing);
        camera.position.set(newX, newY, 0);
        // Steering and spin-outs turn the car, never the whole view.
        camera.up.set(0, 1, 0);

        camera.update();
    }


    public float getX() {
        return camera.position.x;
    }

    public float getY() {
        return camera.position.y;
    }

    public OrthographicCamera getCamera() {
        return camera;
    }
    /**
     * Resets camera viewport
     */
    public void reset() {
        camera.position.set(camera.viewportWidth / 2f, camera.viewportHeight / 2f, 0);
        camera.up.set(0, 1, 0);
        camera.update();
    }
}
