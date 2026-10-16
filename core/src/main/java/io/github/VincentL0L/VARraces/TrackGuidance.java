package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/** Turn boards on the outside wall, facing drivers approaching along the racing line. */
public final class TrackGuidance {
    public static final class Sign {
        public final float x, y, rightX, rightY;
        public final boolean turnRight;

        Sign(float x, float y, float rightX, float rightY, boolean turnRight) {
            this.x = x; this.y = y;
            this.rightX = rightX; this.rightY = rightY;
            this.turnRight = turnRight;
        }
    }

    private TrackGuidance() {}

    public static List<Sign> signs(TrackMap map) {
        List<Sign> result = new ArrayList<>();
        if (map.battle || !map.hasBarriers()) return result;
        List<Vector2> points = map.waypoints;
        for (int i = 0; i < points.size(); i++) {
            if (map.pointToPoint && (i == 0 || i == points.size() - 1)) continue;
            Vector2 previous = points.get((i + points.size() - 1) % points.size());
            Vector2 corner = points.get(i);
            Vector2 next = points.get((i + 1) % points.size());
            Vector2 incoming = new Vector2(corner).sub(previous);
            Vector2 outgoing = new Vector2(next).sub(corner);
            if (incoming.len2() < 1f || outgoing.len2() < 1f) continue;
            incoming.nor();
            outgoing.nor();
            float cross = incoming.crs(outgoing);
            // Ignore the straight start/finish waypoint and very gentle bends.
            if (incoming.dot(outgoing) > 0.9f || Math.abs(cross) < 0.2f) continue;
            if (!Barriers.drivable(corner.x, corner.y)) continue;
            // Continue straight past the turn to find the outside wall, rather than
            // putting signs on the road or blocking the exit of the corner.
            float distance = 0f;
            while (distance < 300f && Barriers.drivable(corner.x + incoming.x * distance,
                    corner.y + incoming.y * distance)) distance += 2f;
            if (distance >= 300f) continue;
            result.add(new Sign(corner.x + incoming.x * (distance - 2f),
                corner.y + incoming.y * (distance - 2f), incoming.y, -incoming.x, cross < 0));
        }
        return result;
    }
}
