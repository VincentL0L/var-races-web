package io.github.VincentL0L.VARraces.Multiplayer.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.packets.Entry;
import io.github.VincentL0L.VARraces.Multiplayer.packets.LeaderboardPacket;
import io.github.VincentL0L.VARraces.Multiplayer.packets.PositionPacket;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarBody;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CpuTraffic;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Opponent;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
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
    /** online cars are drawn this far in the past so there's always an update to slide toward */
    private static final float INTERPOLATION_DELAY = 0.1f;

    private String playerId;
    private final Map<String, PositionPacket> opponents = new HashMap<>();
    private final Map<String, Integer> opponentCars = new HashMap<>();
    private final Map<String, Boolean> playerReadyStates = new LinkedHashMap<>();
    private boolean connected = false;
    private String countdownText = "";
    private boolean flagShown = false;
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

    // items (see ItemSystem): single player runs them here, online the server does
    private ItemSystem items;
    private ItemSystem.Item heldItem;
    /** what's on the track, as ItemSystem.describe() text */
    private String itemView = "";
    /** things that happened to our own car: BOOST, SPIN, SLOW, BLOCK */
    private final List<String> myEffects = new ArrayList<>();
    private final Vector2 lastPlayerPos = new Vector2(200, 300);
    private float playerSpeed = 0f;

    /** the map being raced: picked for single player, sent by the server online */
    private TrackMap map = TrackMap.get(TrackMap.CLASSIC);

    // single player race, the same state RaceServer keeps for each room
    private RaceManager serverRaceManager;
    private final List<Opponent> cpuOpponents = new ArrayList<>();
    private final Vector2 playerPos = new Vector2(200, 300);
    private float playerHeading = 90f;
    private CpuTraffic traffic;
    private PixmapTrack track;
    private float raceTime = 0f;
    private float clock = 0f;
    private final Map<String, PoseHistory> poseHistory = new HashMap<>();
    private boolean gameStarted = false;
    private boolean countdownInProgress = false;
    private float countdownTimer = 0f;

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
     * @param raceMap the map to race on
     */
    public void start(TrackMap raceMap) {
        map = raceMap;
        if (playerId == null) {
            playerId = "Player 1";
        }
        playerReadyStates.put(playerId, false);
        serverRaceManager = new RaceManager(map.waypoints);

        List<Vector2> grid = Waypoints.getCpuGrid();
        for (int i = 0; i < grid.size(); i++) {
            Vector2 cpuPos = grid.get(i);
            Opponent cpu = new Opponent("CPU" + (i + 1), map.waypoints, cpuPos);
            cpuOpponents.add(cpu);

            PositionPacket packet = new PositionPacket();
            packet.playerId = cpu.getName();
            packet.x = cpuPos.x;
            packet.y = cpuPos.y;
            packet.rotation = 90;
            opponents.put(packet.playerId, packet);
        }
        track = new PixmapTrack(map.roadMask);
        items = new ItemSystem(map.waypoints);
        traffic = new CpuTraffic(cpuOpponents, track);
        connected = true;
    }

    /**
     * connects to the online race server
     * @param factory opens a WebSocket on this platform
     * @param url server address, ex "ws://localhost:8080"
     */
    public void connect(GameSocket.Factory factory, String url) {
        socketFactory = factory;
        serverUrl = url;
        final int attempt = ++connectAttempt;
        socketOpen = false;
        socketClosed = false;
        socket = factory.create(url, new GameSocket.Listener() {
            // messages from an older, abandoned connection are ignored
            public void onOpen() {
                synchronized (inbox) {
                    if (attempt == connectAttempt) {
                        socketOpen = true;
                    }
                }
            }

            public void onMessage(String message) {
                synchronized (inbox) {
                    if (attempt == connectAttempt) {
                        inbox.add(message);
                    }
                }
            }

            public void onClose() {
                synchronized (inbox) {
                    if (attempt == connectAttempt) {
                        socketClosed = true;
                    }
                }
            }
        });
    }

    // reconnecting: the free server sleeps when nobody plays and restarts on every update,
    // so a dropped connection before joining a room just tries again for a while
    private GameSocket.Factory socketFactory;
    private String serverUrl;
    private int connectAttempt = 0;
    private int retries = 0;
    private float retryTimer = 0f;
    private boolean stopped = false;
    private static final int MAX_RETRIES = 40;
    private static final float RETRY_DELAY = 2f;

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
     * @param raceMap the map the room races on
     */
    public void createRoom(boolean isPublic, int car, TrackMap raceMap) {
        send("CREATE|" + (isPublic ? "public" : "private") + "|" + car + "|" + raceMap.id);
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
        clock += delta;
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
            } else if (countdownTimer < 0f) {
                countdownText = "";   // the flag is still waving
            } else {
                countdownText = String.valueOf(3 - (int) countdownTimer);
            }
        }

        // single player: everything updates every frame so the CPUs move smoothly
        traffic.setPlayer(playerId, playerPos.x, playerPos.y, playerHeading);
        traffic.update(delta, gameStarted);
        if (gameStarted) {
            raceTime += delta;
        }
        // the standings fill in once the cars are on the grid (the countdown has begun)
        boolean onGrid = gameStarted || (countdownInProgress && countdownTimer >= 0f);
        for (Opponent cpu : cpuOpponents) {
            if (onGrid) {
                serverRaceManager.updateRacer(cpu.getName(), cpu.getPosition(), raceTime);
            }
            PositionPacket packet = opponents.get(cpu.getName());
            packet.x = cpu.getPosition().x;
            packet.y = cpu.getPosition().y;
            packet.rotation = cpu.getDisplayRotation();
        }
        if (onGrid) {
            serverRaceManager.updateRacer(playerId, playerPos, raceTime);
            setLeaderboard(new LeaderboardPacket(serverRaceManager.toEntries()));
        }
        playerSpeed = delta > 0f ? lastPlayerPos.dst(playerPos) / delta : 0f;
        lastPlayerPos.set(playerPos);
        if (gameStarted) {
            items.update(delta, localRacers(), raceOrder());
            for (String event : items.takeEvents()) {
                handleMessage(event.split("\\|", -1));
            }
        }
        itemView = items.describe();
    }

    /**
     * handles every message that arrived from the server since last frame
     */
    private void readMessages() {
        List<String> messages;
        synchronized (inbox) {
            connected = socketOpen && !socketClosed;
            if (connected) {
                retries = 0;
            }
            if (socketClosed && !stopped && roomCode == null && retries < MAX_RETRIES) {
                // not in a race yet: quietly try again in a moment
                retryTimer += Gdx.graphics.getDeltaTime();
                if (retryTimer >= RETRY_DELAY) {
                    retryTimer = 0f;
                    retries++;
                    connect(socketFactory, serverUrl);
                }
            } else if (socketClosed && error == null) {
                error = roomCode != null ? "Lost connection - the server restarted. Go back and join again"
                    : "Can't reach the race server right now";
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
            // ROOMS|code|players|map|code|players|map...
            publicRooms.clear();
            for (int i = 1; i + 2 < parts.length; i += 3) {
                publicRooms.add(new String[] {parts[i], parts[i + 1], parts[i + 2]});
            }
        } else if (type.equals("JOINED")) {
            roomCode = parts[1];
            roomPublic = parts[2].equals("public");
            playerId = parts[3];
            map = TrackMap.get(parts.length > 4 ? parts[4] : TrackMap.CLASSIC);
            playerReadyStates.put(playerId, false);
            error = null;
        } else if (type.equals("ERROR")) {
            error = parts[1];
        } else if (type.equals("READY")) {
            playerReadyStates.put(parts[1], parts[2].equals("true"));
        } else if (type.equals("LEFT")) {
            playerReadyStates.remove(parts[1]);
            opponents.remove(parts[1]);
        } else if (type.equals("ITEMS")) {
            itemView = parts[1] + "|" + parts[2] + "|" + parts[3] + "|" + parts[4];
        } else if (type.equals("GOT")) {
            if (parts[1].equals(playerId)) {
                heldItem = ItemSystem.Item.valueOf(parts[2]);
            }
        } else if (type.equals("BOOST") || type.equals("BLOCK")) {
            if (parts[1].equals(playerId)) {
                myEffects.add(type);
            }
        } else if (type.equals("HIT")) {
            if (parts[1].equals(playerId)) {
                myEffects.add(parts[2]);
            }
        } else if (type.equals("SETTINGS")) {
            laps = Integer.parseInt(parts[1]);
            difficulty = Integer.parseInt(parts[2]);
        } else if (type.equals("HOST")) {
            hostId = parts[1];
        } else if (type.equals("FLAG")) {
            flagShown = true;
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
            PoseHistory history = poseHistory.get(pp.playerId);
            if (history == null) {
                history = new PoseHistory();
                poseHistory.put(pp.playerId, history);
            }
            history.add(pp.x, pp.y, pp.rotation, clock);
        } else if (type.equals("LEADER")) {
            // LEADER|name|laps|progress|finishTime|name|...
            List<Entry> entries = new ArrayList<>();
            for (int i = 1; i + 3 < parts.length; i += 4) {
                entries.add(new Entry(parts[i], Integer.parseInt(parts[i + 1]),
                    Float.parseFloat(parts[i + 2]), Float.parseFloat(parts[i + 3])));
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
        stopped = true;
        if (socket != null) {
            socket.close();
        }
        if (track != null) {
            track.dispose();
            track = null;
        }
        connected = false;
        opponents.clear();
        countdownText = "";
        flagShown = false;
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
     * @return true once everyone is ready and the red start flag should wave
     */
    public boolean isFlagShown() {
        return flagShown;
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
        playerHeading = rotation + 90f;
        if (isOnline() && sendTimer >= UPDATE_INTERVAL) {
            sendTimer = 0f;
            send("POS|" + x + "|" + y + "|" + rotation);
        }
    }
    /**
     * Where to draw an opponent this frame. Online, server updates arrive 20 times a second,
     * so cars are drawn slightly in the past, sliding smoothly between the last two updates.
     * @param id opponent id
     * @param out filled with x, y, rotation
     * @return false if this opponent isn't known
     */
    public boolean getOpponentPose(String id, float[] out) {
        PositionPacket latest = opponents.get(id);
        if (latest == null) {
            return false;
        }
        PoseHistory history = poseHistory.get(id);
        if (!isOnline() || history == null || !history.hasTwo) {
            out[0] = latest.x;
            out[1] = latest.y;
            out[2] = latest.rotation;
            return true;
        }
        history.sample(clock - INTERPOLATION_DELAY, out);
        return true;
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
    /** every car in the single player race, for the items */
    private List<ItemSystem.Racer> localRacers() {
        List<ItemSystem.Racer> racers = new ArrayList<>();
        for (Opponent cpu : cpuOpponents) {
            ItemSystem.Racer r = new ItemSystem.Racer();
            r.id = cpu.getName();
            r.x = cpu.getPosition().x + CarBody.WIDTH / 2f;
            r.y = cpu.getPosition().y + CarBody.LENGTH / 2f;
            r.heading = cpu.getRotation();
            r.speed = cpu.getSpeed();
            r.cpu = cpu;
            racers.add(r);
        }
        ItemSystem.Racer me = new ItemSystem.Racer();
        me.id = playerId;
        me.x = playerPos.x + CarBody.WIDTH / 2f;
        me.y = playerPos.y + CarBody.LENGTH / 2f;
        me.heading = playerHeading;
        me.speed = playerSpeed;
        racers.add(me);
        return racers;
    }

    /** racer ids, leader first */
    private List<String> raceOrder() {
        List<String> order = new ArrayList<>();
        for (io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo r : serverRaceManager.getSortedLeaderboard()) {
            order.add(r.name);
        }
        return order;
    }

    /**
     * uses the item we're holding (online the server does it)
     */
    public void useItem() {
        if (heldItem == null) {
            return;
        }
        heldItem = null;
        if (isOnline()) {
            send("USE");
        } else if (gameStarted) {
            List<ItemSystem.Racer> racers = localRacers();
            items.use(racers.get(racers.size() - 1), racers, raceOrder());
            for (String event : items.takeEvents()) {
                handleMessage(event.split("\\|", -1));
            }
        }
    }

    /**
     * @return the item we're holding, or null
     */
    public ItemSystem.Item getHeldItem() {
        return heldItem;
    }

    /**
     * @return what's on the track (boxes|rockets|slicks|shielded ids), see ItemSystem.describe()
     */
    public String getItemView() {
        return itemView;
    }

    /**
     * @return what happened to our car since the last call (BOOST, SPIN, SLOW, BLOCK), cleared
     */
    public List<String> takeMyEffects() {
        List<String> out = new ArrayList<>(myEffects);
        myEffects.clear();
        return out;
    }

    // race settings: laps and CPU difficulty (0 easy, 1 normal, 2 hard)
    private int laps = 1;
    private int difficulty = 1;
    private String hostId;

    /**
     * changes the race settings (online only the host can, and the server tells everyone)
     * @param lapCount laps   @param cpuDifficulty 0 easy, 1 normal, 2 hard
     */
    public void setRaceSettings(int lapCount, int cpuDifficulty) {
        if (!canChangeSettings()) {
            return;
        }
        if (isOnline()) {
            send("SETTINGS|" + lapCount + "|" + cpuDifficulty);
        } else {
            laps = lapCount;
            difficulty = cpuDifficulty;
        }
    }

    /**
     * @return true if this player may change the laps and difficulty (single player, or the online host)
     */
    public boolean canChangeSettings() {
        return !flagShown && !gameStarted && (!isOnline() || (playerId != null && playerId.equals(hostId)));
    }

    /**
     * @return laps in this race
     */
    public int getLaps() {
        return laps;
    }

    /**
     * @return CPU difficulty: 0 easy, 1 normal, 2 hard
     */
    public int getDifficulty() {
        return difficulty;
    }

    /**
     * @return the map this race is on
     */
    public TrackMap getMap() {
        return map;
    }
    /**
     * Every player sorts the same list of names, so each one gets a different grid slot
     * without the server having to hand them out.
     * @return where this player's car lines up on the starting grid (image corner)
     */
    public Vector2 getStartPosition() {
        List<String> ids = new ArrayList<>(playerReadyStates.keySet());
        if (!ids.contains(playerId)) {
            ids.add(playerId);
        }
        java.util.Collections.sort(ids);
        return Waypoints.getPlayerStart(ids.indexOf(playerId));
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
            // the settings are locked in now
            serverRaceManager.setLaps(laps);
            for (Opponent cpu : cpuOpponents) {
                cpu.setLaps(laps);
                cpu.setDifficulty(difficulty);
            }
            // the flag waves first, then the countdown starts
            countdownInProgress = true;
            countdownTimer = -RaceManager.FLAG_TIME;
            countdownText = "";
            flagShown = true;
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

    /**
     * the last two positions received for an online opponent, and when they arrived
     */
    private static class PoseHistory {
        float x0, y0, r0, t0;
        float x1, y1, r1, t1;
        boolean hasTwo = false;
        boolean hasOne = false;

        void add(float x, float y, float rotation, float time) {
            if (hasOne) {
                x0 = x1; y0 = y1; r0 = r1; t0 = t1;
                hasTwo = true;
            }
            x1 = x; y1 = y; r1 = rotation; t1 = time;
            hasOne = true;
        }

        void sample(float time, float[] out) {
            float span = t1 - t0;
            float a = span > 1e-4f ? (time - t0) / span : 1f;
            a = Math.max(0f, Math.min(1.2f, a));   // a little past the newest update if one is late
            out[0] = x0 + (x1 - x0) * a;
            out[1] = y0 + (y1 - y0) * a;
            float dr = ((r1 - r0) % 360f + 540f) % 360f - 180f;   // shortest way round
            out[2] = r0 + dr * a;
        }
    }
}
