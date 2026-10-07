package io.github.VincentL0L.VARraces.Multiplayer.packets;

/**
 * Network clients send ready packet to server
 */
public class ReadyPacket {
    public String playerId;
    public boolean isReady;
    /**
     * Required 0-arg constructor for kryonet
     */
    public ReadyPacket() {}
    /**
     * Creates ready packet 
     * @param playerId playerID for this ReadyPacket
     * @param isReady status of readiness of playerID
     */
    public ReadyPacket(String playerId, boolean isReady) {
        this.playerId = playerId;
        this.isReady = isReady;
    }
} 