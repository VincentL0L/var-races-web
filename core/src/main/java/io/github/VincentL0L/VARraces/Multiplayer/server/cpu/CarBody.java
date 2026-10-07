package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/**
 * The shape every car uses for collisions: a capsule (a rectangle with round ends)
 * as long and wide as the car sprite. Positions passed in are the bottom-left corner
 * of the car's 10x20 image, like everywhere else in the game.
 */
public final class CarBody {
    public static final float WIDTH = 10f;
    public static final float LENGTH = 20f;
    public static final float RADIUS = WIDTH / 2f;
    /** half the length of the capsule's straight middle part */
    private static final float HALF_SPINE = (LENGTH - WIDTH) / 2f;

    private CarBody() {}

    /**
     * Works out how far car A has to move to stop touching car B.
     * @param ax car A image x      @param ay car A image y      @param aHeading car A heading in degrees (90 = up)
     * @param bx car B image x      @param by car B image y      @param bHeading car B heading in degrees
     * @param out set to the push for car A (zero if they don't touch)
     * @return true if the cars overlap
     */
    public static boolean separation(float ax, float ay, float aHeading, float bx, float by, float bHeading, Vector2 out) {
        float acx = ax + WIDTH / 2f, acy = ay + LENGTH / 2f;
        float bcx = bx + WIDTH / 2f, bcy = by + LENGTH / 2f;
        out.setZero();
        // quick reject: too far apart to touch
        float dx = acx - bcx, dy = acy - bcy;
        if (dx * dx + dy * dy > LENGTH * LENGTH) {
            return false;
        }
        float adx = MathUtils.cosDeg(aHeading) * HALF_SPINE, ady = MathUtils.sinDeg(aHeading) * HALF_SPINE;
        float bdx = MathUtils.cosDeg(bHeading) * HALF_SPINE, bdy = MathUtils.sinDeg(bHeading) * HALF_SPINE;

        // closest points between the two capsule spines (segments a0-a1 and b0-b1)
        float a0x = acx - adx, a0y = acy - ady, b0x = bcx - bdx, b0y = bcy - bdy;
        float ux = 2 * adx, uy = 2 * ady, vx = 2 * bdx, vy = 2 * bdy;
        float wx = a0x - b0x, wy = a0y - b0y;
        float uu = ux * ux + uy * uy, uv = ux * vx + uy * vy, vv = vx * vx + vy * vy;
        float uw = ux * wx + uy * wy, vw = vx * wx + vy * wy;
        float denom = uu * vv - uv * uv;
        float s = denom > 1e-6f ? MathUtils.clamp((uv * vw - vv * uw) / denom, 0f, 1f) : 0f;
        float t = MathUtils.clamp((uv * s + vw) / vv, 0f, 1f);
        s = MathUtils.clamp((uv * t - uw) / uu, 0f, 1f);

        float px = (a0x + ux * s) - (b0x + vx * t);
        float py = (a0y + uy * s) - (b0y + vy * t);
        float dist = (float) Math.sqrt(px * px + py * py);
        float overlap = 2 * RADIUS - dist;
        if (overlap <= 0f) {
            return false;
        }
        if (dist < 1e-4f) {
            // exactly on top of each other: push apart along the line between centers, or sideways
            px = dx;
            py = dy;
            dist = (float) Math.sqrt(px * px + py * py);
            if (dist < 1e-4f) {
                px = 1f;
                py = 0f;
                dist = 1f;
            }
        }
        out.set(px / dist * overlap, py / dist * overlap);
        return true;
    }
}
