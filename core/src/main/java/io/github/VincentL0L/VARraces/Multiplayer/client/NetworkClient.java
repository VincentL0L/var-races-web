package io.github.VincentL0L.VARraces.Multiplayer.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.packets.Entry;
import io.github.VincentL0L.VARraces.Multiplayer.packets.LeaderboardPacket;
import io.github.VincentL0L.VARraces.Multiplayer.packets.PositionPacket;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Opponent;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

/**
 * Network Client is the game's link to a race. It works in two modes:
 *
 * single player: the race runs inside the game, one player against 3 CPUs
 * (the same logic RaceServer uses, ticked by update()).
 *
 * online: connects to RaceServer over a WebSocket, joins a room and
 * receives the other players, CPUs, countdown and leaderboard from it.
 *
 * Screens use the same methods in both modes. update(delta) must be called every frame.
 *
 * Messages are plain text with fields separated by '|', see RaceServer for the list.
 */
public class NetworkClient {
    private static final float UPDATE_INTERVAL = 0.05f;
    private static final float LEADERBOARD_INTERVAL = 0.5f;
    private static final float WAYPOINT_THRESHOLD = 150f;

    private String playerId;
    private final Map<String, PositionPacket> opponents = new HashMap<>();
    private final Map<String, Integer> opponentCars = new HashMap<>();
    private final Map<String, Boolean> playerReadyStates = new LinkedHashMap<>();
    private boolean connected = false;
    private String countdownText = "";
    private LeaderboardPacket latestLeaderboard;
    public RaceManager raceManager;
    private boolean isReady = false;

    // online mode
    private GameSocket socket;
    private final List<String> inbox = new ArrayList<>();
    private boolean socketOpen = false;
    private boolean socketClosed = false;
    private String roomCode;
    private boolean roomPublic;
    private String error;
    private final List<String[]> publicRooms = new ArrayList<>();
    private float sendTimer = 0f;

    // single player race, the same state RaceServer keeps for each room
    private final List<Vector2> waypoints = Waypoints.getWaypoints();
    private final RaceManager serverRaceManager = new RaceManager();
    private final List<Opponent> cpuOpponents = new ArrayList<>();
    private final Vector2 playerPos = new Vector2(200, 300);
    private int playerWaypointIndex = 0;
    private int playerLapCount = 0;
    private boolean gameStarted = false;
    private boolean countdownInProgress = false;
    private float countdownTimer = 0f;
    private float updateTimer = 0f;
    private float leaderboardTimer = 0f;

    /**
     * Constructor for NetworkClient.
     * @param playerId passed in as null, and then set when a race is joined
     */
    public NetworkClient(String playerId) {
        this.playerId = playerId;
    }

    /**
     * @return true when connected to the online server instead of racing locally
     */
    public boolean isOnline() {
        return socket != null;
    }

    /**
     * starts a single player race: assigns the player id and spawns the CPU opponents
     * @param host unused, kept so callers match the desktop version
     */
    public void start(String host) {
        if (playerId == null) {
            playerId = "Player 1";
        }
        playerReadyStates.put(playerId, false);

        List<Vector2> grid = Waypoints.getCpuGrid();
        for (int i = 0; i < grid.size(); i++) {
            Vector2 cpuPos = grid.get(i);
            Opponent cpu = new Opponent("CPU" + (i + 1), waypoints, cpuPos);
            cpuOpponents.add(cpu);

            PositionPacket packet = new PositionPacket();
            packet.playerId = cpu.getName();
            packet.x = cpuPos.x;
            packet.y = cpuPos.y;
            packet.rotation = 90;
            opponents.put(packet.playerId, packet);
        }
        connected = true;
    }

    /**
     * connects to the online race server
     * @param factory opens a WebSocket on this platform
     * @param url server address, ex "ws://localhost:8080"
     */
    public void connect(GameSocket.Factory factory, String url) {
        socket = factory.create(url, new GameSocket.Listener() {
            public void onOpen() {
                synchronized (inbox) {
                    socketOpen = true;
                }
            }

            public void onMessage(String message) {
                synchronized (inbox) {
                    inbox.add(message);
                }
            }

            public void onClose() {
                synchronized (inbox) {
                    socketClosed = true;
                }
            }
        });
    }

    /**
     * asks the server for the list of public rooms
     */
    public void requestRooms() {
        send("LIST");
    }

    /**
     * creates a new room on the server and joins it
     * @param isPublic true if it should be listed for everyone
     * @param car selected car skin
     */
    public void createRoom(boolean isPublic, int car) {
        send("CREATE|" + (isPublic ? "public" : "private") + "|" + car);
    }

    /**
     * joins a room using its code
     * @param code 4 letter room code
     * @param car selected car skin
     */
    public void joinRoom(String code, int car) {
        send("JOIN|" + code.trim().toUpperCase() + "|" + car);
    }

    /**
     * Advances the race. Must be called once per frame by the current screen.
     * Online it handles messages from the server, otherwise it runs the local race.
     * @param delta time since last frame
     */
    public void update(float delta) {
        if (isOnline()) {
            readMessages();
            sendTimer += delta;
            return;
        }
        if (!connected) return;

        if (countdownInProgress) {
            countdownTimer += delta;
            if (countdownTimer >= 3f) {
                countdownText = "GO!";
                gameStarted = true;
                countdownInProgress = false;
            } else {
                countdownText = String.valueOf(3 - (int) countdownTimer);
            }
        }

        updateTimer += delta;
        if (updateTimer < UPDATE_INTERVAL) return;
        float step = updateTimer;
        updateTimer = 0f;

        for (Opponent cpu : cpuOpponents) {
            if (gameStarted) {
                cpu.update(step, gameStarted);
                serverRaceManager.updateRacer(cpu.getName(), cpu.getLapCount(),
                    cpu.getCurrentWaypointIndex(), cpu.getPosition());
            }
            PositionPacket packet = new PositionPacket();
            packet.playerId = cpu.getName();
            packet.x = cpu.getPosition().x;
            packet.y = cpu.getPosition().y;
            packet.rotation = cpu.getRotation();
            opponents.put(packet.playerId, packet);
        }

        if (gameStarted) {
            Vector2 target = waypoints.get(playerWaypointIndex);
            if (target.dst(playerPos) < WAYPOINT_THRESHOLD) {
                playerWaypointIndex++;
                if (playerWaypointIndex >= waypoints.size()) {
                    playerWaypointIndex = 0;
                    playerLapCount++;
                }
            }
            serverRaceManager.updateRacer(playerId, playerLapCount, playerWaypointIndex, playerPos);
        }

        leaderboardTimer += step;
        if (leaderboardTimer > LEADERBOARD_INTERVAL) {
            leaderboardTimer = 0f;
            List<Entry> entries = new ArrayList<>();
            for (RacerInfo r : serverRaceManager.getSortedLeaderboard()) {
                entries.add(new Entry(r.name, r.lapCount, r.distanceToNextWaypoint, r.currentWaypointIndex));
            }
            setLeaderboard(new LeaderboardPacket(entries));
        }
    }

    /**
     * handles every message that arrived from the server since last frame
     */
    private void readMessages() {
        List<String> messages;
        synchronized (inbox) {
            connected = socketOpen && !socketClosed;
            if (socketClosed && error == null) {
                error = "Lost connection to the race server";
            }
            messages = new ArrayList<>(inbox);
            inbox.clear();
        }
        for (String message : messages) {
            handleMessage(message.split("\\|", -1));
        }
    }

    /**
     * reads one message from the server and updates the race
     * @param parts message split on '|'
     */
    private void handleMessage(String[] parts) {
        String type = parts[0];
        if (type.equals("ROOMS")) {
            publicRooms.clear();
            for (int i = 1; i + 1 < parts.length; i += 2) {
                publicRooms.add(new String[] {parts[i], parts[i + 1]});
            }
        } else if (type.equals("JOINED")) {
            roomCode = parts[1];
            roomPublic = parts[2].equals("public");
            playerId = parts[3];
            playerReadyStates.put(playerId, false);
            error = null;
        } else if (type.equals("ERROR")) {
            error = parts[1];
        } else if (type.equals("READY")) {
            playerReadyStates.put(parts[1], parts[2].equals("true"));
        } else if (type.equals("LEFT")) {
            playerReadyStates.remove(parts[1]);
            opponents.remove(parts[1]);
        } else if (type.equals("COUNTDOWN")) {
            countdownText = parts[1];
        } else if (type.equals("POS")) {
            if (parts[1].equals(playerId)) return;
            PositionPacket pp = new PositionPacket();
            pp.playerId = parts[1];
            pp.x = Float.parseFloat(parts[2]);
            pp.y = Float.parseFloat(parts[3]);
            pp.rotation = Float.parseFloat(parts[4]);
            opponents.put(pp.playerId, pp);
            opponentCars.put(pp.playerId, Integer.parseInt(parts[5]));
        } else if (type.equals("LEADER")) {
            List<Entry> entries = new ArrayList<>();
            for (int i = 1; i + 3 < parts.length; i += 4) {
                entries.add(new Entry(parts[i], Integer.parseInt(parts[i + 1]),
                    Float.parseFloat(parts[i + 3]), Integer.parseInt(parts[i + 2])));
            }
            setLeaderboard(new LeaderboardPacket(entries));
        }
    }

    /**
     * stores the newest leaderboard and copies it into the screen's RaceManager
     * @param packet leaderboard
     */
    private void setLeaderboard(LeaderboardPacket packet) {
        latestLeaderboard = packet;
        if (raceManager != null) {
            raceManager.updateFromPacket(packet);
        }
    }

    /**
     * sends a message to the server if connected
     * @param message text message
     */
    private void send(String message) {
        if (socket != null && connected) {
            socket.send(message);
        }
    }

    /**
     * leaves the race (and disconnects when online)
     */
    public void stop() {
        if (socket != null) {
            socket.close();
        }
        connected = false;
        opponents.clear();
        countdownText = "";
        latestLeaderboard = null;
    }
    /**
     * Need to update local raceManager with leaderboard results
     * so Game Screen and Network Client share raceManager
     * @param raceManager
     */
    public void setRaceManager(RaceManager raceManager) {
        this.raceManager = raceManager;
    }
    /**
     * @return current countdown text: "3" "2" "1" "GO!" or ""
     */
    public String getCountdownText() {
        return countdownText;
    }
    /**
     * Sends the position and rotation of the player (20 times a second online)
     * @param x x-cord of player
     * @param y y-cord of player
     * @param rotation rotation of player image
     */
    public void sendPos(float x, float y, float rotation) {
        if (!connected || playerId == null) return;
        playerPos.set(x, y);
        if (isOnline() && sendTimer >= UPDATE_INTERVAL) {
            sendTimer = 0f;
            send("POS|" + x + "|" + y + "|" + rotation);
        }
    }
    /**
     * @return opponents in NetworkClient
     */
    public Map<String, PositionPacket> getOpponents() {
        return opponents;
    }
    /**
     * @param id opponent id
     * @return car skin (1-3) that opponent picked, 1 for CPUs
     */
    public int getOpponentCar(String id) {
        Integer car = opponentCars.get(id);
        return car == null ? 1 : car;
    }
    /**
     * @return playerID in NetworkClient
     */
    public String getPlayerId() {
        return playerId;
    }
    /**
     * @return boolean connected status
     */
    public boolean isConnected() {
        return connected;
    }
    /**
     * @return true once the server has put this player in a room
     */
    public boolean isInRoom() {
        return roomCode != null;
    }
    /**
     * @return code of the joined room, null in single player
     */
    public String getRoomCode() {
        return roomCode;
    }
    /**
     * @return true if the joined room is listed publicly
     */
    public boolean isRoomPublic() {
        return roomPublic;
    }
    /**
     * @return public rooms as {code, player count}
     */
    public List<String[]> getPublicRooms() {
        return publicRooms;
    }
    /**
     * @return last error from the server, or null
     */
    public String getError() {
        return error;
    }
    /**
     * Marks the player ready. Single player starts the countdown right away,
     * online the server starts it once everyone in the room is ready.
     * @param ready new ready value of this client
     */
    public void setReady(boolean ready) {
        if (isReady == ready) return;
        isReady = ready;
        playerReadyStates.put(playerId, ready);
        if (isOnline()) {
            send("READY|" + ready);
        } else if (ready && !gameStarted && !countdownInProgress) {
            countdownInProgress = true;
            countdownTimer = 0f;
            countdownText = "3";
        }
    }
    /**
     * @return ready status of client
     */
    public boolean isReady() {
        return isReady;
    }
    /**
     * @return Latest leaderboard
     */
    public LeaderboardPacket getLatestLeaderboard() {
        return latestLeaderboard;
    }
    /**
     * @param playerId ID of client whose ready status will be returned
     * @return ready status of client with playerID
     */
    public boolean isPlayerReady(String playerId) {
        Boolean ready = playerReadyStates.get(playerId);
        return ready != null && ready;
    }
    /**
     * @return Map of every player in the race and if they are ready
     */
    public Map<String, Boolean> getPlayerReadyStates() {
        return playerReadyStates;
    }
}
