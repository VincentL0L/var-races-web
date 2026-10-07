package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

/**
 * Tells whether a point is on the road. The game reads the road mask with libGDX,
 * the server reads the same image with plain Java.
 */
public interface Track {
    /**
     * @param x track x (center of a car)
     * @param y track y
     * @return true if (x, y) is on the road
     */
    boolean onRoad(float x, float y);
}
