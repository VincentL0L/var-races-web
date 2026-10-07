package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Game;

import io.github.VincentL0L.VARraces.Multiplayer.client.GameSocket;

/**
 * in charge of calling MenuScreen and holding the
 * multiplayer server address
 */
public class Main extends Game {

    /** address of the online race server */
    public static String serverUrl = "ws://localhost:8080";

    /** opens WebSockets on this platform, null if multiplayer isn't available */
    public static GameSocket.Factory socketFactory;

    /**
     * @param sockets opens WebSockets on this platform
     * @param url address of the race server
     */
    public Main(GameSocket.Factory sockets, String url) {
        socketFactory = sockets;
        if (url != null) {
            serverUrl = url;
        }
    }

    /**
     * called by libGDX, calls MenuScreen
     */
    public void create() {
        setScreen(new MenuScreen(this));
    }

    /**
     * clears memory
     */
    public void dispose() {
        if (getScreen() != null){ 
            getScreen().dispose();
        }
    }
}
