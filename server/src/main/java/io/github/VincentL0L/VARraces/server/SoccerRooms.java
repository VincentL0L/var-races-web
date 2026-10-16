package io.github.VincentL0L.VARraces.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.java_websocket.WebSocket;
import io.github.VincentL0L.VARraces.soccer.SoccerMatch;

/** Soccer rooms use the existing WebSocket endpoint, isolated from race-room messages. */
final class SoccerRooms {
    private static final class Room {
        final String code;
        final SoccerMatch match;
        final Map<WebSocket, Integer> players = new LinkedHashMap<>();
        Room(String code, int size) { this.code = code; match = new SoccerMatch(size); }
    }
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<WebSocket, Room> joined = new HashMap<>();
    private final Random random = new Random();

    boolean handle(WebSocket socket, String message) {
        if (!message.startsWith("SOCCER|")) return joined.containsKey(socket);
        if (message.length() > 256) return true;
        String[] p = message.split("\\|", -1);
        Room room = joined.get(socket);
        try {
            if (p.length < 2) throw new IllegalArgumentException("Invalid soccer request");
            if (p[1].equals("LEAVE")) leave(socket);
            else if (room != null) {
                int slot = room.players.get(socket);
                if (p[1].equals("INPUT") && p.length == 6) {
                    room.match.input(slot, Float.parseFloat(p[2]), Float.parseFloat(p[3]), p[4].equals("1"), p[5].equals("1"));
                } else if (p[1].equals("REMATCH") && socket == room.players.keySet().iterator().next()
                        && room.match.phase == SoccerMatch.FINISHED) room.match.restart();
            } else if (p[1].equals("LIST")) {
                StringBuilder list = new StringBuilder("SOCCER_LIST");
                for (Room r : rooms.values()) {
                    if (r.match.phase == SoccerMatch.WAITING) list.append('|').append(r.code).append(',')
                        .append(r.match.teamSize).append(',').append(r.players.size());
                }
                socket.send(list.toString());
            } else if (p[1].equals("CREATE") && p.length == 3) {
                if (rooms.size() >= 128) throw new IllegalArgumentException("Soccer rooms are full. Try again soon");
                int size = Integer.parseInt(p[2]);
                if (size != 1 && size != 2) throw new IllegalArgumentException("Choose 1v1 or 2v2");
                String code;
                do {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < 5; i++) b.append("ABCDEFGHJKLMNPQRSTUVWXYZ".charAt(random.nextInt(24)));
                    code = b.toString();
                } while (rooms.containsKey(code));
                room = new Room(code, size); rooms.put(code, room); join(socket, room);
            } else if (p[1].equals("JOIN") && p.length == 3) {
                room = rooms.get(p[2].trim().toUpperCase(java.util.Locale.ROOT));
                if (room == null) throw new IllegalArgumentException("No soccer room with that code");
                if (room.match.phase != SoccerMatch.WAITING || room.players.size() >= room.match.cars.length)
                    throw new IllegalArgumentException("That match is full or already started");
                join(socket, room);
            } else throw new IllegalArgumentException("Invalid soccer request");
        } catch (IllegalArgumentException e) {
            socket.send("SOCCER_ERROR|" + (e instanceof NumberFormatException ? "Invalid input" : e.getMessage()));
        }
        return true;
    }

    private void join(WebSocket socket, Room room) {
        int slot = 0;
        while (room.players.containsValue(slot)) slot++;
        room.players.put(socket, slot); joined.put(socket, room); room.match.add(slot);
        socket.send("SOCCER_JOINED|" + room.code + "|" + slot);
        broadcast(room);
    }

    void leave(WebSocket socket) {
        Room room = joined.remove(socket);
        if (room == null) return;
        Integer slot = room.players.remove(socket);
        room.match.remove(slot);
        if (room.players.isEmpty()) rooms.remove(room.code);
        else broadcast(room);
    }

    void tick(float delta) {
        Iterator<Room> it = rooms.values().iterator();
        while (it.hasNext()) {
            Room r = it.next();
            if (r.players.isEmpty()) { it.remove(); continue; }
            r.match.step(delta);
            broadcast(r);
        }
    }

    private void broadcast(Room room) {
        String snapshot = room.match.snapshot();
        String message = "SOCCER_STATE|" + snapshot.substring(0, snapshot.length() - 1)
            + ",\"host\":" + room.players.values().iterator().next() + "}";
        for (WebSocket socket : room.players.keySet()) if (socket.isOpen()) socket.send(message);
    }
}
