package io.github.VincentL0L.VARraces.Multiplayer.packets;

/**
 * one row of the leaderboard, sent from the race host to each player
 */
public class Entry {
    public String name;
    public int lapCount;
    public float progress;
    public float finishTime;

    public Entry() {}

    /**
     * @param name racer name
     * @param lapCount laps completed
     * @param progress distance along the track (including whole laps)
     * @param finishTime finish time in seconds, or -1 if still racing
     */
    public Entry(String name, int lapCount, float progress, float finishTime) {
        this.name = name;
        this.lapCount = lapCount;
        this.progress = progress;
        this.finishTime = finishTime;
    }
}
