package io.github.VincentL0L.VARraces;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Stage;

/**
 * background of track
 */
public class Background {
    private Stage stage;
    private Texture backgroundTexture;
    private Texture grassTexture;
    private TextureRegion grassRegion;
    private SpriteBatch batch;
    private float worldWidth;
    private float worldHeight;
    /**
     * 1-Arg constructor for BackGround
     * Sets RaceTrack over mask and grass on sides
     * @param stage
     */
    public Background(Stage stage, TrackMap map) {
        backgroundTexture = new Texture(map.background);
        grassTexture = new Texture("ui/grass.png");
        
        grassRegion = new TextureRegion(grassTexture, 0, 0, 512, 512);
        
        batch = new SpriteBatch();
    }
    /**
     * Renders every frame
     * Draws Grass on sides and actual racing map
     * @param camera Camera view for user
     */
    public void render(com.badlogic.gdx.graphics.Camera camera) {
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        
        Vector3 camPos = camera.position;
        float viewportWidth = camera.viewportWidth;
        float viewportHeight = camera.viewportHeight;
 
        float leftEdge = camPos.x - viewportWidth/2;
        float bottomEdge = camPos.y - viewportHeight/2;
        float rightEdge = camPos.x + viewportWidth/2;
        float topEdge = camPos.y + viewportHeight/2;
        
        float tileSize = 256;
        
        int startX = (int)(leftEdge / tileSize) - 1;
        int startY = (int)(bottomEdge / tileSize) - 1;
        int endX = (int)(rightEdge / tileSize) + 1;
        int endY = (int)(topEdge / tileSize) + 1;
        
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                batch.draw(grassRegion,
                    x * tileSize,
                    y * tileSize,
                    tileSize,
                    tileSize);
            }
        }
        
        batch.draw(backgroundTexture, 0, 0);
        batch.end();
    }
    /**
     * When switching screens, 
     */
    public void dispose() {
        backgroundTexture.dispose();
        grassTexture.dispose();
        batch.dispose();
    }
}