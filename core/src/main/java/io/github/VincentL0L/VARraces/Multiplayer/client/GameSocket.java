package io.github.VincentL0L.VARraces.Multiplayer.client;

/**
 * A WebSocket connection to the race server.
 * Desktop and web each provide their own version because they
 * open WebSockets in different ways.
 */
public interface GameSocket {

    /**
     * receives events from the socket
     * (on desktop these can arrive on another thread)
     */
    interface Listener {
        void onOpen();
        void onMessage(String message);
        void onClose();
    }

    /**
     * makes new sockets; set by the launcher in Main
     */
    interface Factory {
        GameSocket create(String url, Listener listener);
    }

    /**
     * sends a text message to the server
     * @param message message to send
     */
    void send(String message);

    /**
     * closes the connection
     */
    void close();
}
