package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;

public class CameraController {
    private OrthographicCamera camera;
    /** how quickly the camera catches up with the car (per second); higher = tighter */
    private static final float FOLLOW_RATE = 12f;
    private static final float ROTATION_RATE = 6f;
    /**
     * 1-Arg constructor for camera
     * @param cam camera
     */
    public CameraController(OrthographicCamera cam) {
        this.camera = cam;
    }
    /**
     * Updates every frame and sets camera position and rotation
     * @param targetX new x-position to follow
     * @param targetY new y-position to follow
     * @param targetRotationDegrees new rotation to follow
     * @param delta seconds since last frame (the smoothing depends on time, not frame count)
     */
    public void update(float targetX, float targetY, float targetRotationDegrees, float delta) {
        float smoothing = 1f - (float) Math.exp(-FOLLOW_RATE * delta);
        float rotationSmoothing = 1f - (float) Math.exp(-ROTATION_RATE * delta);
        float newX = MathUtils.lerp(camera.position.x, targetX, smoothing);
        float newY = MathUtils.lerp(camera.position.y, targetY, smoothing);
        camera.position.set(newX, newY, 0);
        // a crash shakes the view for a moment
        if (shake > 0.05f) {
            camera.position.add(MathUtils.random(-shake, shake), MathUtils.random(-shake, shake), 0);
            shake *= (float) Math.exp(-10f * delta);
        }

        float currentRot = (float) Math.toDegrees(Math.atan2(camera.up.y, camera.up.x));
        float newRot = MathUtils.lerpAngleDeg(currentRot, targetRotationDegrees, rotationSmoothing);

        camera.up.set(MathUtils.cosDeg(newRot), MathUtils.sinDeg(newRot), 0);

        camera.update();
    }

    private float shake = 0f;

    /**
     * @param amount how hard to shake the view (map pixels)
     */
    public void shake(float amount) {
        shake = Math.max(shake, amount);
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
