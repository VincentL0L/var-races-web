package io.github.VincentL0L.VARraces.server;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Track;

/**
 * Road check for the server, read from road_mask.png packed into the server jar (white = road)
 */
public class ImageTrack implements Track {
    private final BufferedImage mask;

    /**
     * loads road_mask.png from the jar
     */
    public ImageTrack() {
        try (InputStream in = ImageTrack.class.getResourceAsStream("/road_mask.png")) {
            if (in == null) {
                throw new IllegalStateException("road_mask.png missing from the server jar");
            }
            mask = ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalStateException("could not read road_mask.png", e);
        }
    }

    public boolean onRoad(float x, float y) {
        int px = (int) x;
        int py = mask.getHeight() - (int) y;
        if (px < 0 || py < 0 || px >= mask.getWidth() || py >= mask.getHeight()) {
            return false;
        }
        int rgb = mask.getRGB(px, py);
        return ((rgb >> 16) & 255) >= 217 && ((rgb >> 8) & 255) >= 217 && (rgb & 255) >= 217;
    }
}
