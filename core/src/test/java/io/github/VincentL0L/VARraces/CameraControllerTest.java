package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CameraControllerTest {
    @BeforeClass
    public static void loadNatives() {
        GdxNativesLoader.load();
    }

    @Test
    public void trackStaysUprightWhileFollowingTurns() {
        OrthographicCamera camera = new OrthographicCamera(675, 360);
        CameraController controller = new CameraController(camera);
        // Drive a full circle and start with a rotated camera to verify recovery.
        camera.up.set(1, 0, 0);
        for (int frame = 0; frame < 360; frame++) {
            double angle = Math.toRadians(frame);
            controller.update(100f * (float) Math.cos(angle),
                100f * (float) Math.sin(angle), 1f / 60f);
            assertEquals(0f, camera.up.x, 0f);
            assertEquals(1f, camera.up.y, 0f);
            assertEquals(1f, camera.zoom, 0f);
        }
    }

    @Test
    public void followingIsSmoothAndConsistentAcrossFrameRates() {
        OrthographicCamera slow = followForOneSecond(30);
        OrthographicCamera fast = followForOneSecond(144);
        assertTrue(slow.position.x > 0f && slow.position.x < 100f);
        assertTrue(slow.position.y > 0f && slow.position.y < 50f);
        assertEquals(slow.position.x, fast.position.x, 0.001f);
        assertEquals(slow.position.y, fast.position.y, 0.001f);
    }

    @Test
    public void stationaryTargetDoesNotJitter() {
        OrthographicCamera camera = new OrthographicCamera(675, 360);
        camera.position.set(100, 200, 0);
        CameraController controller = new CameraController(camera);
        for (int frame = 0; frame < 120; frame++) {
            controller.update(100, 200, 1f / 60f);
            assertEquals(100f, camera.position.x, 0f);
            assertEquals(200f, camera.position.y, 0f);
        }
    }

    private OrthographicCamera followForOneSecond(int fps) {
        OrthographicCamera camera = new OrthographicCamera(675, 360);
        CameraController controller = new CameraController(camera);
        for (int frame = 0; frame < fps; frame++) {
            controller.update(100, 50, 1f / fps);
        }
        return camera;
    }
}
