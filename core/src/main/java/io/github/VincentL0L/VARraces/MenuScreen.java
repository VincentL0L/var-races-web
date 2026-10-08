package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

/**
 * The title screen: the 16-bit attract-mode scene (TitleBackdrop) with the VAR RACES sign,
 * and the Single Player / Multiplayer / Skin / Exit buttons
 */
public class MenuScreen implements Screen {

    private Stage stage;
    private Skin button;
    private final TitleBackdrop backdrop = new TitleBackdrop();
    private Game game;
    private int car;

    /**
     * creates MenuScreen with a game and initilizes text button 
     * skin and background image and selected car choice
     * @param g game
     */
    public MenuScreen(Game g) {
        game = g;
        button = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        car = GarageScreen.savedCar();
    }

    /**
     * creates MenuScreen keeping a car that was already picked
     * @param g game
     * @param selectedCar car skin 1-3
     */
    public MenuScreen(Game g, int selectedCar) {
        this(g);
        car = selectedCar;
    }

    /**
     * main menu screen, includes single player and multiplayer buttons, exit button, and choose skin button
     */
    public void show() {
        clearStage();

        TextButton play = new TextButton("Single Player", button);
        TextButton online = new TextButton("Multiplayer", button);
        TextButton exit = new TextButton("Exit", button);
        TextButton skin = new TextButton("Garage", button);

        float buttonWidth = 220;
        float buttonHeight = 58;
        play.setSize(buttonWidth, buttonHeight);
        online.setSize(buttonWidth, buttonHeight);
        exit.setSize(buttonWidth, buttonHeight);
        skin.setSize(buttonWidth, buttonHeight);

        // a column of buttons under the sign
        float x = Ui.width() * 0.5f - buttonWidth * 0.5f;
        float top = Math.min(Ui.height() - 250f, 380f);
        float gap = Math.min(76f, (top - 30f) / 3f);
        play.setPosition(x, top);
        online.setPosition(x, top - gap);
        skin.setPosition(x, top - gap * 2);
        exit.setPosition(x, top - gap * 3);

        stage.addActor(play);
        stage.addActor(online);
        stage.addActor(exit);
        stage.addActor(skin);

        play.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MapSelectScreen(game, car));
            }
        });

        online.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MultiplayerScreen(game, car));
            }
        });

        exit.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                Gdx.app.exit();
            }
        });

        skin.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
               game.setScreen(new GarageScreen(game, car));
            }
        });

    }

    /**
     * clears stage
     */
    public void clearStage(){
        stage = new Stage(Ui.viewport());
        Gdx.input.setInputProcessor(stage);
    }

    /**
     * automatically called by LibGDX, draws stage onto screen
     * @param delta time since last frame
     */
    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        backdrop.render(delta, true);
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }

    /**
     * resizes stage
     * @param width new width
     * @param height new height
     */
    public void resize(int width, int height) {
        backdrop.resize(width, height);
        stage.getViewport().update(width, height, true);
    }

    /**
     * automatically called by LibGDX when switching screens, since 
     * there is no need to go back to MenuScreen we can remove memory
     */
    public void hide() {
        dispose();
    }

    /**
     * clears memory
     */
    public void dispose() {
        stage.dispose();
        button.dispose();
        backdrop.dispose();
    }

    // abstract methods we need to declare but arent used

    public void pause() {}

    public void resume() {}

}
