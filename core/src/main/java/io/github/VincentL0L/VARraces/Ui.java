package io.github.VincentL0L.VARraces;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * Helpers for the game's look: sharp text on high resolution (Retina) screens
 * and the gilded 16-bit pixel-art panels and buttons (see tools/make_ui.py).
 *
 * The web version draws at the screen's real pixel count, so a Retina screen has
 * 2 pixels for every point. Menus are laid out in points (density = pixels per point)
 * so they stay the same size, and fonts come from large 64px font images that are scaled down:
 * Michroma for small text and the game's own racing font for titles, buttons and big numbers.
 */
public class Ui {
    /** size the font image was drawn at, see tools/make_font.py */
    private static final float FONT_BASE_SIZE = 64f;
    /** size of libGDX's old default font, which the screens were designed around */
    private static final float DEFAULT_FONT_SIZE = 15f;

    /** the screen's real pixels per CSS pixel: 2 on a Retina browser, 3 on most phones */
    public static float pixelRatio = 1f;
    /** screen pixels per layout point: the pixel ratio, shrunk a bit on small screens */
    public static float density = 1f;
    /** true on phones and tablets: shows the on-screen driving buttons */
    public static boolean touchScreen = false;
    /** menus are laid out for at least this many points; smaller screens (phones) scale it all down */
    private static final float MIN_WIDTH = 900f;
    private static final float MIN_HEIGHT = 620f;

    /** asks the player to type something (the web version uses the browser's prompt box) */
    public interface TextPrompt {
        /**
         * @param message question to show
         * @param current text already typed
         * @return what was typed, or null if cancelled
         */
        String ask(String message, String current);
    }

    /** set by the web launcher; null where a real keyboard is always available */
    public static TextPrompt textPrompt;

    /** signing in with Google (for ranked races); the web version provides it through Firebase */
    public interface Account {
        /** @return true if signing in is possible here */
        boolean available();
        /**
         * starts signing in: the browser only opens the Google window from a click, so this
         * arms it and the click's release opens it
         */
        void armSignIn();
        void signOut();
        /** opens the email and password form (sign in or create an account with a username) */
        void openEmailSignIn();
        /** @return the account id, or null when signed out */
        String uid();
        /** @return the account's name ("Vincent Lo"), or null */
        String name();
        /** @return a current Firebase ID token to show the server, or null */
        String token();
        /** @return why the last sign-in failed, or null */
        String error();
    }

    /** null where accounts aren't available (the desktop version) */
    public static Account account;

    /**
     * works out the density for the current window size: on a phone the menus would not
     * fit, so everything is drawn a little smaller there
     */
    public static void refreshDensity() {
        float cssWidth = Gdx.graphics.getWidth() / pixelRatio;
        float cssHeight = Gdx.graphics.getHeight() / pixelRatio;
        if (cssWidth <= 0 || cssHeight <= 0) {
            return;
        }
        density = pixelRatio * Math.min(1f, Math.min(cssWidth / MIN_WIDTH, cssHeight / MIN_HEIGHT));
    }

    private static Texture bodyTexture;
    private static Texture displayTexture;
    private static Texture outlinedTexture;

    /**
     * @return screen width in layout points
     */
    public static float width() {
        refreshDensity();
        return Gdx.graphics.getWidth() / density;
    }

    /**
     * @return screen height in layout points
     */
    public static float height() {
        refreshDensity();
        return Gdx.graphics.getHeight() / density;
    }

    /**
     * @return a ScreenViewport that measures in points, used for menus and HUD stages
     */
    public static ScreenViewport viewport() {
        ScreenViewport viewport = new ScreenViewport() {
            @Override
            public void update(int screenWidth, int screenHeight, boolean centerCamera) {
                // the density changes when a phone is turned or a window is resized
                refreshDensity();
                setUnitsPerPixel(1f / density);
                super.update(screenWidth, screenHeight, centerCamera);
            }
        };
        refreshDensity();
        viewport.setUnitsPerPixel(1f / density);
        return viewport;
    }

    /**
     * makes a sharp font for small text (Michroma)
     * @param size text height in points (the old default font was 15)
     * @return new font; all fonts share one texture so disposing them is optional
     */
    public static BitmapFont font(float size) {
        if (bodyTexture == null) {
            bodyTexture = loadFontTexture("fonts/michroma.png");
        }
        return makeFont("fonts/michroma.fnt", bodyTexture, size);
    }

    /**
     * makes the game's own racing font for titles, buttons and big numbers (tools/make_speed_font.py)
     * @param size text height in points
     * @return new font
     */
    public static BitmapFont display(float size) {
        if (displayTexture == null) {
            displayTexture = loadFontTexture("fonts/speed.png");
        }
        return makeFont("fonts/speed.fnt", displayTexture, size);
    }

    /**
     * the racing font with a dark outline, for big text drawn over the track
     * @param size text height in points
     * @return new font
     */
    public static BitmapFont displayOutlined(float size) {
        if (outlinedTexture == null) {
            outlinedTexture = loadFontTexture("fonts/speed_outline.png");
        }
        return makeFont("fonts/speed_outline.fnt", outlinedTexture, size);
    }

    private static Texture loadFontTexture(String path) {
        Texture texture = new Texture(Gdx.files.internal(path), true);
        texture.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
        return texture;
    }

    private static BitmapFont makeFont(String fntPath, Texture texture, float size) {
        BitmapFontData data = new BitmapFontData(Gdx.files.internal(fntPath), false);
        BitmapFont font = new BitmapFont(data, new TextureRegion(texture), false);
        font.getData().setScale(size / FONT_BASE_SIZE);
        font.setUseIntegerPositions(false);
        return font;
    }

    /**
     * converts an old Label.setFontScale value into one for the sharp font
     * @param oldScale scale that was used with the 15px default font
     * @return scale to pass to Label.setFontScale
     */
    public static float fontScale(float oldScale) {
        return oldScale * DEFAULT_FONT_SIZE / FONT_BASE_SIZE;
    }

    /** points per pixel-art pixel for the gilded UI pieces */
    public static final float PIXEL = 3f;
    /** dark brown text used on gold buttons */
    public static final Color TEXT_DARK = new Color(0.17f, 0.08f, 0.03f, 1f);
    /** warm white for text on dark panels */
    public static final Color CREAM = new Color(1f, 0.95f, 0.84f, 1f);
    /** gold for titles and highlights */
    public static final Color GOLD = new Color(1f, 0.8f, 0.28f, 1f);

    private static final Map<String, Texture> uiTextures = new HashMap<>();

    /**
     * @param name file in assets/ui/gilded without .png
     * @return pixel-art texture with crisp (nearest) filtering, loaded once
     */
    private static Texture uiTexture(String name) {
        Texture texture = uiTextures.get(name);
        if (texture == null) {
            texture = new Texture(Gdx.files.internal("ui/gilded/" + name + ".png"));
            texture.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
            uiTextures.put(name, texture);
        }
        return texture;
    }

    /**
     * makes a stretchable pixel-art piece; the border sizes are in art pixels
     */
    public static NinePatch patch(String name, int left, int right, int top, int bottom) {
        NinePatch patch = new NinePatch(uiTexture(name), left, right, top, bottom);
        patch.scale(PIXEL, PIXEL);
        return patch;
    }

    /**
     * @return dark panel with a gold frame, for menus and the HUD
     */
    public static NinePatchDrawable panel() {
        return new NinePatchDrawable(patch("panel", 6, 6, 6, 6));
    }

    /**
     * @return thin gold frame with a dark middle, for bars
     */
    public static NinePatch frame() {
        return patch("frame", 4, 4, 4, 4);
    }

    /**
     * @param color fill color
     * @return solid color drawable
     */
    public static Drawable solid(Color color) {
        return new TextureRegionDrawable(new TextureRegion(uiTexture("pixel"))).tint(color);
    }

    /**
     * gives a uiskin.json skin the gilded 16-bit look and sharp fonts
     * @param skin loaded skin
     * @return the same skin
     */
    public static Skin style(Skin skin) {
        BitmapFont font = font(13);
        BitmapFont buttonFont = display(20);
        for (TextButtonStyle style : skin.getAll(TextButtonStyle.class).values()) {
            style.font = buttonFont;
            style.up = new NinePatchDrawable(patch("button", 3, 3, 3, 5));
            style.over = new NinePatchDrawable(patch("button_over", 3, 3, 3, 5));
            style.down = new NinePatchDrawable(patch("button_down", 3, 3, 3, 5));
            style.checked = null;
            style.disabled = new NinePatchDrawable(patch("button_disabled", 3, 3, 3, 5));
            style.fontColor = TEXT_DARK;
            style.overFontColor = TEXT_DARK;
            style.downFontColor = TEXT_DARK;
            style.disabledFontColor = new Color(0.3f, 0.25f, 0.2f, 1f);
        }
        for (LabelStyle style : skin.getAll(LabelStyle.class).values()) {
            style.font = font;
            style.fontColor = CREAM;
        }
        for (TextFieldStyle style : skin.getAll(TextFieldStyle.class).values()) {
            style.font = font;
            style.messageFont = font;
            style.fontColor = CREAM;
            style.messageFontColor = new Color(0.6f, 0.5f, 0.38f, 1f);
            style.background = new NinePatchDrawable(patch("field", 3, 3, 3, 3));
            style.focusedBackground = null;
            Drawable cursor = solid(GOLD);
            cursor.setMinWidth(2);
            style.cursor = cursor;
            style.selection = solid(new Color(0.75f, 0.5f, 0.1f, 0.6f));
        }
        for (ScrollPaneStyle style : skin.getAll(ScrollPaneStyle.class).values()) {
            style.background = null;
            Drawable knob = solid(GOLD);
            knob.setMinWidth(6);
            style.vScrollKnob = knob;
            style.vScroll = null;
            style.hScroll = null;
            style.hScrollKnob = null;
        }
        return skin;
    }
}
