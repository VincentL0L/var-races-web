package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;

public class CameraController {
    private OrthographicCamera camera;
    private float smoothing = 0.1f;
    private float rotationSmoothing = 0.1f;
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
     */
    public void update(float targetX, float targetY, float targetRotationDegrees) {
        float newX = MathUtils.lerp(camera.position.x, targetX, smoothing);
        float newY = MathUtils.lerp(camera.position.y, targetY, smoothing);
        camera.position.set(newX, newY, 0);

        float currentRot = (float) Math.toDegrees(Math.atan2(camera.up.y, camera.up.x));
        float newRot = MathUtils.lerpAngleDeg(currentRot, targetRotationDegrees, rotationSmoothing);

        camera.up.set(MathUtils.cosDeg(newRot), MathUtils.sinDeg(newRot), 0);

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
