package io.github.VincentL0L.VARraces.Multiplayer.server.cpu;

import com.badlogic.gdx.math.Vector2;

/**
 * One racer's standing in the race
 */
public class RacerInfo {
    public String name;
    /** laps completed (0 until the car first finishes a lap) */
    public int lapCount;
    /** distance driven along the track since the start line, including whole laps */
    public float progress;
    /** race time when the car crossed the finish line, or -1 if still racing */
    public float finishTime = -1f;
    public Vector2 position;

    // where the car is on the track loop (only used where the race is run)
    int segment = -1;
    int lap = 0;

    /**
     * @param name racer's name, ex "CPU1" or "Player 1"
     */
    public RacerInfo(String name) {
        this.name = name;
    }

    /**
     * @return true once this racer has finished the race
     */
    public boolean isFinished() {
        return finishTime >= 0f;
    }
}
