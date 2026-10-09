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
    private BitmapFont bannerFont;
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
        bannerFont = Ui.display(34);
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
        // left and right mirror each other: a tall panel on top, a strip of two screens under it
        float stripY = vh - MARGIN - sideHeight - STRIP_GAP - STRIP_HEIGHT;
        recordProgress(standings);
        renderStandings(standings, sideWidth, sideHeight);
        renderGaps(standings, MARGIN, stripY, sideWidth);
        renderMinimap(vw - MARGIN - sideWidth, vh - MARGIN - sideHeight, sideWidth, sideHeight);
        renderItemStrip(vw - MARGIN - sideWidth, stripY, sideWidth);
        renderCluster(player, standings);
        renderLapBanner(standings);
        renderDamageFlash();
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
                : map.battle ? health(r) : raceManager.isSprint() ? percent(r) : "LAP " + currentLap(r) + "/" + raceManager.getLaps();
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

    private static final float STRIP_GAP = 10f;
    private static final float STRIP_HEIGHT = BEZEL * 2 + SCREEN_HEIGHT;
    private static final float ROULETTE_TIME = 0.8f;
    private static final Color TRACK_EDGE = new Color(0.1f, 0.08f, 0.07f, 1f);
    private static final Color TRACK_ROAD = new Color(0.42f, 0.42f, 0.46f, 1f);
    private static final Color DOT_CPU = new Color(1f, 0.95f, 0.84f, 1f);
    private static final Color DOT_PLAYER = new Color(0.4f, 0.75f, 1f, 1f);

    // ---------------------------------------------------------------- gaps (left strip)

    /** each racer's progress over time, to work out time gaps: racer -> [time, progress, ...] */
    private final java.util.Map<String, com.badlogic.gdx.utils.FloatArray> history = new java.util.HashMap<>();
    private float lastSample = -1f;

    /** notes everyone's progress ten times a second while racing */
    private void recordProgress(List<RacerInfo> standings) {
        if (raceTime <= 0f || raceTime - lastSample < 0.1f) {
            return;
        }
        lastSample = raceTime;
        for (RacerInfo r : standings) {
            com.badlogic.gdx.utils.FloatArray h = history.get(r.name);
            if (h == null) {
                h = new com.badlogic.gdx.utils.FloatArray();
                history.put(r.name, h);
            }
            h.add(raceTime);
            h.add(r.progress);
        }
    }

    /**
     * @return when this racer had driven this far (seconds), or -1 if we don't know
     */
    private float timeAt(String racer, float progress) {
        com.badlogic.gdx.utils.FloatArray h = history.get(racer);
        if (h == null || h.size < 4) {
            return -1f;
        }
        for (int i = 2; i < h.size; i += 2) {
            if (h.get(i + 1) >= progress) {
                float t0 = h.get(i - 2), p0 = h.get(i - 1), t1 = h.get(i), p1 = h.get(i + 1);
                return p1 > p0 ? t0 + (t1 - t0) * (progress - p0) / (p1 - p0) : t1;
            }
        }
        return -1f;
    }

    /**
     * under the standings: how far, in seconds, the car ahead is and the car behind
     */
    private void renderGaps(List<RacerInfo> standings, float x, float y, float width) {
        int me = -1;
        for (int i = 0; i < standings.size(); i++) {
            if (standings.get(i).name.equals(playerId)) {
                me = i;
            }
        }
        String ahead = "--", behind = "--";
        if (map.battle) {
            // a battle: how many are still in, and how many we've knocked out
            int alive = 0, kos = 0;
            for (RacerInfo r : standings) {
                if (r.progress >= io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE) {
                    alive++;
                }
                if (r.name.equals(playerId)) {
                    kos = r.lapCount;
                }
            }
            batch.begin();
            panel.draw(batch, x, y, width, STRIP_HEIGHT);
            batch.end();
            float half = (width - BEZEL * 2 - 8f) / 2f;
            renderScreen(x + BEZEL, y + BEZEL, half, "ALIVE", alive + "/" + standings.size(), Ui.CREAM, tabFont);
            renderScreen(x + BEZEL + half + 8f, y + BEZEL, half, "KOS", String.valueOf(kos), Ui.GOLD, tabFont);
            return;
        }
        if (me >= 0 && raceTime > 0f && !standings.get(me).isFinished()) {
            RacerInfo mine = standings.get(me);
            if (me == 0) {
                ahead = "LEAD";
            } else {
                float t = timeAt(standings.get(me - 1).name, mine.progress);
                if (t >= 0f) {
                    ahead = String.format("+%.1f", raceTime - t);
                }
            }
            if (me < standings.size() - 1) {
                float t = timeAt(playerId, standings.get(me + 1).progress);
                if (t >= 0f) {
                    behind = String.format("-%.1f", raceTime - t);
                }
            }
        }
        batch.begin();
        panel.draw(batch, x, y, width, STRIP_HEIGHT);
        batch.end();
        float half = (width - BEZEL * 2 - 8f) / 2f;
        renderScreen(x + BEZEL, y + BEZEL, half, "AHEAD", ahead, Ui.CREAM, tabFont);
        renderScreen(x + BEZEL + half + 8f, y + BEZEL, half, "BEHIND", behind, Ui.CREAM, tabFont);
    }

    // ---------------------------------------------------------------- minimap (top right)

    private java.util.Map<String, com.badlogic.gdx.math.Vector2> cars = new java.util.HashMap<>();

    /**
     * @param centers where every car is this frame (map coordinates, by racer id)
     */
    public void setCars(java.util.Map<String, com.badlogic.gdx.math.Vector2> centers) {
        cars = centers;
    }

    /**
     * top right, mirroring the standings: the whole track in miniature, a dot for every car
     */
    private void renderMinimap(float x, float y, float width, float height) {
        batch.begin();
        panel.draw(batch, x, y, width, height);
        titleFont.setColor(Ui.GOLD);
        layout.setText(titleFont, "TRACK");
        titleFont.draw(batch, "TRACK", x + width - BEZEL - layout.width,
            y + height - BEZEL - (TITLE_HEIGHT - layout.height) / 2f + 2f);
        batch.end();

        // fit the 1920 x 1080 map into the space under the title, centered
        float areaW = width - BEZEL * 2 - 12f, areaH = height - BEZEL * 2 - TITLE_HEIGHT - 8f;
        float scale = Math.min(areaW / map.width, areaH / map.height);
        float ox = x + (width - map.width * scale) / 2f;
        float oy = y + BEZEL + 4f + (areaH - map.height * scale) / 2f;

        List<com.badlogic.gdx.math.Vector2> path = map.waypoints;
        // a sprint's line stops at the finish (the last waypoint is only the run-off)
        int stretches = map.pointToPoint ? path.size() - 2 : path.size();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        render.begin(ShapeRenderer.ShapeType.Filled);
        if (map.battle) {
            // an arena: just its floor
            render.setColor(TRACK_EDGE);
            render.rect(ox + 130f * scale, oy + 130f * scale, 1660f * scale, 1180f * scale);
            render.setColor(TRACK_ROAD);
            render.rect(ox + 140f * scale, oy + 140f * scale, 1640f * scale, 1160f * scale);
            stretches = 0;
        }
        for (int pass = 0; pass < 2; pass++) {
            render.setColor(pass == 0 ? TRACK_EDGE : TRACK_ROAD);
            float w = pass == 0 ? 7f : 4f;
            for (int i = 0; i < stretches; i++) {
                com.badlogic.gdx.math.Vector2 a = path.get(i), b = path.get((i + 1) % path.size());
                float ax = ox + a.x * scale, ay = oy + a.y * scale, bx = ox + b.x * scale, by = oy + b.y * scale;
                render.rectLine(ax, ay, bx, by, w);
                render.circle(ax, ay, w / 2f, 12);
            }
        }
        // the start line, and on a sprint a checkered flag at the finish
        render.setColor(Color.WHITE);
        render.rect(ox + (200f - 64f) * scale, oy + 300f * scale - 1f, 128f * scale, 2.5f);
        if (map.pointToPoint) {
            com.badlogic.gdx.math.Vector2 f = map.finishPoint();
            float fx = ox + f.x * scale, fy = oy + f.y * scale;
            for (int cy = 0; cy < 3; cy++) {
                for (int cx = 0; cx < 3; cx++) {
                    render.setColor((cx + cy) % 2 == 0 ? Color.WHITE : TRACK_EDGE);
                    render.rect(fx - 4.5f + cx * 3f, fy - 4.5f + cy * 3f, 3f, 3f);
                }
            }
        }
        // cars: everyone else first, you on top in gold
        for (java.util.Map.Entry<String, com.badlogic.gdx.math.Vector2> e : cars.entrySet()) {
            if (e.getKey().equals(playerId)) {
                continue;
            }
            dot(ox + e.getValue().x * scale, oy + e.getValue().y * scale, 3.5f,
                e.getKey().startsWith("CPU") ? DOT_CPU : DOT_PLAYER);
        }
        com.badlogic.gdx.math.Vector2 mine = cars.get(playerId);
        if (mine != null) {
            dot(ox + mine.x * scale, oy + mine.y * scale, 5f, Ui.GOLD);
        }
        render.end();
    }

    private void dot(float x, float y, float r, Color color) {
        render.setColor(TRACK_EDGE);
        render.circle(x, y, r + 1.5f, 16);
        render.setColor(color);
        render.circle(x, y, r, 16);
    }

    // ---------------------------------------------------------------- item + pause (right strip)

    private Texture[] allIcons;
    private float rouletteTimer = 0f;
    private boolean rouletteLanded = true;

    /**
     * @param icons every item's icon, for the roulette that spins when you pick one up
     */
    public void setItemIcons(Texture[] icons) {
        allIcons = icons;
    }

    /**
     * under the minimap, mirroring the gaps: a PAUSE screen and the ITEM screen
     * (click either; on a phone tap the item to use it)
     */
    private void renderItemStrip(float x, float y, float width) {
        rouletteTimer -= Gdx.graphics.getDeltaTime();
        if (itemIcon != null && rouletteTimer <= 0f && !rouletteLanded) {
            rouletteLanded = true;
            Sounds.play("item_land", 0.45f);
        }
        batch.begin();
        panel.draw(batch, x, y, width, STRIP_HEIGHT);
        batch.end();
        float half = (width - BEZEL * 2 - 8f) / 2f;
        float px = x + BEZEL, ix = x + BEZEL + half + 8f, sy = y + BEZEL;
        menuRows[0][0] = px;
        menuRows[0][1] = sy;
        menuRows[0][2] = half;
        menuRows[0][3] = SCREEN_HEIGHT;
        menuRows[1][0] = ix;
        menuRows[1][1] = sy;
        menuRows[1][2] = half;
        menuRows[1][3] = SCREEN_HEIGHT;

        float mx = Gdx.input.getX() / (float) Gdx.graphics.getWidth() * vw;
        float my = (1f - Gdx.input.getY() / (float) Gdx.graphics.getHeight()) * vh;
        boolean hover = !Ui.touchScreen && mx >= px && mx <= px + half && my >= sy && my <= sy + SCREEN_HEIGHT;

        batch.begin();
        screen.draw(batch, px, sy, half, SCREEN_HEIGHT);
        screen.draw(batch, ix, sy, half, SCREEN_HEIGHT);
        labelFont.setColor(hover || paused ? Ui.GOLD : LABEL);
        String pauseLabel = paused ? "RESUME" : "PAUSE";
        layout.setText(labelFont, pauseLabel);
        labelFont.draw(batch, pauseLabel, px + 12, sy + SCREEN_HEIGHT / 2f + layout.height / 2f);
        labelFont.setColor(LABEL);
        layout.setText(labelFont, Ui.touchScreen ? "ITEM" : "ITEM  E");
        labelFont.draw(batch, Ui.touchScreen ? "ITEM" : "ITEM  E", ix + 12, sy + SCREEN_HEIGHT / 2f + layout.height / 2f);
        // the icon: spinning through every item for a moment after a pickup, then the one you got
        Texture shown = itemIcon;
        if (itemIcon != null && rouletteTimer > 0f && allIcons != null) {
            shown = allIcons[(int) (clock * 14f) % allIcons.length];
        }
        float icon = SCREEN_HEIGHT - 8f;
        if (shown != null) {
            batch.draw(shown, ix + half - 8f - icon, sy + 4f, icon, icon);
            if (itemAmmo > 0 && rouletteTimer <= 0f) {
                // shots left in a blaster
                tabFont.setColor(Ui.GOLD);
                String n = "x" + itemAmmo;
                layout.setText(tabFont, n);
                tabFont.draw(batch, n, ix + half - 14f - icon - layout.width, sy + SCREEN_HEIGHT / 2f + layout.height / 2f);
            }
        } else {
            valueFont.setColor(LABEL);
            layout.setText(valueFont, "-");
            valueFont.draw(batch, "-", ix + half - 12f - layout.width, sy + SCREEN_HEIGHT / 2f + layout.height / 2f);
        }
        batch.end();
        render.begin(ShapeRenderer.ShapeType.Filled);
        drawIcon(paused ? ICON_RESUME : ICON_PAUSE, px + half - 12f - 14f, sy + (SCREEN_HEIGHT - 14f) / 2f, 2f,
            hover || paused ? Ui.GOLD : Ui.CREAM);
        render.end();
    }

    // ---------------------------------------------------------------- damage flash

    private float damageFlash = 0f;

    /** we just got hit: the screen edges flash red */
    public void flashDamage() {
        damageFlash = 1f;
    }

    private void renderDamageFlash() {
        if (damageFlash <= 0f) {
            return;
        }
        damageFlash -= Gdx.graphics.getDeltaTime() * 2.5f;
        float a = Math.max(0f, damageFlash) * 0.45f;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        render.begin(ShapeRenderer.ShapeType.Filled);
        Color edge = new Color(0.9f, 0.1f, 0.05f, a), clear = new Color(0.9f, 0.1f, 0.05f, 0f);
        float band = Math.min(vw, vh) * 0.18f;
        render.rect(0, 0, vw, band, edge, edge, clear, clear);
        render.rect(0, vh - band, vw, band, clear, clear, edge, edge);
        render.rect(0, 0, band, vh, edge, clear, clear, edge);
        render.rect(vw - band, 0, band, vh, clear, edge, edge, clear);
        render.end();
    }

    // ---------------------------------------------------------------- lap banner

    private int shownLap = 0;
    private float bannerTimer = 0f;
    private String bannerText = "";
    private static final float BANNER_TIME = 1.8f;

    /** "LAP 2/3" or "FINAL LAP" slides across the middle as you cross the line */
    private void renderLapBanner(List<RacerInfo> standings) {
        RacerInfo me = raceManager.getRacerInfoByName(playerId);
        if (me != null && !raceManager.isSprint() && !map.battle && !me.isFinished() && me.lapCount > shownLap) {
            shownLap = me.lapCount;
            int lap = me.lapCount + 1;
            bannerText = lap == raceManager.getLaps() ? "FINAL LAP" : "LAP " + lap + "/" + raceManager.getLaps();
            Sounds.play(lap == raceManager.getLaps() ? "final_lap" : "lap", 0.5f);
            bannerTimer = BANNER_TIME;
        }
        if (bannerTimer <= 0f) {
            return;
        }
        bannerTimer -= Gdx.graphics.getDeltaTime();
        float t = 1f - bannerTimer / BANNER_TIME;
        // slide in, hold, slide out
        float slide = t < 0.18f ? 1f - t / 0.18f : t > 0.82f ? -(t - 0.82f) / 0.18f : 0f;
        slide = slide * Math.abs(slide);
        float w = 360f, h = 64f;
        float x = (vw - w) / 2f + slide * vw * 0.6f, y = vh * 0.58f;
        batch.begin();
        panel.draw(batch, x, y, w, h);
        bannerFont.setColor(bannerText.equals("FINAL LAP") ? WARNING : Ui.GOLD);
        layout.setText(bannerFont, bannerText);
        bannerFont.draw(batch, bannerText, x + (w - layout.width) / 2f, y + (h + layout.height) / 2f);
        batch.end();
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

    /** a battle car's health, like "HP 72", or OUT once knocked out */
    private static String health(RacerInfo r) {
        return r.progress >= io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE ? "HP " + Math.round(r.progress - io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE) : "OUT";
    }

    /** how much of a sprint this racer has driven, like "64%" */
    private String percent(RacerInfo r) {
        float done = MathUtils.clamp(r.progress / Math.max(1f, raceManager.getTrackLength()), 0f, 1f);
        return Math.round(done * 100f) + "%";
    }

    private int currentLap(RacerInfo r) {
        return Math.max(1, Math.min(raceManager.getLaps(), r.lapCount + 1));
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
        if (map.battle) {
            // a battle: our health, red when it's low
            float hp = me == null ? 100f : Math.max(0f, me.progress - io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE);
            renderScreen(rightX, topRowY, moduleWidth, "HP", me != null && me.progress < io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.ALIVE ? "OUT" : String.valueOf(Math.round(hp)),
                hp > 50f ? Ui.CREAM : hp > 25f ? Ui.GOLD : WARNING);
        } else if (raceManager.isSprint()) {
            // a sprint has no laps: how much of the course is done
            renderScreen(rightX, topRowY, moduleWidth, "DIST", finished ? "DONE" : me != null ? percent(me) : "0%", Ui.CREAM);
        } else {
            String lap = finished ? "DONE" : (me != null ? currentLap(me) : 1) + "/" + raceManager.getLaps();
            renderScreen(rightX, topRowY, moduleWidth, "LAP", lap, Ui.CREAM);
        }
        // a battle counts down to its time limit
        float shownTime = map.battle ? Math.max(0f, io.github.VincentL0L.VARraces.Multiplayer.server.cpu.BattleSystem.TIME_LIMIT - raceTime) : raceTime;
        renderScreen(rightX, bottomRowY, moduleWidth, "TIME", formatTime(shownTime), finished ? Ui.GOLD : Ui.CREAM);

        // center pod: the speedometer sits high so it hangs below the housing
        renderSpeedometer(cx - gaugeSize / 2f, y + CLUSTER_HEIGHT + 6f - gaugeSize, player.getVelocity().len() * 0.4f);
    }

    /**
     * a recessed display: small gold label on the left, value on the right
     */
    private void renderScreen(float x, float y, float width, String label, String value, Color valueColor) {
        renderScreen(x, y, width, label, value, valueColor, valueFont);
    }

    private void renderScreen(float x, float y, float width, String label, String value, Color valueColor, BitmapFont valueFont) {
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
    private int itemAmmo = 0;

    /**
     * @param shots shots left in a blaster (0 for other items)
     */
    public void setItemAmmo(int shots) {
        itemAmmo = shots;
    }
    private String itemName;

    /**
     * @param icon the held item's icon, or null when not holding one
     * @param name its name
     */
    public void setItem(Texture icon, String name) {
        if (icon != null && itemIcon == null) {
            rouletteTimer = ROULETTE_TIME;      // just picked one up: spin the roulette
            rouletteLanded = false;
            Sounds.play("item_pickup", 0.5f);
        }
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
        bannerFont.dispose();
        render.dispose();
        gauge.dispose();
    }
}
