package io.github.VincentL0L.VARraces.server;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Online race server. Players connect with a WebSocket and can
 * list public rooms, create a public or private room, or join one by code.
 *
 * Messages are text with fields separated by '|'
 * from players:  LIST | CREATE|public or private|car|map | JOIN|code|car | READY|true | POS|x|y|rotation
 * to players:    ROOMS|code|count|map|code|count|map... | JOINED|code|public|playerId|map | ERROR|text
 *                READY|id|true | LEFT|id | COUNTDOWN|3 | POS|id|x|y|rotation|car
 *                LEADER|name|laps|progress|finishTime|name|laps...   (race order, finishTime -1 = racing)
 * accounts:      AUTH|idToken -> PROFILE|name|rating|races|wins|tier|rankedOn  (or AUTHFAIL)
 *                RANKED|car -> joins the ranked queue: QUEUE|players|seconds, then the race;
 *                after it RANKRESULT|oldRating|newRating|tier|place
 *                TOP -> TOP|name|rating|tier|name|rating|tier...
 *
 * Run with: ./gradlew server:run   (port 8080, or the PORT environment variable)
 */
public class RaceServer extends WebSocketServer {
    private static final int TICK_MS = 50;
    private static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<WebSocket, Room> playerRooms = new HashMap<>();
    private final Random random = new Random();
    private long lastTick = System.currentTimeMillis();
    // accounts and ranked (see Accounts)
    private final Accounts accounts = new Accounts();
    private final java.util.concurrent.ExecutorService background = Executors.newFixedThreadPool(2);
    private final Map<WebSocket, Accounts.Account> signedIn = new HashMap<>();
    private final Map<WebSocket, Accounts.Profile> profiles = new HashMap<>();
    private String topCache;
    private long topCachedAt = 0;

    /**
     * @param port port to listen on
     */
    public RaceServer(int port) {
        super(new InetSocketAddress("0.0.0.0", port));
        setReuseAddr(true);
    }

    public void onStart() {
        setConnectionLostTimeout(30);
        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
        loop.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        System.out.println("VAR Races server listening on port " + getPort());
    }

    public void onOpen(WebSocket conn, ClientHandshake handshake) {}

    public synchronized void onClose(WebSocket conn, int code, String reason, boolean remote) {
        signedIn.remove(conn);
        profiles.remove(conn);
        Room room = playerRooms.remove(conn);
        if (room != null) {
            room.leave(conn);
            if (room.isEmpty()) {
                rooms.remove(room.getCode());
            }
        }
    }

    public synchronized void onMessage(WebSocket conn, String message) {
        String[] parts = message.split("\\|", -1);
        Room current = playerRooms.get(conn);
        // account messages work anywhere, in a room or not
        if (parts[0].equals("AUTH") && parts.length >= 2) {
            authenticate(conn, parts[1]);
            return;
        }
        if (parts[0].equals("TOP")) {
            sendTop(conn);
            return;
        }

        if (current != null) {
            current.receive(conn, parts);
            return;
        }
        try {
            if (parts[0].equals("LIST")) {
                conn.send(listRooms());
            } else if (parts[0].equals("CREATE")) {
                String map = parts.length > 3 ? parts[3] : TrackMap.CLASSIC;
                Room room = new Room(newCode(), parts[1].equals("public"), TrackMap.get(map));
                rooms.put(room.getCode(), room);
                joinRoom(conn, room, Integer.parseInt(parts[2]));
            } else if (parts[0].equals("RANKED")) {
                joinRanked(conn, Integer.parseInt(parts[1]));
            } else if (parts[0].equals("JOIN")) {
                Room room = rooms.get(parts[1].toUpperCase());
                if (room == null) {
                    conn.send("ERROR|No room with code " + parts[1]);
                } else if (!room.isJoinable()) {
                    conn.send("ERROR|That race is full or already started");
                } else {
                    joinRoom(conn, room, Integer.parseInt(parts[2]));
                }
            }
        } catch (RuntimeException e) {
            conn.send("ERROR|Bad request");
        }
    }

    public void onError(WebSocket conn, Exception ex) {
        System.err.println("Socket error: " + ex);
    }

    /**
     * puts a player in a room
     */
    private void joinRoom(WebSocket conn, Room room, int car) {
        playerRooms.put(conn, room);
        Accounts.Account a = signedIn.get(conn);
        room.join(conn, Math.max(1, Math.min(io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel.count(), car)),
            a == null ? null : a.name, a == null ? null : a.uid);
    }

    // ---------------------------------------------------------------- accounts and ranked

    /** checks a sign-in token (off the game loop) and sends back the player's profile */
    private void authenticate(WebSocket conn, String token) {
        background.execute(() -> {
            Accounts.Account a = accounts.verify(token);
            if (a == null) {
                conn.send("AUTHFAIL");
                return;
            }
            Accounts.Profile p = null;
            if (accounts.rankedOn()) {
                try {
                    p = accounts.load(a);
                } catch (Exception e) {
                    System.err.println("profile: " + e.getMessage());
                }
            }
            synchronized (this) {
                signedIn.put(conn, a);
                if (p != null) {
                    profiles.put(conn, p);
                }
            }
            int rating = p == null ? Accounts.START_RATING : p.rating;
            conn.send("PROFILE|" + a.name + "|" + rating + "|" + (p == null ? 0 : p.races) + "|" + (p == null ? 0 : p.wins)
                + "|" + Accounts.tier(rating) + "|" + (accounts.rankedOn() && p != null ? 1 : 0));
        });
    }

    /** into the ranked queue: the open ranked room if there is one, or a new one on a random track */
    private void joinRanked(WebSocket conn, int car) {
        if (!signedIn.containsKey(conn)) {
            conn.send("ERROR|Sign in to race ranked");
            return;
        }
        if (!accounts.rankedOn() || !profiles.containsKey(conn)) {
            conn.send("ERROR|Ranked is offline right now");
            return;
        }
        Room open = null;
        for (Room r : rooms.values()) {
            if (r.isRanked() && r.isJoinable()) {
                open = r;
            }
        }
        if (open == null) {
            java.util.List<TrackMap> races = new java.util.ArrayList<>();
            for (TrackMap m : TrackMap.all()) {
                if (!m.battle) {
                    races.add(m);
                }
            }
            open = new Room(newCode(), false, races.get(random.nextInt(races.size())));
            open.setRanked();
            rooms.put(open.getCode(), open);
        }
        joinRoom(conn, open, car);
    }

    /** a finished ranked race: new ratings for everyone in it, saved and sent to them */
    private void rate(java.util.List<Object[]> result) {
        if (result.size() < 2) {
            for (Object[] r : result) {
                ((WebSocket) r[0]).send("RANKRESULT|-1|-1|UNRANKED|1");     // nobody to race against
            }
            return;
        }
        int[] old = new int[result.size()];
        Accounts.Profile[] ps = new Accounts.Profile[result.size()];
        for (int i = 0; i < result.size(); i++) {
            ps[i] = profiles.get((WebSocket) result.get(i)[0]);
            old[i] = ps[i] == null ? Accounts.START_RATING : ps[i].rating;
        }
        int[] updated = Accounts.newRatings(old);
        for (int i = 0; i < result.size(); i++) {
            WebSocket conn = (WebSocket) result.get(i)[0];
            String uid = (String) result.get(i)[1];
            Accounts.Profile p = ps[i] != null ? ps[i] : new Accounts.Profile();
            if (p.name == null) {
                p.name = (String) result.get(i)[2];
            }
            p.rating = updated[i];
            p.races++;
            if (i == 0) {
                p.wins++;
            }
            final int place = i + 1, before = old[i];
            background.execute(() -> {
                try {
                    accounts.save(uid, p);
                    topCache = null;
                } catch (Exception e) {
                    System.err.println("save: " + e.getMessage());
                }
                if (conn.isOpen()) {
                    conn.send("RANKRESULT|" + before + "|" + p.rating + "|" + Accounts.tier(p.rating) + "|" + place);
                    conn.send("PROFILE|" + p.name + "|" + p.rating + "|" + p.races + "|" + p.wins + "|" + Accounts.tier(p.rating) + "|1");
                }
            });
        }
    }

    /** the leaderboard (cached for half a minute) */
    private void sendTop(WebSocket conn) {
        if (!accounts.rankedOn()) {
            conn.send("TOP");
            return;
        }
        if (topCache != null && System.currentTimeMillis() - topCachedAt < 30_000) {
            conn.send(topCache);
            return;
        }
        background.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder("TOP");
                for (Accounts.Profile p : accounts.top(20)) {
                    sb.append('|').append(p.name.replace("|", "")).append('|').append(p.rating).append('|').append(Accounts.tier(p.rating));
                }
                topCache = sb.toString();
                topCachedAt = System.currentTimeMillis();
                conn.send(topCache);
            } catch (Exception e) {
                System.err.println("top: " + e.getMessage());
                conn.send("TOP");
            }
        });
    }

    /**
     * @return ROOMS message listing public rooms that can still be joined
     */
    private String listRooms() {
        StringBuilder msg = new StringBuilder("ROOMS");
        for (Room room : rooms.values()) {
            if (room.isPublic() && room.isJoinable()) {
                msg.append('|').append(room.getCode()).append('|').append(room.getPlayerCount())
                    .append('|').append(room.getMap().id);
            }
        }
        return msg.toString();
    }

    /**
     * @return an unused 4 letter room code
     */
    private String newCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
            }
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    /**
     * updates every room 20 times a second
     */
    private synchronized void tick() {
        long now = System.currentTimeMillis();
        float delta = (now - lastTick) / 1000f;
        lastTick = now;
        try {
            Iterator<Room> it = rooms.values().iterator();
            while (it.hasNext()) {
                Room room = it.next();
                if (room.isEmpty()) {
                    it.remove();
                } else {
                    room.tick(delta);
                    java.util.List<Object[]> result = room.takeRankedResult();
                    if (result != null) {
                        rate(result);
                    }
                }
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String port = System.getenv("PORT");
        RaceServer server = new RaceServer(port == null ? 8080 : Integer.parseInt(port));
        server.start();
    }
}
