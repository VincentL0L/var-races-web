package io.github.VincentL0L.VARraces.Multiplayer.packets;
/**
 * Countdown Packet that is sent once all players are ready
 * "3" "2" "1" "GO!"
 */
public class CountdownPacket {
    public String text;

    /**
     * Required 0-arg constructor for kryonet
     * 
     */
    public CountdownPacket() {}
    /**
     * Constructor for CountdownPacket
     * Packet sent from Server to Client
     * @param text "3" "2" "1" "GO!"
     */
    public CountdownPacket(String text) {
        this.text = text;
    }
}
