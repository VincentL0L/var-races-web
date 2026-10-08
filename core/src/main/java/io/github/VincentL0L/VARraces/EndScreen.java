package io.github.VincentL0L.VARraces;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RaceManager;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
/**
 * displays leaderboard at the end of the race
 */
public class EndScreen implements Screen {
    private static final float CHECKER_SIZE = 56f;
    private static final float CHECKER_SPEED = 14f;
    private static final Color CHECKER_LIGHT = new Color(0.93f, 0.9f, 0.84f, 1f);
    private static final Color CHECKER_DARK = new Color(0.1f, 0.08f, 0.07f, 1f);
    private final ShapeRenderer checkers = new ShapeRenderer();
    private float checkerOffset = 0f;

    private Stage stage;
    private Game game;
    private Skin skin;
    private int selectedCar;
    private int finalPosition;
    private List<RacerInfo> leaderboard;
    private TrackMap map;
    /**
     * constructor for EndScreen
     * @param game sets game to game 
     * @param selectedCar sets gameScreen selected car to end screen
     * @param finalPosition final position for this client
     * @param leaderboard total leaderboard
     * @param map the map that was raced (for the CPUs' names)
     */
    public EndScreen(Game game, int selectedCar, int finalPosition, List<RacerInfo> leaderboard, TrackMap map) {
        this.game = game;
        this.selectedCar = selectedCar;
        this.finalPosition = finalPosition;
        this.leaderboard = leaderboard;
        this.map = map;
        stage = new Stage(Ui.viewport());
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        
        createUI();
        Gdx.input.setInputProcessor(stage);
    }
    /**
     * renders leadeboard with List<RacerInfo>
     */
    private void createUI() {
        Table mainTable = new Table();
        mainTable.setFillParent(true);

        Label.LabelStyle titleStyle = new Label.LabelStyle(Ui.display(36), Ui.GOLD);
        Label titleLabel = new Label("Race Complete!", titleStyle);

        Label.LabelStyle resultStyle = new Label.LabelStyle(Ui.font(13), Ui.CREAM);
        String result = "Position: " + finalPosition + " of " + leaderboard.size();
        if (finalPosition >= 1 && finalPosition <= leaderboard.size() && leaderboard.get(finalPosition - 1).isFinished()) {
            result += "     Time: " + Overlay.formatTime(leaderboard.get(finalPosition - 1).finishTime);
        }
        Label positionLabel = new Label(result, resultStyle);

        Table leaderboardTable = new Table();
        leaderboardTable.setBackground(Ui.panel());
        leaderboardTable.pad(15);
        
        Label.LabelStyle headerStyle = new Label.LabelStyle(Ui.font(13), Ui.GOLD);
        leaderboardTable.add(new Label("Pos", headerStyle)).pad(10);
        leaderboardTable.add(new Label("Player", headerStyle)).pad(10);
        leaderboardTable.add(new Label("Time", headerStyle)).pad(10).row();

        Label.LabelStyle entryStyle = new Label.LabelStyle(Ui.font(13), Ui.CREAM);
        int position = 1;
        for (RacerInfo racer : leaderboard) {
            Color rowColor = position == finalPosition ? Ui.GOLD : Ui.CREAM;
            Label posLabel = new Label(String.valueOf(position), entryStyle);
            Label nameLabel = new Label(map.displayName(racer.name), entryStyle);
            Label timeLabel = new Label(racer.isFinished() ? Overlay.formatTime(racer.finishTime) : "racing", entryStyle);

            // still on track when you finished: dimmed
            if (!racer.isFinished()) {
                rowColor = new Color(rowColor.r, rowColor.g, rowColor.b, 0.5f);
            }

            posLabel.setColor(rowColor);
            nameLabel.setColor(rowColor);
            timeLabel.setColor(rowColor);

            leaderboardTable.add(posLabel).pad(5);
            leaderboardTable.add(nameLabel).pad(5).left();
            leaderboardTable.add(timeLabel).pad(5).right().row();
            position++;
        }

        ScrollPane scrollPane = new ScrollPane(leaderboardTable, skin);
        scrollPane.setFadeScrollBars(false);

        mainTable.add(titleLabel).padBottom(30).row();
        //mainTable.add(timeLabel).padBottom(10).row();
        mainTable.add(positionLabel).padBottom(30).row();
        mainTable.add(scrollPane).width(460).height(260).padBottom(30).row();

        TextButton menuButton = new TextButton("Main Menu", skin);
        menuButton.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MenuScreen(game, selectedCar));
            }
        });
        mainTable.add(menuButton).width(220).height(56).row();

        // results sit in a gilded panel so they read clearly over the checkered flag
        mainTable.setFillParent(false);
        mainTable.setBackground(Ui.panel());
        mainTable.pad(30, 50, 34, 50);
        Table holder = new Table();
        holder.setFillParent(true);
        holder.add(mainTable);
        stage.addActor(holder);
    }

    /**
     * displays stage
     * @param delta time from last frame
     */
    public void render(float delta) {
        Gdx.gl.glClearColor(0.12f, 0.07f, 0.04f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        drawCheckers(delta);
        
        stage.act(delta);
        stage.draw();
    }

    /**
     * a finish-line checkerboard filling the screen, drifting slowly on a diagonal like a
     * waving flag, under a dark shade so the results stay easy to read
     */
    private void drawCheckers(float delta) {
        checkerOffset = (checkerOffset + delta * CHECKER_SPEED) % (CHECKER_SIZE * 2f);
        float w = Ui.width(), h = Ui.height();
        checkers.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        checkers.begin(ShapeRenderer.ShapeType.Filled);
        int cols = (int) (w / CHECKER_SIZE) + 3;
        int rows = (int) (h / CHECKER_SIZE) + 3;
        for (int cx = -2; cx < cols; cx++) {
            for (int cy = -2; cy < rows; cy++) {
                boolean light = ((cx + cy) & 1) == 0;
                checkers.setColor(light ? CHECKER_LIGHT : CHECKER_DARK);
                checkers.rect(cx * CHECKER_SIZE + checkerOffset, cy * CHECKER_SIZE + checkerOffset, CHECKER_SIZE, CHECKER_SIZE);
            }
        }
        checkers.end();
        // dark warm shade over the flag
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        checkers.begin(ShapeRenderer.ShapeType.Filled);
        checkers.setColor(0.1f, 0.06f, 0.03f, 0.62f);
        checkers.rect(0, 0, w, h);
        checkers.end();
    }

    /**
     * resizes stage
     * @param width width of new stage
     * @param height heigh of new stage
     */
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    /**
     * clears memory
     */
    public void dispose() {
        checkers.dispose();
        stage.dispose();
        skin.dispose();
    }


    public void show() {}

    public void pause() {}

    public void resume() {}

    public void hide() {
        dispose();
    }
} 