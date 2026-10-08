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
        room.join(conn, Math.max(1, Math.min(3, car)));
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
