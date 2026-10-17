package io.github.VincentL0L.VARraces.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.java_websocket.WebSocket;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarBody;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.ItemSystem;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Opponent;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CpuTraffic;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

/**
 * One lobby on the server: its players, 3 CPU opponents, countdown and leaderboard.
 * After each race everyone goes back to the lobby together for the next one (on the
 * same track or one the host picks), so nobody has to rejoin. Players who join mid-race
 * wait in the lobby until it's over. Ranked rooms are still one race each.
 */
public class Room {
    public static final int MAX_PLAYERS = 6;

    private final String code;
    private final boolean isPublic;
    private final Map<WebSocket, PlayerState> players = new LinkedHashMap<>();
    private final List<Opponent> cpuOpponents = new ArrayList<>();
    private final List<Opponent> allCpuOpponents = new ArrayList<>();
    private boolean botsEnabled = true;
    private CpuTraffic traffic;
    private TrackMap map;
    private RaceManager raceManager;
    private ItemSystem items;
    /** battle mode (null in races) */
    private BattleSystem battle;
    private float roomClock = 0f;
    // race settings, chosen by the host in the lobby
    private int laps = 1;
    private int difficulty = 1;
    private int nextPlayerNumber = 1;
    private boolean countdownInProgress = false;
    private boolean started = false;
    private float countdownTimer = 0f;
    private String lastCountdown = "";
    private float raceTime = 0f;
    /** seconds since everyone finished: the results show for a moment, then it's back to the lobby */
    private float overTime = 0f;
    private static final float RESULTS_TIME = 4f;

    /**
     * creates a room with 3 CPU opponents on the starting line
     * @param code 4 letter code players join with
     * @param isPublic true if it shows in the public room list
     * @param map the map this room races on
     */
    public Room(String code, boolean isPublic, TrackMap map) {
        this.code = code;
        this.isPublic = isPublic;
        setup(map);
    }

    /** a fresh race on this map: CPUs on the grid, no items on the track, no standings */
    private void setup(TrackMap newMap) {
        map = newMap;
        List<Vector2> waypoints = map.waypoints;
        raceManager = RaceManager.forMap(map);
        items = new ItemSystem(waypoints, map.pointToPoint);
        battle = null;
        if (map.battle) {
            battle = new BattleSystem();
            items.setBattle(battle);
        }
        allCpuOpponents.clear();
        List<Vector2> grid = Waypoints.getCpuGrid();
        for (int i = 0; i < grid.size(); i++) {
            Vector2 start = grid.get(i);
            float heading = 90f;
            if (map.battle) {
                float[] spot = TrackMap.arenaSpawn(TrackMap.CPU_SPAWNS[i]);
                start = new Vector2(spot[0], spot[1]);
                heading = spot[2];
            }
            Opponent cpu = new Opponent("CPU" + (i + 1), waypoints, start);
            cpu.setSprint(map.pointToPoint);
            cpu.setBattle(map.battle);
            cpu.setHeading(heading);
            allCpuOpponents.add(cpu);
        }
        cpuOpponents.clear();
        if (botsEnabled) {
            cpuOpponents.addAll(allCpuOpponents);
        }
        traffic = new CpuTraffic(cpuOpponents, ImageTrack.forMap(map));
    }

    /**
     * Everyone back to the lobby, unready, for a new race on newMap. Clients reset on LOBBY.
     */
    private void backToLobby(TrackMap newMap) {
        setup(newMap);
        started = false;
        countdownInProgress = false;
        countdownTimer = 0f;
        lastCountdown = "";
        raceTime = 0f;
        firstFinish = -1f;
        overTime = 0f;
        int i = 0;
        for (PlayerState p : players.values()) {
            p.ready = false;
            p.racing = false;
            p.position.set(spawn(i++));
            p.lastPosition.set(p.position);
        }
        broadcast("LOBBY|" + map.id);
    }

    /** where a player waits before the race (their game sends the real grid spot once it starts) */
    private static Vector2 spawn(int index) {
        return new Vector2(200 + index * 40, 300);
    }

    /** true while a race is on (from the start flag until everyone's back in the lobby) */
    private boolean racing() {
        return started || countdownInProgress;
    }

    /** in this race: everyone between races, and once it's on, only those who were there at the start */
    private boolean inRace(PlayerState p) {
        return p.racing || !racing();
    }

    /**
     * Once every racer has finished (or the battle's over, or a minute after the first
     * finisher), the results show for a moment and then everyone's back in the lobby.
     */
    private void checkRaceOver(float delta) {
        if (ranked || !started) {
            return;
        }
        boolean all = true, any = false;
        for (PlayerState p : players.values()) {
            if (p.racing) {
                boolean done = battle != null ? battle.isOver() : raceManager.isFinished(p.id);
                all &= done;
                any |= done;
            }
        }
        if (any && firstFinish < 0f) {
            firstFinish = raceTime;
        }
        if (all || (firstFinish >= 0f && raceTime - firstFinish > 60f)) {
            overTime += delta;
            if (overTime >= RESULTS_TIME) {
                backToLobby(map);
            }
        }
    }

    /**
     * adds a player and tells everyone in the room
     * @param conn player's connection
     * @param car car skin the player picked
     */
    public void join(WebSocket conn, int car) {
        join(conn, car, null, null);
    }

    /**
     * @param name the player's account name (null for a guest, who's "Player 2" etc)
     * @param uid their account id (null for a guest)
     */
    public void join(WebSocket conn, int car, String name, String uid) {
        String id = name == null ? "Player " + nextPlayerNumber++ : uniqueId(name);
        PlayerState state = new PlayerState(id, car, spawn(players.size()));
        state.uid = uid;

        conn.send("JOINED|" + code + "|" + (ranked ? "ranked" : isPublic ? "public" : "private") + "|" + id + "|" + map.id);
        for (PlayerState other : players.values()) {
            conn.send("READY|" + other.id + "|" + other.ready);
        }
        players.put(conn, state);
        broadcast("READY|" + id + "|false");
        conn.send(settingsMessage());
        broadcast("HOST|" + hostId());
        if (racing()) {
            conn.send("RACING");     // a race is on: they wait in the lobby for the next one
        }
    }

    /** a name nobody else in the room has ("Vincent L", then "Vincent L 2"...) */
    private String uniqueId(String name) {
        String id = name;
        for (int n = 2; ; n++) {
            boolean taken = id.startsWith("CPU");
            for (PlayerState p : players.values()) {
                taken |= p.id.equals(id);
            }
            if (!taken) {
                return id;
            }
            id = name + " " + n;
        }
    }

    // ---------------------------------------------------------------- ranked

    /** a ranked race: starts by itself, fixed settings, results change ratings */
    private boolean ranked = false;
    private float queueTime = 0f, fullTime = 0f;
    private int lastQueueSecond = -1;
    private float firstFinish = -1f;
    private boolean resultTaken = false;
    /** seconds to wait once two players are in, and how long one player waits before racing anyway */
    private static final float RANKED_WAIT = 20f, SOLO_WAIT = 45f;

    /**
     * makes this a ranked room
     */
    public void setRanked() {
        ranked = true;
        laps = map.hasLaps() ? 2 : 1;
        difficulty = 1;
    }

    public boolean isRanked() {
        return ranked;
    }

    /** the ranked queue: counts down and starts the race by itself */
    private void tickQueue(float delta) {
        if (!ranked || started || countdownInProgress || players.isEmpty()) {
            return;
        }
        queueTime += delta;
        fullTime = players.size() >= 2 ? fullTime + delta : 0f;
        float left = players.size() >= 2 ? RANKED_WAIT - fullTime : SOLO_WAIT - queueTime;
        if (players.size() >= MAX_PLAYERS) {
            left = Math.min(left, 3f);
        }
        int second = (int) Math.ceil(Math.max(0f, left));
        if (second != lastQueueSecond) {
            lastQueueSecond = second;
            broadcast("QUEUE|" + players.size() + "|" + second);
        }
        if (left <= 0f) {
            startCountdown();
        }
    }

    /**
     * Once every player has finished (or a minute after the first did), the result of a
     * ranked race, once: the signed-in players in finishing order.
     * @return {connection, uid, name} per player, best first; null until it's ready (and after)
     */
    public List<Object[]> takeRankedResult() {
        if (!ranked || !started || resultTaken) {
            return null;
        }
        boolean all = true, any = false;
        for (PlayerState p : players.values()) {
            boolean done = raceManager.isFinished(p.id);
            all &= done;
            any |= done;
        }
        if (any && firstFinish < 0f) {
            firstFinish = raceTime;
        }
        if (!all && !(firstFinish >= 0f && raceTime - firstFinish > 60f)) {
            return null;
        }
        resultTaken = true;
        List<Object[]> result = new ArrayList<>();
        for (RacerInfo r : raceManager.getSortedLeaderboard()) {
            for (Map.Entry<WebSocket, PlayerState> e : players.entrySet()) {
                if (e.getValue().id.equals(r.name) && e.getValue().uid != null) {
                    result.add(new Object[] {e.getKey(), e.getValue().uid, e.getValue().id});
                }
            }
        }
        return result;
    }

    /** the host (who picks laps and difficulty) is whoever has been in the room longest */
    private String hostId() {
        for (PlayerState p : players.values()) {
            return p.id;
        }
        return "";
    }

    private String settingsMessage() {
        return "SETTINGS|" + laps + "|" + difficulty + "|" + botsEnabled;
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
            if (!players.isEmpty()) {
                broadcast("HOST|" + hostId());
            }
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
        } else if (parts[0].equals("SETTINGS") && parts.length >= 3 && !ranked && !started && !countdownInProgress
                && state.id.equals(hostId())) {
            try {
                int newLaps = Math.max(1, Math.min(10, Integer.parseInt(parts[1])));
                int newDifficulty = Math.max(0, Math.min(3, Integer.parseInt(parts[2])));
                boolean newBots = parts.length < 4 ? botsEnabled : Boolean.parseBoolean(parts[3]);
                laps = newLaps;
                difficulty = newDifficulty;
                if (botsEnabled != newBots) {
                    botsEnabled = newBots;
                    cpuOpponents.clear();
                    if (botsEnabled) {
                        cpuOpponents.addAll(allCpuOpponents);
                    } else {
                        for (Opponent cpu : allCpuOpponents) {
                            broadcast("LEFT|" + cpu.getName());
                        }
                    }
                }
                broadcast(settingsMessage());
            } catch (NumberFormatException e) {
                // ignore bad settings
            }
        } else if (parts[0].equals("MAP") && parts.length >= 2 && !ranked && !racing()
                && state.id.equals(hostId())) {
            backToLobby(TrackMap.get(parts[1]));
        } else if (parts[0].equals("USE") && started && state.racing) {
            List<ItemSystem.Racer> racers = racers();
            for (ItemSystem.Racer r : racers) {
                if (r.id.equals(state.id)) {
                    items.use(r, racers, order());
                }
            }
            sendItemEvents();
        }
    }

    /**
     * starts the countdown once every player is ready
     */
    private void checkAllReady() {
        if (ranked || started || countdownInProgress || players.isEmpty()) return;
        for (PlayerState p : players.values()) {
            if (!p.ready) return;
        }
        startCountdown();
    }

    private void startCountdown() {
        // the settings are locked in now, and so are the racers
        for (PlayerState p : players.values()) {
            p.racing = true;
        }
        raceManager.setLaps(laps);
        for (Opponent cpu : cpuOpponents) {
            cpu.setLaps(laps);
            cpu.setDifficulty(difficulty);
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
        // every update is stamped with the room's clock, so players' games can space the
        // cars' movement evenly however unevenly the messages arrive
        roomClock += delta;
        broadcast("TICK|" + roomClock);
        tickQueue(delta);
        checkRaceOver(delta);
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
            if (inRace(p)) {
                traffic.setPlayer(p.id, p.position.x, p.position.y, p.rotation + 90f, CarModel.of(p.car).mass);
            }
        }
        traffic.update(delta, started);
        if (started) {
            raceTime += delta;
        }
        // standings start once everyone is on the grid (the countdown has begun)
        boolean onGrid = started || (countdownInProgress && countdownTimer >= 0f);
        for (Opponent cpu : cpuOpponents) {
            if (onGrid && battle == null) {
                raceManager.updateRacer(cpu.getName(), cpu.getPosition(), raceTime);
            }
            broadcast("POS|" + cpu.getName() + "|" + cpu.getPosition().x + "|"
                + cpu.getPosition().y + "|" + cpu.getDisplayRotation() + "|1");
        }

        for (PlayerState p : players.values()) {
            if (!inRace(p)) {
                continue;
            }
            if (onGrid && battle == null) {
                raceManager.updateRacer(p.id, p.position, raceTime);
            }
            broadcast("POS|" + p.id + "|" + p.position.x + "|" + p.position.y + "|"
                + p.rotation + "|" + p.car);
        }

        // items: boxes, rockets, oil and shields, and what happened to whom
        for (PlayerState p : players.values()) {
            p.speed = delta > 0f ? p.lastPosition.dst(p.position) / delta : 0f;
            p.lastPosition.set(p.position);
        }
        if (started) {
            items.update(delta, racers(), order());
            sendItemEvents();
        }
        broadcast("ITEMS|" + items.describe());

        // battle: crashes and knockouts
        if (onGrid && battle != null) {
            java.util.Map<String, Float> masses = new java.util.HashMap<>();
            for (PlayerState p : players.values()) {
                if (inRace(p)) {
                    masses.put(p.id, CarModel.of(p.car).mass);
                }
            }
            battle.update(delta, racers(), masses, started);
            for (String event : battle.takeEvents()) {
                if (event.startsWith("OUT|")) {
                    traffic.setOut(event.split("\\|")[1]);
                }
                broadcast(event);
            }
            StringBuilder msg = new StringBuilder("LEADER");
            for (io.github.VincentL0L.VARraces.Multiplayer.packets.Entry e : battle.toEntries()) {
                msg.append('|').append(e.name).append('|').append(e.lapCount).append('|')
                    .append(e.progress).append('|').append(e.finishTime);
            }
            broadcast(msg.toString());
        } else
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

    /** every car in the room, for the items */
    private List<ItemSystem.Racer> racers() {
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
        for (PlayerState p : players.values()) {
            if (!inRace(p)) {
                continue;
            }
            ItemSystem.Racer r = new ItemSystem.Racer();
            r.id = p.id;
            r.x = p.position.x + CarBody.WIDTH / 2f;
            r.y = p.position.y + CarBody.LENGTH / 2f;
            r.heading = p.rotation + 90f;
            r.speed = p.speed;
            racers.add(r);
        }
        return racers;
    }

    /** racer ids, leader first */
    private List<String> order() {
        if (battle != null) {
            return battle.order();
        }
        List<String> order = new ArrayList<>();
        for (RacerInfo r : raceManager.getSortedLeaderboard()) {
            order.add(r.name);
        }
        return order;
    }

    private void sendItemEvents() {
        for (String event : items.takeEvents()) {
            broadcast(event);
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
     * @return true if new players can still join (mid-race too: they wait for the next one,
     * except in ranked, where the race is the whole room)
     */
    public boolean isJoinable() {
        return players.size() < MAX_PLAYERS && !(ranked && racing());
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
        /** in the race that's on now (false for someone who joined mid-race) */
        boolean racing = false;
        String uid;
        final Vector2 lastPosition = new Vector2();
        float speed = 0f;

        PlayerState(String id, int car, Vector2 spawn) {
            this.id = id;
            this.car = car;
            this.position = spawn.cpy();
            this.lastPosition.set(spawn);
        }
    }
}
