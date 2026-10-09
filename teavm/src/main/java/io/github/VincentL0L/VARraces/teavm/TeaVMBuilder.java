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
        + "    authDomain: 'var-races.firebaseapp.com',\n"
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
        // ---- email accounts: a real HTML form over the game (so password managers,
        // autofill and phone keyboards all work), styled like the game's gold panels
        + "  var css = document.createElement('style');\n"
        + "  css.textContent = '#varForm{position:fixed;inset:0;display:none;z-index:20;background:rgba(10,6,4,.6);align-items:center;justify-content:center;font-family:Michroma,system-ui,sans-serif}'\n"
        + "   + '#varForm .card{width:min(360px,88vw);background:#2c1f17;border:4px solid #f2b83a;box-shadow:0 0 0 4px #6a3d12,0 10px 30px rgba(0,0,0,.6);padding:20px 22px;color:#fff3d6}'\n"
        + "   + '#varForm h2{margin:0 0 4px;color:#ffcf47;font:italic 900 26px system-ui,sans-serif;letter-spacing:1px}'\n"
        + "   + '#varForm .tabs{display:flex;gap:8px;margin:10px 0 14px}#varForm .tabs button{flex:1}'\n"
        + "   + '#varForm label{display:block;font-size:10px;color:#d9a64d;letter-spacing:1px;margin:10px 0 4px}'\n"
        + "   + '#varForm input{box-sizing:border-box;width:100%;padding:10px;background:#1b130e;border:2px solid #b5761c;color:#fff3d6;font:15px system-ui,sans-serif;outline:none}'\n"
        + "   + '#varForm input:focus{border-color:#ffcf47}'\n"
        + "   + '#varForm button{cursor:pointer;padding:10px;border:0;background:#f2b83a;box-shadow:inset 0 -4px #a8641a;color:#2b1408;font:italic 900 15px system-ui,sans-serif}'\n"
        + "   + '#varForm button.off{background:#6a5032;box-shadow:inset 0 -4px #3f2c1a;color:#d9b98a}'\n"
        + "   + '#varForm .go{width:100%;margin-top:16px;font-size:18px}'\n"
        + "   + '#varForm .row{display:flex;justify-content:space-between;margin-top:12px;font-size:11px}#varForm a{color:#d9a64d;cursor:pointer}'\n"
        + "   + '#varForm .msg{min-height:16px;margin-top:10px;font-size:12px;color:#ff8a70}#varForm .msg.ok{color:#7fe08a}';\n"
        + "  document.head.appendChild(css);\n"
        + "  var box = document.createElement('div'); box.id = 'varForm';\n"
        + "  box.innerHTML = '<form class=card autocomplete=on><h2>ACCOUNT</h2><div class=tabs><button type=button data-t=in>SIGN IN</button><button type=button data-t=up>CREATE ACCOUNT</button></div>'\n"
        + "   + '<div class=user><label>USERNAME (SHOWN ON THE LEADERBOARD)</label><input name=username maxlength=14 autocomplete=username></div>'\n"
        + "   + '<label>EMAIL</label><input name=email type=email autocomplete=email required>'\n"
        + "   + '<label>PASSWORD</label><input name=password type=password minlength=6 required>'\n"
        + "   + '<button class=go type=submit>SIGN IN</button><div class=msg></div>'\n"
        + "   + '<div class=row><a class=forgot>Forgot password?</a><a class=close>Close</a></div></form>';\n"
        + "  document.body.appendChild(box);\n"
        + "  var f = box.querySelector('form'), msg = box.querySelector('.msg'), mode = 'in';\n"
        + "  var nice = function (e) { var c = (e && e.code) || ''; return {\n"
        + "    'auth/invalid-credential': 'Wrong email or password', 'auth/wrong-password': 'Wrong email or password', 'auth/user-not-found': 'No account with that email',\n"
        + "    'auth/email-already-in-use': 'That email already has an account. Sign in instead', 'auth/weak-password': 'Password needs at least 6 characters',\n"
        + "    'auth/invalid-email': 'That email doesn\\'t look right', 'auth/too-many-requests': 'Too many tries. Wait a minute and try again',\n"
        + "    'auth/operation-not-allowed': 'Email accounts aren\\'t switched on yet', 'auth/network-request-failed': 'No connection'}[c] || 'Something went wrong (' + c + ')'; };\n"
        + "  var setMode = function (m) { mode = m; box.querySelector('.user').style.display = m === 'up' ? 'block' : 'none';\n"
        + "    f.querySelector('.go').textContent = m === 'up' ? 'CREATE ACCOUNT' : 'SIGN IN';\n"
        + "    f.password.autocomplete = m === 'up' ? 'new-password' : 'current-password';\n"
        + "    box.querySelectorAll('.tabs button').forEach(function (b) { b.className = b.dataset.t === m ? '' : 'off'; }); msg.textContent = ''; };\n"
        + "  box.querySelectorAll('.tabs button').forEach(function (b) { b.onclick = function () { setMode(b.dataset.t); }; });\n"
        + "  box.querySelector('.close').onclick = function () { box.style.display = 'none'; };\n"
        + "  box.querySelector('.forgot').onclick = function () {\n"
        + "    if (!f.email.value) { msg.className = 'msg'; msg.textContent = 'Type your email first'; return; }\n"
        + "    auth.sendPasswordResetEmail(f.email.value).then(function () { msg.className = 'msg ok'; msg.textContent = 'Check your email for a reset link'; })\n"
        + "      .catch(function (e) { msg.className = 'msg'; msg.textContent = nice(e); }); };\n"
        + "  f.onsubmit = function (ev) { ev.preventDefault(); msg.className = 'msg'; msg.textContent = '...';\n"
        + "    var done = function () { box.style.display = 'none'; f.reset(); msg.textContent = ''; };\n"
        + "    var fail = function (e) { msg.className = 'msg'; msg.textContent = nice(e); };\n"
        + "    if (mode === 'in') { auth.signInWithEmailAndPassword(f.email.value, f.password.value).then(done, fail); return; }\n"
        + "    var name = f.username.value.trim();\n"
        + "    if (name.length < 3) { msg.textContent = 'Pick a username (3 or more letters)'; return; }\n"
        + "    auth.createUserWithEmailAndPassword(f.email.value, f.password.value).then(function (c) {\n"
        + "      return c.user.updateProfile({displayName: name}).then(function () { return c.user.getIdToken(true); })\n"
        + "        .then(function (t) { varAuth.name = name; varAuth.token = t; done(); });\n"
        + "    }).catch(fail); };\n"
        + "  varAuth.openEmail = function () { setMode('in'); box.style.display = 'flex'; setTimeout(function () { f.email.focus(); }, 50); };\n"
        + "  var fl = document.createElement('link'); fl.rel = 'stylesheet'; fl.href = 'https://fonts.googleapis.com/css2?family=Michroma&display=swap'; document.head.appendChild(fl);\n"
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
