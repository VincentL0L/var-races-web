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
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;

/**
 * Contains HUD elements: mana bar, leaderboard, speedometer, player coordinates.
 * Everything is drawn in screen points (see Ui).
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

    private static final Color GOLD = new Color(0.98f, 0.76f, 0.26f, 1f);
    private static final Color NEEDLE = new Color(0.94f, 0.32f, 0.16f, 1f);
    private static final Color DARK = new Color(0.13f, 0.07f, 0.04f, 1f);

    private SpriteBatch batch;
    private BitmapFont font;
    private BitmapFont speedFont;
    private BitmapFont titleFont;
    private NinePatch panel;
    private NinePatch frame;
    private BitmapFont unitFont;
    private GlyphLayout layout = new GlyphLayout();
    private ShapeRenderer render;
    private Texture gauge;
    private RaceManager raceManager;
    private float shownSpeed = 0f;

    /**
     * creates a new overlay
     */
    public Overlay(RaceManager rm) {
        raceManager = rm;
        batch = new SpriteBatch();
        font = Ui.font(12);
        font.setColor(Color.WHITE);
        speedFont = Ui.display(19);
        titleFont = Ui.display(16);
        panel = Ui.patch("panel", 6, 6, 6, 6);
        frame = Ui.frame();
        unitFont = Ui.font(9);
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
     * renders leaderboard, player coordinates, speedometer, and mana bar in order
     * @param player player being rendered
     * @param camera camera needed to find coordinates of player
     */
    public void render(Player player, CameraController camera) {
        if (raceManager != null) {
            List<RacerInfo> leaderboard = raceManager.getSortedLeaderboard();

            // fit the gilded panel to the widest line
            float pad = 20;
            float lineHeight = 20;
            layout.setText(titleFont, "LEADERBOARD");
            float boxWidth = layout.width;
            for (int i = 0; i < leaderboard.size(); i++) {
                layout.setText(font, leaderboardLine(i, leaderboard.get(i)));
                boxWidth = Math.max(boxWidth, layout.width);
            }
            boxWidth += pad * 2;
            float boxHeight = pad * 2 + 26 + lineHeight * leaderboard.size();
            float boxX = 10;
            float boxY = 10;

            batch.begin();
            panel.draw(batch, boxX, boxY, boxWidth, boxHeight);
            titleFont.setColor(Ui.GOLD);
            titleFont.draw(batch, "LEADERBOARD", boxX + pad, boxY + boxHeight - pad + 2);
            for (int i = 0; i < leaderboard.size(); i++) {
                RacerInfo r = leaderboard.get(i);
                font.setColor(i == 0 ? Ui.GOLD : Ui.CREAM);
                font.draw(batch, leaderboardLine(i, r), boxX + pad, boxY + boxHeight - pad - 26 - lineHeight * i);
            }
            batch.end();
        }

        batch.begin();
        font.setColor(Ui.CREAM);
        String playerText = "Player: (" + (int)player.getX() + ", " + (int)player.getY() + ")";
        String cameraText = "Camera: (" + (int)camera.getX() + ", " + (int)camera.getY() + ")";
        layout.setText(font, playerText);
        font.draw(batch, playerText, Ui.width() - layout.width - 16, Ui.height() - 14);
        layout.setText(font, cameraText);
        font.draw(batch, cameraText, Ui.width() - layout.width - 16, Ui.height() - 34);
        batch.end();

        renderSpeedometer(player.getVelocity().len() * 0.4f);

        // narrower on small windows so it doesn't run under the speedometer
        // boost (mana) bar: vertical gold frame in the bottom right, glowing blue fill rising from the bottom
        float barWidth = 36;
        float barHeight = Math.min(240, Ui.height() * 0.4f);
        float barX = Ui.width() - barWidth - 24;
        float barY = 24;
        float inset = 4 * Ui.PIXEL;
        float fill = (barHeight - inset * 2) * MathUtils.clamp(player.getMana() / 100f, 0f, 1f);
        batch.begin();
        frame.draw(batch, barX, barY, barWidth, barHeight);
        titleFont.setColor(Ui.GOLD);
        layout.setText(titleFont, "BOOST");
        titleFont.draw(batch, "BOOST", barX + barWidth - layout.width, barY + barHeight + 24);
        batch.end();
        render.begin(ShapeRenderer.ShapeType.Filled);
        render.setColor(0.2f, 0.62f, 0.95f, 1f);
        render.rect(barX + inset, barY + inset, barWidth - inset * 2, fill);
        render.setColor(0.6f, 0.9f, 1f, 1f);
        render.rect(barX + inset, barY + inset, Ui.PIXEL, fill);
        render.end();
    }

    /**
     * @return one leaderboard row, ex "1. CPU2   Lap 1/1" or "1. CPU2   Finished"
     */
    private String leaderboardLine(int index, RacerInfo r) {
        if (r.lapCount >= RaceManager.LAPS) {
            return String.format("%d. %s   Finished", index + 1, r.name);
        }
        return String.format("%d. %s   Lap %d/%d", index + 1, r.name, r.lapCount + 1, RaceManager.LAPS);
    }

    /**
     * draws the gauge at the top middle of the screen: pixel-art dial, moving needle and digital readout
     * @param speedMph current speed
     */
    private void renderSpeedometer(float speedMph) {
        // ease the needle toward the real speed so it sweeps like a physical gauge
        shownSpeed += (speedMph - shownSpeed) * Math.min(1f, Gdx.graphics.getDeltaTime() * 10f);

        float w = gauge.getWidth() * GAUGE_SCALE;
        float h = gauge.getHeight() * GAUGE_SCALE;
        float x = (Ui.width() - w) / 2f;
        float y = Ui.height() - h - 20;
        float pivotX = x + GAUGE_CENTER * GAUGE_SCALE;
        float pivotY = y + h - GAUGE_CENTER * GAUGE_SCALE;

        batch.begin();
        batch.draw(gauge, x, y, w, h);
        String speedText = String.valueOf(Math.round(speedMph));
        speedFont.setColor(GOLD);
        layout.setText(speedFont, speedText);
        float readoutY = y + h - READOUT_Y * GAUGE_SCALE;
        speedFont.draw(batch, speedText, pivotX - layout.width / 2f, readoutY + speedFont.getCapHeight() / 2f);
        unitFont.setColor(new Color(0.85f, 0.65f, 0.3f, 1f));
        layout.setText(unitFont, "MPH");
        float unitY = y + h - UNIT_Y * GAUGE_SCALE;
        unitFont.draw(batch, "MPH", pivotX - layout.width / 2f, unitY + unitFont.getCapHeight() / 2f);
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
        render.setColor(GOLD);
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

    /**
     * removes memory
     */
    public void dispose () {
        batch.dispose();
        font.dispose();
        speedFont.dispose();
        titleFont.dispose();
        unitFont.dispose();
        render.dispose();
        gauge.dispose();
    }
}
