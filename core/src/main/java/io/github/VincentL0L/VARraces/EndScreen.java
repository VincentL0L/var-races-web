package io.github.VincentL0L.VARraces;

import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.RacerInfo;
/**
 * displays leaderboard at the end of the race
 */
public class EndScreen implements Screen {
    private Stage stage;
    private Game game;
    private Skin skin;
    private int selectedCar;
    private int finalPosition;
    private List<RacerInfo> leaderboard;
    /**
     * 4-arg constructor for EndScreen
     * @param game sets game to game 
     * @param selectedCar sets gameScreen selected car to end screen
     * @param finalPosition final position for this client
     * @param leaderboard total leaderboard
     */
    public EndScreen(Game game, int selectedCar, int finalPosition, List<RacerInfo> leaderboard) {
        this.game = game;
        this.selectedCar = selectedCar;
        this.finalPosition = finalPosition;
        this.leaderboard = leaderboard;
        stage = new Stage(Ui.viewport());
        skin = Ui.sharpen(new Skin(Gdx.files.internal("ui/uiskin.json")));
        
        createUI();
        Gdx.input.setInputProcessor(stage);
    }
    /**
     * renders leadeboard with List<RacerInfo>
     */
    private void createUI() {
        Table mainTable = new Table();
        mainTable.setFillParent(true);

        Label.LabelStyle titleStyle = new Label.LabelStyle(Ui.display(36), Color.GOLD);
        Label titleLabel = new Label("Race Complete!", titleStyle);

        Label.LabelStyle resultStyle = new Label.LabelStyle(Ui.font(13), Color.WHITE);
        Label positionLabel = new Label(String.format("Position: %d", finalPosition), resultStyle);

        Table leaderboardTable = new Table();
        leaderboardTable.setBackground(skin.newDrawable("white", new Color(0.2f, 0.2f, 0.2f, 0.8f)));
        
        Label.LabelStyle headerStyle = new Label.LabelStyle(Ui.font(13), Color.GOLD);
        leaderboardTable.add(new Label("Pos", headerStyle)).pad(10);
        leaderboardTable.add(new Label("Player", headerStyle)).pad(10).row();

        Label.LabelStyle entryStyle = new Label.LabelStyle(Ui.font(13), Color.WHITE);
        int position = 1;
        for (RacerInfo racer : leaderboard) {
            Color rowColor = position == finalPosition ? Color.YELLOW : Color.WHITE;
            Label posLabel = new Label(String.valueOf(position), entryStyle);
            Label nameLabel = new Label(racer.name, entryStyle);
            
            if (racer.lapCount < 3) {
                rowColor = position == finalPosition ? 
                          new Color(1f, 1f, 0f, 0.5f) :
                          new Color(1f, 1f, 1f, 0.5f);   
            }
            
            posLabel.setColor(rowColor);
            nameLabel.setColor(rowColor);
            
            leaderboardTable.add(posLabel).pad(5);
            leaderboardTable.add(nameLabel).pad(5).row();
            position++;
        }

        ScrollPane scrollPane = new ScrollPane(leaderboardTable, skin);
        scrollPane.setFadeScrollBars(false);

        mainTable.add(titleLabel).padBottom(30).row();
        //mainTable.add(timeLabel).padBottom(10).row();
        mainTable.add(positionLabel).padBottom(30).row();
        mainTable.add(scrollPane).width(400).height(200).padBottom(30).row();

        TextButton menuButton = new TextButton("Main Menu", skin);
        menuButton.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MenuScreen(game, selectedCar));
            }
        });
        mainTable.add(menuButton).width(200).height(50).row();

        stage.addActor(mainTable);
    }

    /**
     * displays stage
     * @param delta time from last frame
     */
    public void render(float delta) {
        Gdx.gl.glClearColor(0.2f, 0.2f, 0.2f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        
        stage.act(delta);
        stage.draw();
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