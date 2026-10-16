package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Hills for the first-person view. The race itself is worked out on the flat map, so the
 * hills are a way of looking at it: the ground, the scenery and the cars are all lifted by
 * the same smooth height, so the road climbs and dips and everything on it follows.
 *
 * The height is a sum of a few long, gentle waves, worked out both here (for the cars, the
 * camera and the items) and in the scenery's vertex shader (for everything built as meshes),
 * with the same numbers, so they agree exactly.
 */
public final class Terrain {
    private static float amp = 0f, k1, k2, k3, p1, p2, p3;

    private Terrain() {}

    /** sets the hills for a map (how high, and a shape of their own) */
    public static void set(TrackMap map) {
        switch (map.id) {
            case TrackMap.SAN_FRANCISCO:
                amp = 46f;
                break;
            case TrackMap.SUMMIT:
                amp = 70f;
                break;
            case TrackMap.CLASSIC:
            case TrackMap.GRAND_PRIX:
                amp = 26f;
                break;
            case TrackMap.MOON:
                amp = 30f;
                break;
            case TrackMap.ROOFTOP:
            case TrackMap.JUNKYARD:
            case TrackMap.STADIUM:
            case TrackMap.RINK:
                amp = 0f;
                break;
            default:
                amp = 18f;                 // the other cities: gentle rises
                break;
        }
        java.util.Random r = new java.util.Random(map.id.hashCode());
        k1 = 0.0019f + r.nextFloat() * 0.0012f;
        k2 = 0.0015f + r.nextFloat() * 0.0012f;
        k3 = 0.0028f + r.nextFloat() * 0.0015f;
        p1 = r.nextFloat() * 6.28f;
        p2 = r.nextFloat() * 6.28f;
        p3 = r.nextFloat() * 6.28f;
    }

    /** @return how high the ground is at (x, y) */
    public static float height(float x, float y) {
        if (amp == 0f) {
            return 0f;
        }
        return amp * (0.6f * MathUtils.sin(x * k1 + p1) * MathUtils.cos(y * k2 + p2)
            + 0.4f * MathUtils.sin((x * 0.7f - y * 0.5f) * k3 + p3));
    }

    /** @return the slope (rise per unit) along x and y at (x, y), into out */
    public static void slope(float x, float y, float[] out) {
        float e = 2f;
        out[0] = (height(x + e, y) - height(x - e, y)) / (2 * e);
        out[1] = (height(x, y + e) - height(x, y - e)) / (2 * e);
    }

    /** hands the hills to a shader that uses {@link #GLSL} */
    public static void apply(ShaderProgram shader) {
        shader.setUniformf("u_hillA", amp, k1, k2, k3);
        shader.setUniformf("u_hillB", p1, p2, p3, 0f);
    }

    /** the same height, for a vertex shader (needs uniforms u_hillA, u_hillB) */
    public static final String GLSL =
        "uniform vec4 u_hillA;\n"
        + "uniform vec4 u_hillB;\n"
        + "float hill(vec2 p) {\n"
        + "  return u_hillA.x * (0.6 * sin(p.x * u_hillA.y + u_hillB.x) * cos(p.y * u_hillA.z + u_hillB.y)\n"
        + "    + 0.4 * sin((p.x * 0.7 - p.y * 0.5) * u_hillA.w + u_hillB.z));\n"
        + "}\n";
}
