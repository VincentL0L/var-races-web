package io.github.VincentL0L.VARraces.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.java_websocket.WebSocket;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Opponent;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

/**
 * One race on the server: its players, 3 CPU opponents, countdown and leaderboard.
 * This is the old GameServer logic, but one copy per room.
 */
public class Room {
    public static final int MAX_PLAYERS = 6;
    private static final float WAYPOINT_THRESHOLD = 150f;
    private static final float LEADERBOARD_INTERVAL = 0.5f;

    private final String code;
    private final boolean isPublic;
    private final Map<WebSocket, PlayerState> players = new LinkedHashMap<>();
    private final List<Opponent> cpuOpponents = new ArrayList<>();
    private final List<Vector2> waypoints = Waypoints.getWaypoints();
    private final RaceManager raceManager = new RaceManager();
    private int nextPlayerNumber = 1;
    private boolean countdownInProgress = false;
    private boolean started = false;
    private float countdownTimer = 0f;
    private String lastCountdown = "";
    private float leaderboardTimer = 0f;

    /**
     * creates a room with 3 CPU opponents on the starting line
     * @param code 4 letter code players join with
     * @param isPublic true if it shows in the public room list
     */
    public Room(String code, boolean isPublic) {
        this.code = code;
        this.isPublic = isPublic;
        for (int i = 0; i < 3; i++) {
            Vector2 cpuPos = new Vector2(200 + i * 40, 300);
            cpuOpponents.add(new Opponent("CPU" + (i + 1), waypoints, cpuPos));
        }
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

        conn.send("JOINED|" + code + "|" + (isPublic ? "public" : "private") + "|" + id);
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
        countdownInProgress = true;
        countdownTimer = 0f;
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
            } else {
                text = String.valueOf(3 - (int) countdownTimer);
            }
            if (!text.equals(lastCountdown)) {
                lastCountdown = text;
                broadcast("COUNTDOWN|" + text);
            }
        }

        for (Opponent cpu : cpuOpponents) {
            if (started) {
                cpu.update(delta, true);
                raceManager.updateRacer(cpu.getName(), cpu.getLapCount(),
                    cpu.getCurrentWaypointIndex(), cpu.getPosition());
            }
            broadcast("POS|" + cpu.getName() + "|" + cpu.getPosition().x + "|"
                + cpu.getPosition().y + "|" + cpu.getRotation() + "|1");
        }

        for (PlayerState p : players.values()) {
            if (started) {
                p.update(waypoints);
                raceManager.updateRacer(p.id, p.lapCount, p.waypointIndex, p.position);
            }
            broadcast("POS|" + p.id + "|" + p.position.x + "|" + p.position.y + "|"
                + p.rotation + "|" + p.car);
        }

        leaderboardTimer += delta;
        if (started && leaderboardTimer > LEADERBOARD_INTERVAL) {
            leaderboardTimer = 0f;
            StringBuilder msg = new StringBuilder("LEADER");
            for (RacerInfo r : raceManager.getSortedLeaderboard()) {
                msg.append('|').append(r.name).append('|').append(r.lapCount).append('|')
                    .append(r.currentWaypointIndex).append('|').append(r.distanceToNextWaypoint);
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
        int waypointIndex = 0;
        int lapCount = 0;
        boolean ready = false;

        PlayerState(String id, int car, Vector2 spawn) {
            this.id = id;
            this.car = car;
            this.position = spawn.cpy();
        }

        /**
         * moves on to the next waypoint when close enough, counting laps
         * @param waypoints track waypoints
         */
        void update(List<Vector2> waypoints) {
            if (waypoints.get(waypointIndex).dst(position) < WAYPOINT_THRESHOLD) {
                waypointIndex++;
                if (waypointIndex >= waypoints.size()) {
                    waypointIndex = 0;
                    lapCount++;
                }
            }
        }
    }
}
