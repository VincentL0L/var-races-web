package io.github.VincentL0L.VARraces.teavm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

import com.github.xpenatan.gdx.backends.teavm.config.AssetFileHandle;
import com.github.xpenatan.gdx.backends.teavm.config.TeaBuildConfiguration;
import com.github.xpenatan.gdx.backends.teavm.config.TeaBuilder;

/**
 * Compiles the game to JavaScript and copies the assets into build/dist/webapp
 * Run with: ./gradlew teavm:buildJavaScript
 */
public class TeaVMBuilder {
    public static void main(String[] args) throws IOException {
        TeaBuildConfiguration config = new TeaBuildConfiguration();
        config.assetsPath.add(new AssetFileHandle("../assets"));
        config.webappPath = new File("build/dist").getCanonicalPath();
        config.htmlTitle = "VAR Races";
        config.htmlWidth = 1280;
        config.htmlHeight = 720;
        config.useDefaultHtmlIndex = true;
        config.showLoadingLogo = false;

        TeaVMTool tool = TeaBuilder.config(config);
        tool.setMainClass(TeaVMLauncher.class.getName());
        tool.setObfuscated(true);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
        if (!TeaBuilder.build(tool)) {
            System.exit(1);
        }
        addMobileSupport(new File(config.webappPath, "webapp/index.html"));
    }

    /** extra page settings so the game works on phones */
    private static final String MOBILE_HEAD =
        "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover\">\n"
        + "    <meta name=\"apple-mobile-web-app-capable\" content=\"yes\">\n"
        + "    <meta name=\"mobile-web-app-capable\" content=\"yes\">\n"
        + "    <meta name=\"theme-color\" content=\"#000000\">\n"
        + "    <style>\n"
        // no scrolling, pinch zoom, text selection or tap highlight while playing
        + "      html, body { touch-action: none; overscroll-behavior: none; -webkit-user-select: none; user-select: none; -webkit-tap-highlight-color: transparent; }\n"
        + "      body { height: 100dvh; }\n"
        + "      canvas { touch-action: none; }\n"
        // the game is played sideways; held upright, a phone asks to be turned
        + "      #rotate { display: none; position: fixed; inset: 0; z-index: 10; background: #E8281E; color: #fff;\n"
        + "        font: 700 22px system-ui, sans-serif; text-align: center; flex-direction: column; align-items: center; justify-content: center; gap: 18px; }\n"
        + "      #rotate .phone { width: 46px; height: 80px; border: 5px solid #fff; border-radius: 10px; animation: turn 1.6s ease-in-out infinite; }\n"
        + "      @keyframes turn { 0%, 25% { transform: rotate(0deg); } 60%, 100% { transform: rotate(-90deg); } }\n"
        + "      @media (orientation: portrait) and (pointer: coarse) { #rotate { display: flex; } }\n"
        + "    </style>\n";

    /**
     * Google sign-in with Firebase Authentication. The game reads window.varAuth each frame.
     * Browsers only open the sign-in window straight from a click, so the game "arms" it when
     * its button is pressed and the click's release opens it.
     */
    private static final String AUTH_SCRIPT =
        "<script src=\"https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js\"></script>\n"
        + "<script src=\"https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js\"></script>\n"
        + "<script>\n"
        + "window.varAuth = {ready: false, armed: false, uid: null, name: null, token: null, error: null};\n"
        + "try {\n"
        + "  var host = location.hostname;\n"
        + "  firebase.initializeApp({apiKey: 'AIzaSyBlIzA_86YzqDi3_G9BbUAVzh0ss0MIYaA',\n"
        + "    authDomain: (host.endsWith('.web.app') || host.endsWith('.firebaseapp.com')) ? host : 'var-races.firebaseapp.com',\n"
        + "    projectId: 'var-races', appId: '1:222306009427:web:132612da1788a9ad8a21a6', messagingSenderId: '222306009427'});\n"
        + "  var auth = firebase.auth();\n"
        + "  var provider = new firebase.auth.GoogleAuthProvider();\n"
        + "  auth.onIdTokenChanged(function (u) {\n"
        + "    if (!u) { varAuth.uid = null; varAuth.name = null; varAuth.token = null; return; }\n"
        + "    varAuth.uid = u.uid; varAuth.name = u.displayName || 'Racer';\n"
        + "    u.getIdToken().then(function (t) { varAuth.token = t; });\n"
        + "  });\n"
        + "  setInterval(function () { if (auth.currentUser) auth.currentUser.getIdToken().then(function (t) { varAuth.token = t; }); }, 600000);\n"
        + "  varAuth.signIn = function () {\n"
        + "    varAuth.error = null;\n"
        + "    auth.signInWithPopup(provider).catch(function (e) {\n"
        + "      if (e.code === 'auth/popup-blocked' || e.code === 'auth/operation-not-supported-in-this-environment') return auth.signInWithRedirect(provider);\n"
        + "      if (e.code !== 'auth/popup-closed-by-user' && e.code !== 'auth/cancelled-popup-request') varAuth.error = e.code || String(e);\n"
        + "    });\n"
        + "  };\n"
        + "  varAuth.signOut = function () { auth.signOut(); };\n"
        + "  var fire = function () { if (varAuth.armed) { varAuth.armed = false; varAuth.signIn(); } };\n"
        + "  document.addEventListener('pointerup', fire, true);\n"
        + "  document.addEventListener('touchend', fire, true);\n"
        + "  varAuth.ready = true;\n"
        + "} catch (e) { varAuth.error = 'unavailable'; }\n"
        + "</script>\n";

    private static final String ROTATE_SCREEN =
        "<div id=\"rotate\"><div class=\"phone\"></div><div>Turn your phone sideways to race</div></div>\n";

    private static void addMobileSupport(File index) throws IOException {
        String html = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
        if (html.contains("id=\"rotate\"") && html.contains("varAuth")) {
            return;
        }
        html = html.replace("</head>", MOBILE_HEAD + "</head>");
        html = html.replaceFirst("(<body[^>]*>)", "$1\n" + ROTATE_SCREEN);
        html = html.replace("</body>", AUTH_SCRIPT + "</body>");
        Files.write(index.toPath(), html.getBytes(StandardCharsets.UTF_8));
    }
}
