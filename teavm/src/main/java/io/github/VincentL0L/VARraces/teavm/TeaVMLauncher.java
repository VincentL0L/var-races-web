package io.github.VincentL0L.VARraces.teavm;

import org.teavm.jso.JSBody;

import com.github.xpenatan.gdx.backends.teavm.TeaApplication;
import com.github.xpenatan.gdx.backends.teavm.TeaApplicationConfiguration;

import io.github.VincentL0L.VARraces.Main;
import io.github.VincentL0L.VARraces.Ui;

/**
 * Launches the game in a web browser
 */
public class TeaVMLauncher {
    /** online race server used by the hosted site */
    private static final String PUBLIC_SERVER = "wss://var-races-web.onrender.com";

    public static void main(String[] args) {
        TeaApplicationConfiguration config = new TeaApplicationConfiguration("canvas");
        // 0 x 0 makes the canvas fill the browser window and resize with it
        config.width = 0;
        config.height = 0;
        config.padHorizontal = 0;
        config.padVertical = 0;
        // draw at the screen's real resolution so text is sharp on Retina screens,
        // while menus are laid out in points (see Ui)
        config.usePhysicalPixels = true;
        Ui.pixelRatio = (float) getDevicePixelRatio();
        Ui.touchScreen = isTouchScreen();
        Ui.textPrompt = TeaVMLauncher::prompt;

        // a page opened with ?server=wss://host uses that server instead
        String server = getServerParam();
        if (server == null || server.isEmpty()) {
            server = isLocalhost() ? "ws://localhost:8080" : PUBLIC_SERVER;
        }
        new TeaApplication(new Main(WebSocketConnection::new, server), config);
    }

    @JSBody(script = "return new URLSearchParams(window.location.search).get('server');")
    private static native String getServerParam();

    @JSBody(script = "return ('ontouchstart' in window) || navigator.maxTouchPoints > 0;")
    private static native boolean isTouchScreen();

    /** the browser's own text box, which brings up the phone keyboard */
    @JSBody(params = {"message", "current"}, script = "return window.prompt(message, current);")
    private static native String prompt(String message, String current);

    @JSBody(script = "return window.devicePixelRatio || 1;")
    private static native double getDevicePixelRatio();

    @JSBody(script = "var h = window.location.hostname; return h === 'localhost' || h === '127.0.0.1';")
    private static native boolean isLocalhost();
}
