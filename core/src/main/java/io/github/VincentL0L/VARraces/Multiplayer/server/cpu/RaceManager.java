package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.packets.LeaderboardPacket;
import io.github.VincentL0L.VARraces.Multiplayer.packets.Entry;

public class RaceManager {

    

    public final List<RacerInfo> racers = new ArrayList<>();
    private final List<Vector2> waypoints;
    /**
     * 1-Arg consturctor for RaceManager
     * Sets Waypoint field to given parameter
     * @param waypoints
     */
    public RaceManager(List<Vector2> waypoints) {
        this.waypoints = waypoints;
    }
    /**
     * 0-arg constructor
     */
    public RaceManager() {
        this(Waypoints.getWaypoints());
    }
    /**
     * Update each racer information every frame. 
     * Updates RacerInfo position with given params.
     * Calculates distance to next waypoint
     * Also can add new racer if RacerInfo for given name does not exist
     * @param name
     * @param lapCount
     * @param currentWaypointIndex
     * @param position
     */
    public void updateRacer(String name, int lapCount, int currentWaypointIndex, Vector2 position) {
        if (position == null) {
            return;
        }

        int currentIdx = currentWaypointIndex % waypoints.size();
        Vector2 currentWaypoint = waypoints.get(currentIdx);
        

        float distanceToNext = position.dst(currentWaypoint);



        RacerInfo info = null;
        for (RacerInfo r : racers) {
            if (r.name.equals(name)) {
                info = r;
                break;
            }
        }

        if (info == null) {
            info = new RacerInfo(name, lapCount, currentWaypointIndex, distanceToNext, new Vector2(position));
            racers.add(info);
        } else {
            info.lapCount = lapCount;
            info.currentWaypointIndex = currentWaypointIndex;
            info.distanceToNextWaypoint = distanceToNext;
            info.position = new Vector2(position);
        }
    }
    /**
     * Sorts RacerInfo list by custom comparator
     * Order of Importance (Greatest to Least): LapCount, Cur Waypoint index, Dist. to. nxt Waypoint. 
     * @return sorted List of RacerInfo given by custom comparator
     */
    public List<RacerInfo> getSortedLeaderboard() {
        List<RacerInfo> sorted = new ArrayList<>(racers);

        Collections.sort(sorted, new Comparator<RacerInfo>() {
            @Override
            public int compare(RacerInfo r1, RacerInfo r2) {
                if (r1.lapCount != r2.lapCount) {
                    return Integer.compare(r2.lapCount, r1.lapCount);
                }
                
                if (r1.currentWaypointIndex != r2.currentWaypointIndex) {
                    return Integer.compare(r2.currentWaypointIndex, r1.currentWaypointIndex);
                }
                
                return Float.compare(r1.distanceToNextWaypoint, r2.distanceToNextWaypoint);
            }
        });

        return sorted;
    }
    /**
     * @return List of RacerInfo
     */
    public List<RacerInfo> getRacers() {
        return racers;
    }
    /**
     * Uses previous position and current position to see 
     * if finish line has been crossed. 
     * @param prevPos Previous Position (last frame)
     * @param currentPos Current Position (current frame)
     * @return boolean status if finish line has been crossed
     */
    public boolean crossedFinishLine(Vector2 prevPos, Vector2 currentPos) {
        boolean crossedForward = (prevPos.y < 300 && currentPos.y > 300);
        boolean inXRange = (currentPos.x >= 135 && currentPos.x <= 280);
        return crossedForward && inXRange;
    }
    /**
     * Uses previous position and current position to see 
     * if finish line has been crossed already, and crossed again by going backwards
     * @param prevPos Previous Position (last frame)
     * @param currentPos Current Position (current frame)
     * @return boolean status if finish line has been crossed
     */
    public boolean crossedFinishLineBackwards(Vector2 prevPos, Vector2 currentPos) {
        boolean crossedBack = (prevPos.y > 300 && currentPos.y < 300);
        boolean inXRange = (currentPos.x >= 135 && currentPos.x <= 280);
        return crossedBack && inXRange;
    }
    /**
     * Loops through RacerInfo to find matching name
     * @param name RacerInfo with given name
     * @return RacerInfo with given name
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
     * @param name RacerInfo with given name
     * @return LapCount of racer with given name
     */
    public int getLapCount(String name) {
        RacerInfo info = getRacerInfoByName(name);
        return info != null ? info.lapCount : 0;
    }
    /**
     * @param name RacerInfo to find with given name
     * @return CurrentwayPointIndex of racer with given name
     */
    public int getCurrentWaypointIndex(String name) {
        RacerInfo info = getRacerInfoByName(name);
        return (info != null? info.currentWaypointIndex : 0);
    }
    /**
     * This method is solely used for local version of RaceManager
     * Updates client-side RaceManager with leaderboard packet send from server
     * @param packet leaderboard packet sent from server
     */
    public void updateFromPacket(LeaderboardPacket packet) {
        racers.clear();
        for (Entry entry : packet.entries) {
            RacerInfo info = new RacerInfo(
                entry.name,
                entry.lapCount,
                entry.currentWaypointIndex,
                entry.distanceToNextWaypoint,
                null
            );
            racers.add(info);
        }
    }
}