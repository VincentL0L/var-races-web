package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

import io.github.VincentL0L.VARraces.Multiplayer.client.PixmapTrack;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarBody;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CpuTraffic;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Opponent;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.Waypoints;

/**
 * The title screen, like a 16-bit arcade game's attract mode: the classic track seen up
 * close in crisp pixel art, with a pack of cars racing round it forever (past the animals
 * watching at the start line) and the camera following the action. On top, the gilded
 * VAR RACES sign.
 */
public class TitleBackdrop {
    private static final int RACERS = 5;
    /** how quickly the camera catches up with the car it follows */
    private static final float FOLLOW = 1.6f;
    /** the camera starts on the animals by the start line */
    private static final float START_X = 420f, START_Y = 330f;

    private final TrackMap map = TrackMap.get(TrackMap.CLASSIC);
    private final OrthographicCamera camera = new OrthographicCamera();
    // zoomed in a lot more than in a race, so the pixel art reads big and chunky
    private final Stage stage = new Stage(new ExtendViewport(470, 264, camera));
    private final Background background = new Background(stage, map);
    private final List<Opponent> cars = new ArrayList<>();
    private final Texture[] skins = new Texture[RACERS];
    private final PixmapTrack track = new PixmapTrack(map.roadMask);
    private final CpuTraffic traffic;
    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private static final Color SHADE = new Color(0.05f, 0.03f, 0.02f, 0.45f);
    private static final Color CLEAR = new Color(0.05f, 0.03f, 0.02f, 0f);
    private final Texture white;
    private final NinePatch panel = Ui.patch("panel", 6, 6, 6, 6);
    private final BitmapFont title = Ui.displayOutlined(80);
    private final GlyphLayout layout = new GlyphLayout();
    private float clock = 0f;
    private float camX = START_X, camY = START_Y;

    public TitleBackdrop() {
        for (int i = 0; i < RACERS; i++) {
            Vector2 slot = Waypoints.getGridSlot(i);
            Opponent car = new Opponent("CPU" + (i + 1), map.waypoints, slot);
            car.setEndless(true);
            cars.add(car);
            skins[i] = new Texture(Gdx.files.internal("ui/car" + (i + 1) + ".png"));
            skins[i].setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        }
        traffic = new CpuTraffic(cars, track);
        Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixel.setColor(Color.WHITE);
        pixel.fill();
        white = new Texture(pixel);
        pixel.dispose();
        camera.position.set(camX, camY, 0);
    }

    /**
     * moves the race on and draws the track, the cars, a soft shade and the title sign
     * @param delta seconds since last frame
     * @param showTitle false on screens that have their own heading (the car picker)
     */
    public void render(float delta, boolean showTitle) {
        clock += delta;
        // wait a moment on the animals, then the race starts and the camera follows the leader
        boolean racing = clock > 1.5f;
        traffic.update(delta, racing);
        if (racing) {
            Vector2 leader = cars.get(0).getPosition();
            for (Opponent car : cars) {
                if (car.getLapCount() * 100000 + car.getCurrentWaypointIndex() * 1000
                    > cars.get(0).getLapCount() * 100000 + cars.get(0).getCurrentWaypointIndex() * 1000) {
                    leader = car.getPosition();
                }
            }
            float smooth = 1f - (float) Math.exp(-FOLLOW * delta);
            camX += (leader.x + CarBody.WIDTH / 2f - camX) * smooth;
            camY += (leader.y + CarBody.LENGTH / 2f - camY) * smooth;
        }
        // keep the view inside the map
        float halfW = camera.viewportWidth / 2f, halfH = camera.viewportHeight / 2f;
        camera.position.set(MathUtils.clamp(camX, halfW, 1920f - halfW), MathUtils.clamp(camY, halfH, 1080f - halfH), 0);
        camera.update();

        stage.getViewport().apply();
        background.render(camera);
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        for (int i = 0; i < cars.size(); i++) {
            Opponent car = cars.get(i);
            Vector2 p = car.getPosition();
            batch.draw(skins[i], p.x, p.y, CarBody.WIDTH / 2f, CarBody.LENGTH / 2f, CarBody.WIDTH, CarBody.LENGTH,
                1f, 1f, car.getRotation() - 90f, 0, 0, skins[i].getWidth(), skins[i].getHeight(), false, false);
        }
        batch.end();

        // a soft shade toward the bottom so the buttons stand out, then the sign
        float w = Ui.width(), h = Ui.height();
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.rect(0, 0, w, h * 0.62f, SHADE, SHADE, CLEAR, CLEAR);
        shapes.end();
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        if (showTitle) {
            drawSign(w, h);
        }
        batch.end();
    }

    /** the VAR RACES sign: red like the one on the track, in a gold frame, gently bobbing */
    private void drawSign(float w, float h) {
        float scale = Math.min(1f, Math.min(w / 900f, h / 620f));
        title.getData().setScale(80f / 64f * scale);
        layout.setText(title, "VAR RACES");
        float signW = layout.width + 90f * scale, signH = layout.height + 64f * scale;
        float x = (w - signW) / 2f;
        float y = h - signH - 34f * scale + MathUtils.sin(clock * 1.6f) * 3f;
        // drop shadow, frame, red face, then a lighter band like a lit sign
        batch.setColor(0f, 0f, 0f, 0.35f);
        batch.draw(white, x + 8, y - 8, signW, signH);
        batch.setColor(Color.WHITE);
        panel.draw(batch, x, y, signW, signH);
        float inset = 6 * Ui.PIXEL;
        batch.setColor(0.86f, 0.16f, 0.12f, 1f);
        batch.draw(white, x + inset, y + inset, signW - inset * 2, signH - inset * 2);
        batch.setColor(1f, 0.42f, 0.32f, 1f);
        batch.draw(white, x + inset, y + signH - inset - 3 * Ui.PIXEL, signW - inset * 2, 3 * Ui.PIXEL);
        batch.setColor(0.6f, 0.08f, 0.06f, 1f);
        batch.draw(white, x + inset, y + inset, signW - inset * 2, 2 * Ui.PIXEL);
        batch.setColor(Color.WHITE);
        // light bulbs around the edge, chasing like a marquee
        int bulbs = (int) (signW / 22f);
        for (int i = 0; i < bulbs; i++) {
            boolean on = ((i + (int) (clock * 6f)) % 3) != 0;
            batch.setColor(on ? 1f : 0.55f, on ? 0.92f : 0.45f, on ? 0.55f : 0.2f, 1f);
            float bx = x + inset + 8 + i * (signW - inset * 2 - 16) / Math.max(1, bulbs - 1);
            batch.draw(white, bx - 3, y + signH - inset - 7f, 6, 6);
            batch.draw(white, bx - 3, y + inset + 4f, 6, 6);
        }
        batch.setColor(Color.WHITE);
        title.setColor(Color.WHITE);
        title.draw(batch, "VAR RACES", (w - layout.width) / 2f, y + (signH + layout.height) / 2f);
    }

    public void resize(int width, int height) {
        stage.getViewport().update(width, height, false);
    }

    public void dispose() {
        background.dispose();
        stage.dispose();
        batch.dispose();
        shapes.dispose();
        white.dispose();
        title.dispose();
        track.dispose();
        for (Texture t : skins) {
            t.dispose();
        }
    }
}
