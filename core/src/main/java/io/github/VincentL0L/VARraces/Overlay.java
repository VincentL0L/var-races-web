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
    private static final float MAX_SPEED_MPH = 200f;

    // speedometer.png is pixel art: 48x44 pixels, needle pivot at (23.5, 23.5) from the top left
    private static final float GAUGE_SCALE = 4f;
    private static final float GAUGE_PIVOT_X = 23.5f;
    private static final float GAUGE_PIVOT_Y = 23.5f;
    private static final float NEEDLE_LENGTH = 14f;
    // needle sweeps clockwise from 210 degrees (0 mph) to -30 degrees (max)
    private static final float START_ANGLE = 210f;
    private static final float SWEEP = 240f;

    private static final Color GOLD = new Color(0.98f, 0.76f, 0.26f, 1f);
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
        speedFont = Ui.display(26);
        titleFont = Ui.display(16);
        panel = Ui.patch("panel", 6, 6, 6, 6);
        frame = Ui.frame();
        unitFont = Ui.font(10);
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
            float boxY = Ui.height() - boxHeight - 10;

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
        font.draw(batch, "Player: (" + (int)player.getX() + ", " + (int)player.getY() + ")", 20, 40);
        font.draw(batch, "Camera: (" + (int)camera.getX() + ", " + (int)camera.getY() + ")", 20, 60);
        batch.end();

        renderSpeedometer(player.getVelocity().len() * 0.4f);

        // narrower on small windows so it doesn't run under the speedometer
        // boost (mana) bar: gold frame, glowing blue fill
        float barWidth = Math.min(400, Ui.width() - 2 * (gauge.getWidth() * GAUGE_SCALE + 30));
        float barHeight = 36;
        float barX = (Ui.width() - barWidth) / 2f;
        float barY = 30;
        float inset = 4 * Ui.PIXEL;
        float fill = (barWidth - inset * 2) * MathUtils.clamp(player.getMana() / 100f, 0f, 1f);
        batch.begin();
        frame.draw(batch, barX, barY, barWidth, barHeight);
        titleFont.setColor(Ui.GOLD);
        titleFont.draw(batch, "BOOST", barX + 4, barY + barHeight + 24);
        batch.end();
        render.begin(ShapeRenderer.ShapeType.Filled);
        render.setColor(0.2f, 0.62f, 0.95f, 1f);
        render.rect(barX + inset, barY + inset, fill, barHeight - inset * 2);
        render.setColor(0.6f, 0.9f, 1f, 1f);
        render.rect(barX + inset, barY + barHeight - inset - Ui.PIXEL, fill, Ui.PIXEL);
        render.end();
    }

    /**
     * @return one leaderboard row, ex "1. CPU2  Lap 0/3"
     */
    private String leaderboardLine(int index, RacerInfo r) {
        return String.format("%d. %s   Lap %d/3", index + 1, r.name, r.lapCount);
    }

    /**
     * draws the gauge in the bottom right corner: pixel-art dial, moving needle and digital readout
     * @param speedMph current speed
     */
    private void renderSpeedometer(float speedMph) {
        // ease the needle toward the real speed so it sweeps like a physical gauge
        shownSpeed += (speedMph - shownSpeed) * Math.min(1f, Gdx.graphics.getDeltaTime() * 10f);

        float w = gauge.getWidth() * GAUGE_SCALE;
        float h = gauge.getHeight() * GAUGE_SCALE;
        float x = Ui.width() - w - 20;
        float y = 20;
        float pivotX = x + GAUGE_PIVOT_X * GAUGE_SCALE;
        float pivotY = y + h - GAUGE_PIVOT_Y * GAUGE_SCALE;

        batch.begin();
        batch.draw(gauge, x, y, w, h);
        batch.end();

        float ratio = MathUtils.clamp(shownSpeed / MAX_SPEED_MPH, 0f, 1f);
        float angle = (START_ANGLE - ratio * SWEEP) * MathUtils.degreesToRadians;
        float length = NEEDLE_LENGTH * GAUGE_SCALE;
        float tipX = pivotX + MathUtils.cos(angle) * length;
        float tipY = pivotY + MathUtils.sin(angle) * length;

        render.begin(ShapeRenderer.ShapeType.Filled);
        render.setColor(DARK);
        render.rectLine(pivotX, pivotY, tipX, tipY, 8f);
        render.setColor(GOLD);
        render.rectLine(pivotX, pivotY, tipX, tipY, 4f);
        render.setColor(DARK);
        render.circle(pivotX, pivotY, 9f, 20);
        render.setColor(GOLD);
        render.circle(pivotX, pivotY, 7f, 20);
        render.setColor(DARK);
        render.circle(pivotX, pivotY, 2.5f, 12);
        render.end();

        batch.begin();
        String speedText = String.valueOf(Math.round(speedMph));
        speedFont.setColor(GOLD);
        layout.setText(speedFont, speedText);
        speedFont.draw(batch, speedText, pivotX - layout.width / 2f, pivotY - 16f);
        unitFont.setColor(new Color(0.85f, 0.65f, 0.3f, 1f));
        layout.setText(unitFont, "MPH");
        unitFont.draw(batch, "MPH", pivotX - layout.width / 2f, pivotY - 44f);
        batch.end();
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
