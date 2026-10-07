package io.github.VincentL0L.VARraces.Multiplayer.packets;

/**
 * sent from network client to game server for game server to update server leaderboard
 */
public class Entry {
    public String name;
    public int lapCount;
    public float distanceToNextWaypoint;
    public int currentWaypointIndex;
    /**
     * Required 0-arg constructor for kryonet
     */
    public Entry() {}
    /**
     * Constructor for Entry
     * @param name Name/String to show on Leaderboard GUI
     * @param lapCount lapCount of this Player
     * @param distanceToNextWaypoint Distance to nextwayPoint of this player
     * @param currentWaypointIndex Current Waypoint index of this player
     */
    public Entry(String name, int lapCount, float distanceToNextWaypoint, int currentWaypointIndex) {
        this.name = name;
        this.lapCount = lapCount;
        this.distanceToNextWaypoint = distanceToNextWaypoint;
        this.currentWaypointIndex = currentWaypointIndex;
    }
}