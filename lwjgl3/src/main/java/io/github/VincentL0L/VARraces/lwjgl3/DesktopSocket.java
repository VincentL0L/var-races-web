package io.github.VincentL0L.VARraces.lwjgl3;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import io.github.VincentL0L.VARraces.Multiplayer.client.GameSocket;

/**
 * WebSocket connection for the desktop game (Java-WebSocket library)
 */
public class DesktopSocket implements GameSocket {
    private final WebSocketClient client;

    /**
     * starts connecting to the server
     * @param url server address
     * @param listener gets connection events (called on the socket's thread)
     */
    public DesktopSocket(String url, final Listener listener) {
        client = new WebSocketClient(URI.create(url)) {
            public void onOpen(ServerHandshake handshake) {
                listener.onOpen();
            }

            public void onMessage(String message) {
                listener.onMessage(message);
            }

            public void onClose(int code, String reason, boolean remote) {
                listener.onClose();
            }

            public void onError(Exception ex) {
                System.err.println("Socket error: " + ex);
            }
        };
        client.connect();
    }

    public void send(String message) {
        if (client.isOpen()) {
            client.send(message);
        }
    }

    public void close() {
        client.close();
    }
}
