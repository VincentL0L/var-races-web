package io.github.VincentL0L.VARraces.Multiplayer.packets;

import java.util.List;

/**
 * sent from game server to each network client to update their local leaderboards
 */
public class LeaderboardPacket {
    public List<Entry> entries;
    /**
     * Required 0-arg constructor for kryonet
     */
    public LeaderboardPacket() {} // Required for KryoNet serialization
    /**
     * Constructor for Leaderboard Packet
     * @param entries Entries passed in 
     */
    public LeaderboardPacket(List<Entry> entries) {
        this.entries = entries;
    }
}
