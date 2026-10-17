package io.github.VincentL0L.VARraces.server;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.java_websocket.WebSocket;
import org.junit.Test;

import com.badlogic.gdx.math.Vector2;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

/** A lobby lives on after its race: same players, a new track, and latecomers race next time. */
public class RoomLobbyTest {
    /** a connection that keeps every message the room sends it */
    private static WebSocket player(List<String> inbox) {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[] {WebSocket.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "send": inbox.add(String.valueOf(args[0])); return null;
                    case "isOpen": return true;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return method.getReturnType() == boolean.class ? false : null;
                }
            });
    }

    private final Room room = new Room("TEST", true, TrackMap.get(TrackMap.CLASSIC));
    private final List<String> a = new ArrayList<>(), b = new ArrayList<>(), late = new ArrayList<>();
    private final WebSocket ca = player(a), cb = player(b), cl = player(late);

    private void tickUntil(List<String> inbox, String message) {
        for (int i = 0; i < 400 && !inbox.contains(message); i++) {
            room.tick(0.05f);
        }
        assertTrue("never got " + message, inbox.contains(message));
    }

    /** drives a car once round the classic circuit, from its grid slot over the line and back to it */
    private void driveLap(WebSocket conn, int slot) {
        List<Vector2> wp = TrackMap.get(TrackMap.CLASSIC).waypoints;
        List<Vector2> route = new ArrayList<>();
        route.add(Waypoints.getPlayerStart(slot));
        route.add(wp.get(wp.size() - 1));
        route.addAll(wp);
        route.add(wp.get(0));
        for (int i = 0; i + 1 < route.size(); i++) {
            Vector2 from = route.get(i), to = route.get(i + 1);
            int steps = Math.max(1, (int) (from.dst(to) / 20f));
            for (int s = 1; s <= steps; s++) {
                Vector2 p = from.cpy().lerp(to, s / (float) steps);
                room.receive(conn, ("POS|" + p.x + "|" + p.y + "|0").split("\\|"));
                room.tick(0.05f);
            }
        }
    }

    @Test public void playersStayInTheLobbyBetweenRaces() {
        room.join(ca, 1);
        room.join(cb, 2);
        room.receive(ca, "SETTINGS|1|1|false".split("\\|"));
        room.receive(ca, "READY|true".split("\\|"));
        room.receive(cb, "READY|true".split("\\|"));
        assertTrue(a.contains("FLAG"));
        tickUntil(a, "COUNTDOWN|GO!");

        // someone joins mid-race: they wait, and they're not on the track
        assertTrue(room.isJoinable());
        room.join(cl, 3);
        assertTrue(late.contains("RACING"));
        a.clear();
        room.tick(0.05f);
        assertFalse(a.stream().anyMatch(m -> m.startsWith("POS|Player 3|")));

        driveLap(ca, 0);
        driveLap(cb, 1);
        tickUntil(late, "LOBBY|classic");
        assertTrue(a.contains("LOBBY|classic"));
        assertTrue(b.contains("LOBBY|classic"));

        // the host picks another track; a guest can't
        room.receive(cb, "MAP|tokyo".split("\\|"));
        assertFalse(a.contains("LOBBY|tokyo"));
        room.receive(ca, "MAP|tokyo".split("\\|"));
        assertTrue(late.contains("LOBBY|tokyo"));
        assertTrue("bots stay off", b.stream().filter(m -> m.startsWith("SETTINGS|")).reduce((x, y) -> y).get().endsWith("false"));

        // everyone, latecomer included, races again without rejoining
        a.clear();
        room.receive(ca, "READY|true".split("\\|"));
        room.receive(cb, "READY|true".split("\\|"));
        assertFalse("waits for the latecomer", a.contains("FLAG"));
        room.receive(cl, "READY|true".split("\\|"));
        assertTrue(a.contains("FLAG"));
        tickUntil(late, "COUNTDOWN|GO!");
    }
}
