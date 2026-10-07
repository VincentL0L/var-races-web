package io.github.VincentL0L.VARraces.teavm;

import org.teavm.jso.websocket.WebSocket;

import io.github.VincentL0L.VARraces.Multiplayer.client.GameSocket;

/**
 * WebSocket connection for the browser version (uses the browser's WebSocket)
 */
public class WebSocketConnection implements GameSocket {
    private final WebSocket socket;

    /**
     * starts connecting to the server
     * @param url server address
     * @param listener gets connection events
     */
    public WebSocketConnection(String url, Listener listener) {
        socket = new WebSocket(url);
        socket.onOpen(e -> listener.onOpen());
        socket.onMessage(e -> listener.onMessage(e.getDataAsString()));
        socket.onClose(e -> listener.onClose());
    }

    public void send(String message) {
        // 1 = OPEN
        if (socket.getReadyState() == 1) {
            socket.send(message);
        }
    }

    public void close() {
        socket.close();
    }
}
