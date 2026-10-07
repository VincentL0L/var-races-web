package io.github.VincentL0L.VARraces.Multiplayer.packets;

/**
 * Network client sends its position to game server through PositionPacket
 */
public class PositionPacket {
    public String playerId;
    public float x;
    public float y;
    public float rotation;
}
