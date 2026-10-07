package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Gdx;
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
 * Helpers that keep text and menus sharp on high resolution (Retina) screens.
 *
 * The web version draws at the screen's real pixel count, so a Retina screen has
 * 2 pixels for every point. Menus are laid out in points (density = pixels per point)
 * so they stay the same size, and fonts come from large 64px font images that are scaled down:
 * Michroma for small text and Racing Sans One for titles, buttons and big numbers.
 */
public class Ui {
    /** size the font image was drawn at, see tools/make_font.py */
    private static final float FONT_BASE_SIZE = 64f;
    /** size of libGDX's old default font, which the screens were designed around */
    private static final float DEFAULT_FONT_SIZE = 15f;

    /** screen pixels per layout point: 2 on a Retina browser, 1 on desktop */
    public static float density = 1f;

    private static Texture bodyTexture;
    private static Texture displayTexture;

    /**
     * @return screen width in layout points
     */
    public static float width() {
        return Gdx.graphics.getWidth() / density;
    }

    /**
     * @return screen height in layout points
     */
    public static float height() {
        return Gdx.graphics.getHeight() / density;
    }

    /**
     * @return a ScreenViewport that measures in points, used for menus and HUD stages
     */
    public static ScreenViewport viewport() {
        ScreenViewport viewport = new ScreenViewport();
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
     * makes a sharp racing-style font for titles, buttons and big numbers (Racing Sans One)
     * @param size text height in points
     * @return new font
     */
    public static BitmapFont display(float size) {
        if (displayTexture == null) {
            displayTexture = loadFontTexture("fonts/racing.png");
        }
        return makeFont("fonts/racing.fnt", displayTexture, size);
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

    /**
     * swaps the blurry fonts in uiskin.json for the sharp font
     * @param skin loaded skin
     * @return the same skin
     */
    public static Skin sharpen(Skin skin) {
        BitmapFont font = font(13);
        BitmapFont buttonFont = display(20);
        for (TextButtonStyle style : skin.getAll(TextButtonStyle.class).values()) {
            style.font = buttonFont;
        }
        for (LabelStyle style : skin.getAll(LabelStyle.class).values()) {
            style.font = font;
        }
        for (TextFieldStyle style : skin.getAll(TextFieldStyle.class).values()) {
            style.font = font;
            style.messageFont = font;
        }
        return skin;
    }
}
