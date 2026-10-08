package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.math.MathUtils;

import java.util.List;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;

/**
 * The race HUD, laid out like a car's digital driver display in the gilded pixel style.
 *
 * Across the top, symmetrical: the standings tower on the left, the instrument cluster in
 * the middle (speedometer pod hanging below it, boost meter and position on its left, lap
 * and stopwatch on its right), and a matching menu panel on the right (pause, restart, quit).
 * Every readout sits in a recessed "screen" with a gold bezel, like the speedometer's own
 * readout window. Drawn in screen points (see Ui), scaled down evenly on narrow windows.
 */
public class Overlay {
    private static final float MAX_SPEED_MPH = 240f;

    // speedometer.png is 64x64 pixel art (tools/make_speedometer.py), center at (32, 32) from the top left
    private static final float GAUGE_SCALE = Ui.PIXEL;
    private static final float GAUGE_CENTER = 32f;
    private static final float NEEDLE_LENGTH = 20f;
    // the readout window in the image: center (32, 44), and the MPH label below it at y 51.5
    private static final float READOUT_Y = 44f;
    private static final float UNIT_Y = 51.5f;
    // needle sweeps clockwise from 210 degrees (0 mph) to -30 degrees (max), matching the dots
    private static final float START_ANGLE = 210f;
    private static final float SWEEP = 240f;

    // cluster layout, in points
    private static final float CLUSTER_WIDTH = 640f;
    private static final float CLUSTER_HEIGHT = 124f;
    private static final float MARGIN = 12f;
    /** the top row (standings + cluster + menu) needs about this much width before it shrinks */
    private static final float FULL_WIDTH = 1240f;
    private static final float BEZEL = 6 * Ui.PIXEL;      // the gold frame of a panel
    private static final float SCREEN_HEIGHT = 38f;
    private static final int BOOST_SEGMENTS = 12;
    private static final float ROW_HEIGHT = 26f;
    private static final float TAB_SIZE = 26f;
    private static final float TITLE_HEIGHT = 30f;

    private static final Color NEEDLE = new Color(0.94f, 0.32f, 0.16f, 1f);
    private static final Color DARK = new Color(0.13f, 0.07f, 0.04f, 1f);
    private static final Color LABEL = new Color(0.85f, 0.65f, 0.3f, 1f);
    private static final Color TAB = new Color(0.8f, 0.55f, 0.15f, 1f);
    private static final Color SEGMENT_OFF = new Color(0.12f, 0.16f, 0.22f, 1f);
    private static final Color SEGMENT_ON = new Color(0.25f, 0.68f, 1f, 1f);
    private static final Color SEGMENT_ON_TOP = new Color(0.62f, 0.9f, 1f, 1f);
    private static final Color WARNING = new Color(1f, 0.36f, 0.22f, 1f);
    private static final Color HIGHLIGHT = new Color(1f, 0.8f, 0.28f, 0.22f);

    private SpriteBatch batch;
    private BitmapFont font;
    private BitmapFont valueFont;
    private BitmapFont tabFont;
    private BitmapFont speedFont;
    private BitmapFont titleFont;
    private BitmapFont labelFont;
    private BitmapFont warningFont;
    private NinePatch panel;
    private NinePatch button;
    private NinePatch buttonOver;
    private NinePatch buttonDown;
    private BitmapFont buttonFont;
    private NinePatch screen;
    private GlyphLayout layout = new GlyphLayout();
    private ShapeRenderer render;
    private Texture gauge;
    private RaceManager raceManager;
    private final TrackMap map;
    private String playerId = "";
    private float raceTime = 0f;
    private float shownSpeed = 0f;
    private boolean offTrack = false;
    private boolean paused = false;
    private boolean online = false;
    private float clock = 0f;
    // HUD space: screen points divided by the HUD scale
    private float hudScale = 1f;
    private float vw = 1f;
    private float vh = 1f;
    // clickable menu rows: x, y, width, height for each (pause, restart, quit)
    private final float[][] menuRows = new float[3][4];

    /**
     * creates a new overlay
     */
    public Overlay(RaceManager rm, TrackMap map) {
        raceManager = rm;
        this.map = map;
        batch = new SpriteBatch();
        font = Ui.font(12);
        valueFont = Ui.display(20);
        tabFont = Ui.display(16);
        speedFont = Ui.display(19);
        titleFont = Ui.display(16);
        labelFont = Ui.font(9);
        warningFont = Ui.displayOutlined(26);
        panel = Ui.patch("panel", 6, 6, 6, 6);
        button = Ui.patch("button", 3, 3, 3, 5);
        buttonOver = Ui.patch("button_over", 3, 3, 3, 5);
        buttonDown = Ui.patch("button_down", 3, 3, 3, 5);
        buttonFont = Ui.display(15);
        screen = Ui.patch("field", 3, 3, 3, 3);
        render = new ShapeRenderer();
        gauge = new Texture(Gdx.files.internal("ui/speedometer.png"));
        gauge.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        resize(Ui.width(), Ui.height());
    }

    /**
     * keeps the overlay drawn in screen points after the window is resized
     * @param width new width in points
     * @param height new height in points
     */
    public void resize(float width, float height) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
        render.getProjectionMatrix().setToOrtho2D(0, 0, width, height);
    }

    /**
     * draws the whole HUD
     * @param player the local player's car
     */
    public void render(Player player) {
        clock += Gdx.graphics.getDeltaTime();
        hudScale = Math.min(1f, Ui.width() / FULL_WIDTH);
        vw = Ui.width() / hudScale;
        vh = Ui.height() / hudScale;
        batch.getProjectionMatrix().setToOrtho2D(0, 0, vw, vh);
        render.getProjectionMatrix().setToOrtho2D(0, 0, vw, vh);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        List<RacerInfo> standings = raceManager.getSortedLeaderboard();
        // both side panels share one size so the top row is symmetrical
        int rows = Math.max(standings.size(), 4);
        float sideWidth = sidePanelWidth(standings);
        float sideHeight = BEZEL * 2 + TITLE_HEIGHT + ROW_HEIGHT * rows;
        renderStandings(standings, sideWidth, sideHeight);
        renderItemPanel(sideWidth, sideHeight);
        renderPauseButton(sideHeight);
        renderCluster(player, standings);
        if (offTrack && !paused) {
            renderWarning("RETURN TO TRACK");
        }
    }

    // ---------------------------------------------------------------- standings tower

    /**
     * top left: one row per racer with a gold position tab, name and lap or finish time;
     * your own row is highlighted
     */
    private void renderStandings(List<RacerInfo> standings, float width, float height) {
        float rowHeight = ROW_HEIGHT;
        float tab = TAB_SIZE;
        float titleHeight = TITLE_HEIGHT;
        float x = MARGIN;
        float y = vh - height - MARGIN;

        batch.begin();
        panel.draw(batch, x, y, width, height);
        titleFont.setColor(Ui.GOLD);
        layout.setText(titleFont, "STANDINGS");
        titleFont.draw(batch, "STANDINGS", x + BEZEL, y + height - BEZEL - (titleHeight - layout.height) / 2f + 2f);
        batch.end();

        float rowsTop = y + height - BEZEL - titleHeight;
        for (int i = 0; i < standings.size(); i++) {
            RacerInfo r = standings.get(i);
            float rowY = rowsTop - rowHeight * (i + 1);
            float middle = rowY + rowHeight / 2f;
            boolean me = r.name.equals(playerId);

            // the sprite batch switches blending off when it ends; the highlight needs it on
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            render.begin(ShapeRenderer.ShapeType.Filled);
            if (me) {
                render.setColor(HIGHLIGHT);
                render.rect(x + BEZEL - 3, rowY + 2, width - BEZEL * 2 + 6, rowHeight - 4);
            }
            // gold position tab, the leader's is brightest
            render.setColor(i == 0 ? Ui.GOLD : TAB);
            render.rect(x + BEZEL, rowY + 4, tab, rowHeight - 8);
            render.end();

            batch.begin();
            String pos = String.valueOf(i + 1);
            tabFont.setColor(Ui.TEXT_DARK);
            layout.setText(tabFont, pos);
            tabFont.draw(batch, pos, x + BEZEL + (tab - layout.width) / 2f, middle + layout.height / 2f);

            String name = displayName(r);
            font.setColor(me ? Ui.GOLD : Ui.CREAM);
            layout.setText(font, name);
            font.draw(batch, name, x + BEZEL + tab + 10, middle + layout.height / 2f);

            String status = r.isFinished() ? formatTime(r.finishTime)
                : "LAP " + currentLap(r) + "/" + RaceManager.LAPS;
            font.setColor(r.isFinished() ? Ui.GOLD : LABEL);
            layout.setText(font, status);
            font.draw(batch, status, x + width - BEZEL - layout.width, middle + layout.height / 2f);
            batch.end();
        }
    }

    /**
     * width that fits the longest standings row (and the menu rows, which use the same size)
     */
    private float sidePanelWidth(List<RacerInfo> standings) {
        float nameWidth = textWidth(font, "RESTART");
        for (RacerInfo r : standings) {
            nameWidth = Math.max(nameWidth, textWidth(font, displayName(r)));
        }
        return BEZEL * 2 + TAB_SIZE + 10 + nameWidth + 18 + textWidth(font, "0:00.00");
    }

    // ---------------------------------------------------------------- menu panel

    // 7x7 pixel-art icons for the menu buttons ('#' = a pixel)
    private static final String[] ICON_PAUSE = {
        ".##.##.", ".##.##.", ".##.##.", ".##.##.", ".##.##.", ".##.##.", ".##.##."};
    private static final String[] ICON_RESUME = {
        ".#.....", ".##....", ".###...", ".####..", ".###...", ".##....", ".#....."};

    /**
     * top right, mirroring the standings: the item you're holding, big, with its name
     * (tap it on a phone to use it)
     */
    private void renderItemPanel(float width, float height) {
        float x = vw - width - MARGIN;
        float y = vh - height - MARGIN;
        menuRows[1][0] = x;
        menuRows[1][1] = y;
        menuRows[1][2] = width;
        menuRows[1][3] = height;
        batch.begin();
        panel.draw(batch, x, y, width, height);
        titleFont.setColor(Ui.GOLD);
        layout.setText(titleFont, "ITEM");
        titleFont.draw(batch, "ITEM", x + width - BEZEL - layout.width,
            y + height - BEZEL - (TITLE_HEIGHT - layout.height) / 2f + 2f);
        float areaTop = y + height - BEZEL - TITLE_HEIGHT;
        float areaBottom = y + BEZEL;
        float cx = x + width / 2f;
        if (itemIcon != null) {
            float size = Math.min(64f, areaTop - areaBottom - 30f);
            float bob = MathUtils.sin(clock * 5f) * 2f;
            batch.draw(itemIcon, cx - size / 2f, areaBottom + 26f + bob, size, size);
            String name = itemName.toUpperCase() + (Ui.touchScreen ? "" : "   [E]");
            buttonFont.setColor(Ui.GOLD);
            layout.setText(buttonFont, name);
            buttonFont.draw(batch, name, cx - layout.width / 2f, areaBottom + 18f);
        } else {
            // an empty slot: a dim box outline with a question mark
            screen.draw(batch, cx - 28f, areaBottom + (areaTop - areaBottom) / 2f - 28f, 56f, 56f);
            labelFont.setColor(LABEL);
            layout.setText(labelFont, "?");
            labelFont.draw(batch, "?", cx - layout.width / 2f, areaBottom + (areaTop - areaBottom) / 2f + layout.height / 2f);
        }
        batch.end();
    }

    /**
     * a small pause button tucked under the item panel; it opens the pause menu
     */
    private void renderPauseButton(float sideHeight) {
        float size = 38f;
        float x = vw - MARGIN - size;
        float y = vh - MARGIN - sideHeight - 8f - size;
        menuRows[0][0] = x;
        menuRows[0][1] = y;
        menuRows[0][2] = size;
        menuRows[0][3] = size;
        float mx = Gdx.input.getX() / (float) Gdx.graphics.getWidth() * vw;
        float my = (1f - Gdx.input.getY() / (float) Gdx.graphics.getHeight()) * vh;
        boolean hover = !Ui.touchScreen && mx >= x && mx <= x + size && my >= y && my <= y + size;
        batch.begin();
        (hover ? buttonOver : button).draw(batch, x, y, size, size);
        batch.end();
        render.begin(ShapeRenderer.ShapeType.Filled);
        drawIcon(paused ? ICON_RESUME : ICON_PAUSE, x + (size - 14f) / 2f, y + (size - 14f) / 2f + 2f, 2f, Ui.TEXT_DARK);
        render.end();
    }

    /**
     * draws a pixel-art icon, one square per '#'
     * @param x left   @param y bottom   @param cell size of one pixel
     */
    private void drawIcon(String[] icon, float x, float y, float cell, Color color) {
        render.setColor(color);
        for (int row = 0; row < icon.length; row++) {
            for (int col = 0; col < icon[row].length(); col++) {
                if (icon[row].charAt(col) == '#') {
                    render.rect(x + col * cell, y + (icon.length - 1 - row) * cell, cell, cell);
                }
            }
        }
    }

    /**
     * @param screenX touch x from Gdx.input   @param screenY touch y from Gdx.input (top = 0)
     * @return what was hit: 0 the pause button, 1 the item panel, or -1
     */
    public int menuRowAt(int screenX, int screenY) {
        float x = screenX / (float) Gdx.graphics.getWidth() * vw;
        float y = (1f - screenY / (float) Gdx.graphics.getHeight()) * vh;
        for (int i = 0; i < menuRows.length; i++) {
            float[] r = menuRows[i];
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                return i;
            }
        }
        return -1;
    }

    private String displayName(RacerInfo r) {
        return r.name.equals(playerId) ? r.name + " (YOU)" : map.displayName(r.name);
    }

    private static int currentLap(RacerInfo r) {
        return Math.max(1, Math.min(RaceManager.LAPS, r.lapCount + 1));
    }

    // ---------------------------------------------------------------- instrument cluster

    /**
     * top middle: one gilded housing with the speedometer pod hanging out of the middle,
     * boost + position screens on the left, lap + stopwatch screens on the right
     */
    private void renderCluster(Player player, List<RacerInfo> standings) {
        float gaugeSize = gauge.getWidth() * GAUGE_SCALE;
        float cx = vw / 2f;
        float x = cx - CLUSTER_WIDTH / 2f;
        float y = vh - MARGIN - CLUSTER_HEIGHT;

        batch.begin();
        panel.draw(batch, x, y, CLUSTER_WIDTH, CLUSTER_HEIGHT);
        batch.end();

        // the side modules fill the space between the bezel and the gauge pod
        float moduleWidth = (CLUSTER_WIDTH - gaugeSize) / 2f - BEZEL - 14f;
        float leftX = x + BEZEL;
        float rightX = cx + gaugeSize / 2f + 14f;
        float topRowY = y + CLUSTER_HEIGHT - BEZEL - SCREEN_HEIGHT;
        float bottomRowY = y + BEZEL;

        // left: boost meter (top) and race position (bottom)
        renderBoost(leftX, topRowY, moduleWidth, player.getMana() / 100f);
        int position = 0;
        for (int i = 0; i < standings.size(); i++) {
            if (standings.get(i).name.equals(playerId)) {
                position = i + 1;
            }
        }
        String pos = position > 0 ? "P" + position + "/" + standings.size() : "--";
        renderScreen(leftX, bottomRowY, moduleWidth, "POS", pos, Ui.CREAM);

        // right: lap (top) and stopwatch (bottom)
        RacerInfo me = raceManager.getRacerInfoByName(playerId);
        boolean finished = me != null && me.isFinished();
        String lap = finished ? "DONE" : (me != null ? currentLap(me) : 1) + "/" + RaceManager.LAPS;
        renderScreen(rightX, topRowY, moduleWidth, "LAP", lap, Ui.CREAM);
        renderScreen(rightX, bottomRowY, moduleWidth, "TIME", formatTime(raceTime), finished ? Ui.GOLD : Ui.CREAM);

        // center pod: the speedometer sits high so it hangs below the housing
        renderSpeedometer(cx - gaugeSize / 2f, y + CLUSTER_HEIGHT + 6f - gaugeSize, player.getVelocity().len() * 0.4f);
    }

    /**
     * a recessed display: small gold label on the left, value on the right
     */
    private void renderScreen(float x, float y, float width, String label, String value, Color valueColor) {
        batch.begin();
        screen.draw(batch, x, y, width, SCREEN_HEIGHT);
        labelFont.setColor(LABEL);
        layout.setText(labelFont, label);
        labelFont.draw(batch, label, x + 12, y + SCREEN_HEIGHT / 2f + layout.height / 2f);
        valueFont.setColor(valueColor);
        layout.setText(valueFont, value);
        valueFont.draw(batch, value, x + width - 12 - layout.width, y + SCREEN_HEIGHT / 2f + layout.height / 2f);
        batch.end();
    }

    /**
     * a recessed display with a segmented, LED-style boost meter
     */
    private void renderBoost(float x, float y, float width, float amount) {
        batch.begin();
        screen.draw(batch, x, y, width, SCREEN_HEIGHT);
        labelFont.setColor(LABEL);
        layout.setText(labelFont, "BOOST");
        float labelWidth = layout.width;
        labelFont.draw(batch, "BOOST", x + 12, y + SCREEN_HEIGHT / 2f + layout.height / 2f);
        batch.end();

        float meterX = x + 12 + labelWidth + 10;
        float meterWidth = width - (meterX - x) - 12;
        float gap = 3f;
        float segment = (meterWidth - gap * (BOOST_SEGMENTS - 1)) / BOOST_SEGMENTS;
        float segmentHeight = SCREEN_HEIGHT - 20f;
        float segmentY = y + 10f;
        int lit = Math.round(MathUtils.clamp(amount, 0f, 1f) * BOOST_SEGMENTS);
        render.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < BOOST_SEGMENTS; i++) {
            float sx = meterX + i * (segment + gap);
            boolean on = i < lit;
            render.setColor(on ? SEGMENT_ON : SEGMENT_OFF);
            render.rect(sx, segmentY, segment, segmentHeight);
            if (on) {
                // lighter top edge on each lit segment, like a backlit LED
                render.setColor(SEGMENT_ON_TOP);
                render.rect(sx, segmentY + segmentHeight - Ui.PIXEL, segment, Ui.PIXEL);
            }
        }
        render.end();
    }

    /**
     * the round gauge: pixel-art dial, moving needle and digital readout
     * @param x left edge   @param y bottom edge   @param speedMph current speed
     */
    private void renderSpeedometer(float x, float y, float speedMph) {
        // ease the needle toward the real speed so it sweeps like a physical gauge
        shownSpeed += (speedMph - shownSpeed) * Math.min(1f, Gdx.graphics.getDeltaTime() * 10f);

        float w = gauge.getWidth() * GAUGE_SCALE;
        float h = gauge.getHeight() * GAUGE_SCALE;
        float pivotX = x + GAUGE_CENTER * GAUGE_SCALE;
        float pivotY = y + h - GAUGE_CENTER * GAUGE_SCALE;

        batch.begin();
        batch.draw(gauge, x, y, w, h);
        String speedText = String.valueOf(Math.round(speedMph));
        speedFont.setColor(Ui.GOLD);
        layout.setText(speedFont, speedText);
        float readoutY = y + h - READOUT_Y * GAUGE_SCALE;
        speedFont.draw(batch, speedText, pivotX - layout.width / 2f, readoutY + speedFont.getCapHeight() / 2f);
        labelFont.setColor(LABEL);
        layout.setText(labelFont, "MPH");
        float unitY = y + h - UNIT_Y * GAUGE_SCALE;
        labelFont.draw(batch, "MPH", pivotX - layout.width / 2f, unitY + labelFont.getCapHeight() / 2f);
        batch.end();

        // tapered red needle with a dark outline, then the hub on top
        float ratio = MathUtils.clamp(shownSpeed / MAX_SPEED_MPH, 0f, 1f);
        float angle = (START_ANGLE - ratio * SWEEP) * MathUtils.degreesToRadians;
        float dirX = MathUtils.cos(angle);
        float dirY = MathUtils.sin(angle);
        float length = NEEDLE_LENGTH * GAUGE_SCALE;
        render.begin(ShapeRenderer.ShapeType.Filled);
        drawNeedle(pivotX, pivotY, dirX, dirY, length + 3f, 6.5f, DARK);
        drawNeedle(pivotX, pivotY, dirX, dirY, length, 4f, NEEDLE);
        render.setColor(DARK);
        render.circle(pivotX, pivotY, 10f, 24);
        render.setColor(Ui.GOLD);
        render.circle(pivotX, pivotY, 7.5f, 24);
        render.setColor(DARK);
        render.circle(pivotX, pivotY, 3f, 16);
        render.end();
    }

    /**
     * draws a needle as a thin triangle from the hub to the tip
     * @param halfWidth half the needle's width at the hub
     */
    private void drawNeedle(float px, float py, float dirX, float dirY, float length, float halfWidth, Color color) {
        render.setColor(color);
        render.triangle(px - dirY * halfWidth, py + dirX * halfWidth,
            px + dirY * halfWidth, py - dirX * halfWidth,
            px + dirX * length, py + dirY * length);
    }

    private Texture itemIcon;
    private String itemName;

    /**
     * @param icon the held item's icon, or null when not holding one
     * @param name its name
     */
    public void setItem(Texture icon, String name) {
        itemIcon = icon;
        itemName = name;
    }

    /**
     * a flashing warning above the cluster
     */
    private void renderWarning(String text) {
        if ((int) (clock * 3f) % 2 == 1) {
            return;
        }
        batch.begin();
        warningFont.setColor(WARNING);
        layout.setText(warningFont, text);
        // just below the speedometer pod
        warningFont.draw(batch, text, (vw - layout.width) / 2f, vh - MARGIN - CLUSTER_HEIGHT - 110f);
        batch.end();
    }

    private float textWidth(BitmapFont f, String text) {
        layout.setText(f, text);
        return layout.width;
    }

    // ---------------------------------------------------------------- settings from GameScreen

    /**
     * @param seconds time to show on the stopwatch
     */
    public void setRaceTime(float seconds) {
        raceTime = seconds;
    }

    /**
     * @param id this player's racer name, highlighted in the standings
     */
    public void setPlayerId(String id) {
        playerId = id;
    }

    /**
     * @param value true while the game is paused (the menu shows RESUME)
     */
    public void setPaused(boolean value) {
        paused = value;
    }

    /**
     * @param value true in online races (RESTART becomes LEAVE)
     */
    public void setOnline(boolean value) {
        online = value;
    }

    /**
     * @param value true to flash RETURN TO TRACK
     */
    public void setOffTrack(boolean value) {
        offTrack = value;
    }

    /**
     * @param seconds race time
     * @return time like 0:29.41
     */
    public static String formatTime(float seconds) {
        int minutes = (int) (seconds / 60f);
        float rest = seconds - minutes * 60;
        return String.format("%d:%05.2f", minutes, rest);
    }

    /**
     * removes memory
     */
    public void dispose () {
        batch.dispose();
        font.dispose();
        valueFont.dispose();
        tabFont.dispose();
        speedFont.dispose();
        titleFont.dispose();
        buttonFont.dispose();
        labelFont.dispose();
        warningFont.dispose();
        render.dispose();
        gauge.dispose();
    }
}
