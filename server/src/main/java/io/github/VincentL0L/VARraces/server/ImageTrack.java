package io.github.VincentL0L.VARraces.server;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import java.util.HashMap;
import java.util.Map;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Track;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Road check for the server, read from a map's road mask packed into the server jar (white = road)
 */
public class ImageTrack implements Track {
    /** one loaded mask per map, shared by every room on it */
    private static final Map<String, ImageTrack> LOADED = new HashMap<>();
    private final BufferedImage mask;
    /** map pixels per mask pixel */
    private int scale = 1;

    /**
     * @param map a map
     * @return the road check for it (loaded once)
     */
    public static synchronized ImageTrack forMap(TrackMap map) {
        ImageTrack track = LOADED.get(map.id);
        if (track == null) {
            track = new ImageTrack("/" + map.serverMask);
            track.scale = map.scale;
            LOADED.put(map.id, track);
        }
        return track;
    }

    /**
     * loads a road mask from the jar
     * @param resource its name in the jar, like "/road_mask.png"
     */
    private ImageTrack(String resource) {
        try (InputStream in = ImageTrack.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " missing from the server jar");
            }
            mask = ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    public boolean onRoad(float x, float y) {
        int px = (int) (x / scale);
        int py = (int) ((mask.getHeight() * scale - y) / scale);
        if (px < 0 || py < 0 || px >= mask.getWidth() || py >= mask.getHeight()) {
            return false;
        }
        int rgb = mask.getRGB(px, py);
        return ((rgb >> 16) & 255) >= 217 && ((rgb >> 8) & 255) >= 217 && (rgb & 255) >= 217;
    }
}
