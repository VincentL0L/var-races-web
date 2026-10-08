package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

/**
 * The frosted race track behind the lobby menus. The camera drifts across the map at a
 * steady speed and bounces off its edges, like the old DVD logo.
 *
 * Where the camera is and which way it's drifting are shared by every screen that uses
 * it, so going from the online menu to a lobby carries on the same drift without a jump.
 */
public class TrackBackdrop {
    private static final float MAP_WIDTH = 1920f;
    private static final float MAP_HEIGHT = 1080f;
    private static final float SPEED_X = 70f;
    private static final float SPEED_Y = 45f;

    private static float camX = 1000f;
    private static float camY = 500f;
    private static float driftX = MathUtils.randomSign() * SPEED_X;
    private static float driftY = MathUtils.randomSign() * SPEED_Y;

    private final OrthographicCamera camera = new OrthographicCamera(675, 360);
    private final Stage stage = new Stage(new ExtendViewport(675, 360, camera));
    private final Background background = new Background(stage);
    private final FrostedBackdrop frost = new FrostedBackdrop();

    /**
     * moves the drift on and draws the frosted track over the whole screen
     * @param delta seconds since last frame
     */
    public void render(float delta) {
        drift(delta);
        stage.getViewport().apply();
        frost.begin();
        background.render(camera);
        frost.end();
        frost.draw(1f);
    }

    private void drift(float delta) {
        float halfW = Math.min(camera.viewportWidth * camera.zoom / 2f, MAP_WIDTH / 2f);
        float halfH = Math.min(camera.viewportHeight * camera.zoom / 2f, MAP_HEIGHT / 2f);
        camX += driftX * delta;
        camY += driftY * delta;
        if (camX < halfW) {
            camX = halfW;
            driftX = Math.abs(driftX);
        } else if (camX > MAP_WIDTH - halfW) {
            camX = MAP_WIDTH - halfW;
            driftX = -Math.abs(driftX);
        }
        if (camY < halfH) {
            camY = halfH;
            driftY = Math.abs(driftY);
        } else if (camY > MAP_HEIGHT - halfH) {
            camY = MAP_HEIGHT - halfH;
            driftY = -Math.abs(driftY);
        }
        camera.position.set(camX, camY, 0);
        camera.update();
    }

    /**
     * @param width new screen width   @param height new screen height
     */
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, false);
    }

    public void dispose() {
        frost.dispose();
        background.dispose();
        stage.dispose();
    }
}
