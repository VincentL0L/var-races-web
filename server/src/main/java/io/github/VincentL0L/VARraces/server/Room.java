package io.github.VincentL0L.VARraces.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.java_websocket.WebSocket;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Opponent;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CpuTraffic;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

/**
 * One race on the server: its players, 3 CPU opponents, countdown and leaderboard.
 * This is the old GameServer logic, but one copy per room.
 */
public class Room {
    public static final int MAX_PLAYERS = 6;

    private final String code;
    private final boolean isPublic;
    private final Map<WebSocket, PlayerState> players = new LinkedHashMap<>();
    private final List<Opponent> cpuOpponents = new ArrayList<>();
    private final CpuTraffic traffic;
    private final TrackMap map;
    private final List<Vector2> waypoints;
    private final RaceManager raceManager;
    private int nextPlayerNumber = 1;
    private boolean countdownInProgress = false;
    private boolean started = false;
    private float countdownTimer = 0f;
    private String lastCountdown = "";
    private float raceTime = 0f;

    /**
     * creates a room with 3 CPU opponents on the starting line
     * @param code 4 letter code players join with
     * @param isPublic true if it shows in the public room list
     * @param map the map this room races on
     */
    public Room(String code, boolean isPublic, TrackMap map) {
        this.code = code;
        this.isPublic = isPublic;
        this.map = map;
        waypoints = map.waypoints;
        raceManager = new RaceManager(waypoints);
        List<Vector2> grid = Waypoints.getCpuGrid();
        for (int i = 0; i < grid.size(); i++) {
            cpuOpponents.add(new Opponent("CPU" + (i + 1), waypoints, grid.get(i)));
        }
        traffic = new CpuTraffic(cpuOpponents, ImageTrack.forMap(map));
    }

    /**
     * adds a player and tells everyone in the room
     * @param conn player's connection
     * @param car car skin the player picked
     */
    public void join(WebSocket conn, int car) {
        String id = "Player " + nextPlayerNumber++;
        Vector2 spawn = new Vector2(200 + (players.size()) * 40, 300);
        PlayerState state = new PlayerState(id, car, spawn);

        conn.send("JOINED|" + code + "|" + (isPublic ? "public" : "private") + "|" + id + "|" + map.id);
        for (PlayerState other : players.values()) {
            conn.send("READY|" + other.id + "|" + other.ready);
        }
        players.put(conn, state);
        broadcast("READY|" + id + "|false");
    }

    /**
     * removes a player who disconnected
     * @param conn player's connection
     */
    public void leave(WebSocket conn) {
        PlayerState state = players.remove(conn);
        if (state != null) {
            traffic.removePlayer(state.id);
            broadcast("LEFT|" + state.id);
            checkAllReady();
        }
    }

    /**
     * handles a message from a player in this room
     * @param conn player's connection
     * @param parts message split on '|'
     */
    public void receive(WebSocket conn, String[] parts) {
        PlayerState state = players.get(conn);
        if (state == null) return;

        if (parts[0].equals("READY")) {
            state.ready = parts[1].equals("true");
            broadcast("READY|" + state.id + "|" + state.ready);
            checkAllReady();
        } else if (parts[0].equals("POS") && parts.length >= 4) {
            try {
                state.position.set(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                state.rotation = Float.parseFloat(parts[3]);
            } catch (NumberFormatException e) {
                // ignore bad position
            }
        }
    }

    /**
     * starts the countdown once every player is ready
     */
    private void checkAllReady() {
        if (started || countdownInProgress || players.isEmpty()) return;
        for (PlayerState p : players.values()) {
            if (!p.ready) return;
        }
        // everyone sees the red flag wave at the same time, then the countdown starts
        countdownInProgress = true;
        countdownTimer = -RaceManager.FLAG_TIME;
        broadcast("FLAG");
    }

    /**
     * moves CPUs, tracks laps, runs the countdown and sends updates to every player
     * @param delta seconds since last tick
     */
    public void tick(float delta) {
        if (countdownInProgress) {
            countdownTimer += delta;
            String text;
            if (countdownTimer >= 3f) {
                text = "GO!";
                started = true;
                countdownInProgress = false;
            } else if (countdownTimer < 0f) {
                text = "";
            } else {
                text = String.valueOf(3 - (int) countdownTimer);
            }
            if (!text.isEmpty() && !text.equals(lastCountdown)) {
                lastCountdown = text;
                broadcast("COUNTDOWN|" + text);
            }
        }

        // all CPUs move together so they avoid and bump each other and the players
        for (PlayerState p : players.values()) {
            traffic.setPlayer(p.id, p.position.x, p.position.y, p.rotation + 90f);
        }
        traffic.update(delta, started);
        if (started) {
            raceTime += delta;
        }
        // standings start once everyone is on the grid (the countdown has begun)
        boolean onGrid = started || (countdownInProgress && countdownTimer >= 0f);
        for (Opponent cpu : cpuOpponents) {
            if (onGrid) {
                raceManager.updateRacer(cpu.getName(), cpu.getPosition(), raceTime);
            }
            broadcast("POS|" + cpu.getName() + "|" + cpu.getPosition().x + "|"
                + cpu.getPosition().y + "|" + cpu.getRotation() + "|1");
        }

        for (PlayerState p : players.values()) {
            if (onGrid) {
                raceManager.updateRacer(p.id, p.position, raceTime);
            }
            broadcast("POS|" + p.id + "|" + p.position.x + "|" + p.position.y + "|"
                + p.rotation + "|" + p.car);
        }

        // leaderboard every tick: LEADER|name|laps|progress|finishTime|...
        if (onGrid) {
            StringBuilder msg = new StringBuilder("LEADER");
            for (RacerInfo r : raceManager.getSortedLeaderboard()) {
                msg.append('|').append(r.name).append('|').append(r.lapCount).append('|')
                    .append(r.progress).append('|').append(r.finishTime);
            }
            broadcast(msg.toString());
        }
    }

    /**
     * sends a message to every player in the room
     * @param message text to send
     */
    private void broadcast(String message) {
        for (WebSocket conn : players.keySet()) {
            if (conn.isOpen()) {
                conn.send(message);
            }
        }
    }

    /**
     * @return the map this room races on
     */
    public TrackMap getMap() {
        return map;
    }

    /**
     * @return true if new players can still join
     */
    public boolean isJoinable() {
        return !started && !countdownInProgress && players.size() < MAX_PLAYERS;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    public int getPlayerCount() {
        return players.size();
    }

    public String getCode() {
        return code;
    }

    /**
     * one human player in the room
     */
    private static class PlayerState {
        String id;
        int car;
        Vector2 position;
        float rotation = 0f;
        boolean ready = false;

        PlayerState(String id, int car, Vector2 spawn) {
            this.id = id;
            this.car = car;
            this.position = spawn.cpy();
        }
    }
}
