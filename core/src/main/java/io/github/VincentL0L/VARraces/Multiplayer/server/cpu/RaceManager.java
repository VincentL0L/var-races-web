package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.packets.Entry;
import io.github.VincentL0L.VARraces.Multiplayer.packets.LeaderboardPacket;

/**
 * Works out race positions.
 *
 * The track is the loop of waypoints, starting at the start/finish line. Every car's
 * progress is how far along that loop it has driven (plus a full track length per lap),
 * so cars are compared fairly however they take the corners. A lap counts when a car
 * crosses the start/finish line going forward. Finished cars are ranked by finish time,
 * everyone else by progress.
 *
 * The race host (single player game or multiplayer server) calls updateRacer; players'
 * games get a copy through updateFromPacket.
 */
public class RaceManager {

    /** lap counts a race can be set to, in the lobby */
    public static final int[] LAP_CHOICES = {1, 2, 3, 5};
    /** laps in this race */
    private int laps = 1;
    /** seconds the red VAR RACES flag waves after everyone is ready, before the 3 2 1 countdown */
    public static final float FLAG_TIME = 2.15f;

    public final List<RacerInfo> racers = new ArrayList<>();

    // the track loop: point 0 is the start/finish line
    private final List<Vector2> path = new ArrayList<>();
    private final float[] segmentStart;
    private final float[] segmentLength;
    private final float trackLength;
    // the corner bisector at each path point: a line through the point, halfway between the
    // direction coming in and going out. Stretch i runs from bisector i to bisector i+1, so
    // neighbouring stretches meet exactly and progress never jumps at a corner.
    private final float[] bisectorX;
    private final float[] bisectorY;

    /** true on a sprint: the path runs once from the start line to the finish line */
    private final boolean open;

    /**
     * @param map a map
     * @return a race manager for it (laps of a circuit, or a sprint from A to B)
     */
    public static RaceManager forMap(TrackMap map) {
        return new RaceManager(map.waypoints, map.pointToPoint);
    }

    /**
     * @param waypoints the race waypoints; the last one is the start/finish line
     */
    public RaceManager(List<Vector2> waypoints) {
        this(waypoints, false);
    }

    /**
     * @param waypoints the race waypoints
     * @param sprint false: a circuit, the last waypoint is the start/finish line;
     * true: start line first, finish line second to last (the last is the run-off)
     */
    public RaceManager(List<Vector2> waypoints, boolean sprint) {
        open = sprint;
        if (open) {
            for (int i = 0; i < waypoints.size() - 1; i++) {
                path.add(waypoints.get(i));
            }
        } else {
            path.add(waypoints.get(waypoints.size() - 1));
            for (int i = 0; i < waypoints.size() - 1; i++) {
                path.add(waypoints.get(i));
            }
        }
        int n = path.size();
        segmentStart = new float[n];
        segmentLength = new float[n];
        float total = 0f;
        for (int i = 0; i < n; i++) {
            segmentStart[i] = total;
            segmentLength[i] = open && i == n - 1 ? 0f : path.get(i).dst(path.get((i + 1) % n));
            total += segmentLength[i];
        }
        trackLength = total;
        bisectorX = new float[n];
        bisectorY = new float[n];
        for (int i = 0; i < n; i++) {
            Vector2 prev = path.get((i - 1 + n) % n), here = path.get(i), next = path.get((i + 1) % n);
            Vector2 in = new Vector2(here).sub(prev).nor();
            Vector2 out = new Vector2(next).sub(here).nor();
            Vector2 normal = in.add(out).nor();
            bisectorX[i] = normal.x;
            bisectorY[i] = normal.y;
        }
        // at the start the "bisector" is the painted finish line itself: straight across at y = 300
        bisectorX[0] = 0f;
        bisectorY[0] = 1f;
        if (open) {
            // a sprint's finish line is straight across the last stretch
            Vector2 d = new Vector2(path.get(n - 1)).sub(path.get(n - 2)).nor();
            bisectorX[n - 1] = d.x;
            bisectorY[n - 1] = d.y;
        }
    }

    /**
     * @return true on a sprint from A to B (no laps)
     */
    public boolean isSprint() {
        return open;
    }

    /** a sprint: where the car is along the course, and whether it's crossed the finish */
    private void updateSprint(RacerInfo info, Vector2 position, float raceTime, boolean first) {
        int n = path.size();
        int segments = n - 1;
        if (first) {
            // first sighting: simply the nearest stretch (the bisector test can match a stretch
            // far away for a car still behind the start line)
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < segments; i++) {
                float d = distanceToSegment(position, i);
                if (d < bestDist) {
                    bestDist = d;
                    info.segment = i;
                }
            }
        } else if (!info.isFinished()) {
            int from = Math.max(0, info.segment - 1);
            int count = Math.min(segments - from, 4);
            info.segment = Math.max(0, closestSegment(position, from, count));
        }
        info.position = new Vector2(position);
        if (info.isFinished()) {
            return;
        }
        info.lap = 0;
        info.lapCount = 0;
        // behind the start line counts as negative, so the starting grid is in order
        float behind = pastBisector(position, 0);
        info.progress = info.segment == 0 && behind < 0f ? behind : along(position, info.segment);
        if (info.segment == segments - 1 && pastBisector(position, n - 1) >= 0f) {
            info.finishTime = raceTime;
            info.lapCount = 1;
            info.progress = trackLength;
        }
    }

    /**
     * 0-arg constructor, uses the standard track
     */
    public RaceManager() {
        this(Waypoints.getWaypoints());
    }

    /**
     * Updates a racer from its position. Only the race host calls this.
     * @param name racer name
     * @param position car position
     * @param raceTime seconds since GO (used as the finish time)
     */
    public void updateRacer(String name, Vector2 position, float raceTime) {
        if (position == null) {
            return;
        }
        RacerInfo info = getRacerInfoByName(name);
        int n = path.size();
        if (open) {
            boolean first = info == null;
            if (first) {
                info = new RacerInfo(name);
                racers.add(info);
            }
            updateSprint(info, position, raceTime, first);
            return;
        }
        if (info == null) {
            info = new RacerInfo(name);
            racers.add(info);
            // first sighting: closest stretch of track anywhere. Cars lined up behind the
            // start line are at the very end of the loop, so they haven't started lap 1 yet.
            info.segment = closestSegment(position, 0, n);
            info.lap = along(position, info.segment) > trackLength / 2f ? -1 : 0;
        } else if (!info.isFinished()) {
            // only look at the stretch the car was on and its neighbours, so cutting a
            // corner or driving near another part of the track can't make it jump
            int from = info.segment - 1;
            int best = closestSegment(position, from, 4);
            int moved = best - info.segment;
            if (moved > n / 2) moved -= n;
            if (moved < -n / 2) moved += n;
            int unwrapped = info.segment + moved;
            if (unwrapped >= n) {
                info.lap++;      // crossed the start/finish line going forward
            } else if (unwrapped < 0) {
                info.lap--;      // backed over it
            }
            info.segment = best;
        }
        info.position = new Vector2(position);
        if (!info.isFinished()) {
            info.progress = info.lap * trackLength + along(position, info.segment);
            info.lapCount = Math.max(0, Math.min(laps, info.lap));
            if (info.lap >= laps) {
                info.finishTime = raceTime;
                info.progress = laps * trackLength;
            }
        }
    }

    /**
     * @return index of the track segment closest to p, among count segments starting at first
     */
    private int closestSegment(Vector2 p, int first, int count) {
        int n = path.size();
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        // first choice: stretches the car is actually between the bisectors of
        for (int pass = 0; pass < 2 && best < 0; pass++) {
            for (int k = 0; k < count; k++) {
                int i = ((first + k) % n + n) % n;
                if (open && i >= n - 1) {
                    continue;    // a sprint has no stretch from the finish back to the start
                }
                if (pass == 0 && !(pastBisector(p, i) >= 0f && pastBisector(p, (i + 1) % n) < 0f)) {
                    continue;
                }
                float d = distanceToSegment(p, i);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
        }
        return best;
    }

    /**
     * @return how far p is past the bisector line at path point i (negative = before it)
     */
    private float pastBisector(Vector2 p, int i) {
        Vector2 point = path.get(i);
        return (p.x - point.x) * bisectorX[i] + (p.y - point.y) * bisectorY[i];
    }

    private float distanceToSegment(Vector2 p, int i) {
        Vector2 a = path.get(i);
        Vector2 b = path.get((i + 1) % path.size());
        float t = projection(p, a, b);
        float x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t;
        return Vector2.dst(p.x, p.y, x, y);
    }

    /**
     * @return distance from the start line to p's spot on segment i
     */
    private float along(Vector2 p, int i) {
        // fraction of the way from this stretch's start bisector to its end bisector
        float fromStart = pastBisector(p, i);
        float toEnd = -pastBisector(p, (i + 1) % path.size());
        float t;
        if (fromStart >= 0f && toEnd >= 0f && fromStart + toEnd > 1e-4f) {
            t = fromStart / (fromStart + toEnd);
        } else {
            t = projection(p, path.get(i), path.get((i + 1) % path.size()));
        }
        return segmentStart[i] + Math.max(0f, Math.min(1f, t)) * segmentLength[i];
    }

    /**
     * @return how far along a-b the point p is, from 0 (at a) to 1 (at b)
     */
    private static float projection(Vector2 p, Vector2 a, Vector2 b) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-6f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2));
    }

    /**
     * Finished racers first, in the order they finished; then everyone else by distance driven.
     * @return racers in race order
     */
    /**
     * @param count laps in this race
     */
    public void setLaps(int count) {
        laps = open ? 1 : Math.max(1, count);
    }

    /**
     * @return laps in this race
     */
    public int getLaps() {
        return laps;
    }

    public List<RacerInfo> getSortedLeaderboard() {
        List<RacerInfo> sorted = new ArrayList<>(racers);
        Collections.sort(sorted, new Comparator<RacerInfo>() {
            @Override
            public int compare(RacerInfo r1, RacerInfo r2) {
                if (r1.isFinished() != r2.isFinished()) {
                    return r1.isFinished() ? -1 : 1;
                }
                if (r1.isFinished()) {
                    return Float.compare(r1.finishTime, r2.finishTime);
                }
                return Float.compare(r2.progress, r1.progress);
            }
        });
        return sorted;
    }

    /**
     * @return the leaderboard as packet entries, in race order
     */
    public List<Entry> toEntries() {
        List<Entry> entries = new ArrayList<>();
        for (RacerInfo r : getSortedLeaderboard()) {
            entries.add(new Entry(r.name, r.lapCount, r.progress, r.finishTime));
        }
        return entries;
    }

    /**
     * @return List of RacerInfo
     */
    public List<RacerInfo> getRacers() {
        return racers;
    }

    /**
     * @return length of one lap
     */
    public float getTrackLength() {
        return trackLength;
    }

    /**
     * @param name RacerInfo with given name
     * @return RacerInfo with given name, or null
     */
    public RacerInfo getRacerInfoByName(String name) {
        for (RacerInfo r : racers) {
            if (r.name.equals(name)) {
                return r;
            }
        }
        return null;
    }

    /**
     * @param name racer
     * @return laps completed by that racer
     */
    public int getLapCount(String name) {
        RacerInfo info = getRacerInfoByName(name);
        return info != null ? info.lapCount : 0;
    }

    /**
     * How far a racer is from the stretch of track they're supposed to be on. Large when
     * they're out on the grass, or when they cut across and skipped part of the track
     * (progress only counts once they go back to where they left it).
     * @param name racer
     * @return distance in track pixels, 0 if unknown
     */
    public float distanceFromTrack(String name) {
        RacerInfo info = getRacerInfoByName(name);
        if (info == null || info.position == null || info.segment < 0) {
            return 0f;
        }
        return distanceToSegment(info.position, info.segment);
    }

    /**
     * @param name racer
     * @return true if that racer has finished
     */
    public boolean isFinished(String name) {
        RacerInfo info = getRacerInfoByName(name);
        return info != null && info.isFinished();
    }

    /**
     * Copies the race host's leaderboard into this (player-side) RaceManager.
     * @param packet leaderboard from the host
     */
    public void updateFromPacket(LeaderboardPacket packet) {
        racers.clear();
        for (Entry entry : packet.entries) {
            RacerInfo info = new RacerInfo(entry.name);
            info.lapCount = entry.lapCount;
            info.progress = entry.progress;
            info.finishTime = entry.finishTime;
            racers.add(info);
        }
    }
}
