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
 * The menu screen of the game, calls game screen when play button is pressed
 */
public class MenuScreen implements Screen {

    private Stage stage;
    private Skin button;
    private Image bgImg;
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
        bgImg = new Image( new Texture("ui/menu.png"));
        bgImg.setFillParent(true);
        car = 1;
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
        TextButton skin = new TextButton("Skin", button);

        float buttonWidth = 220;
        float buttonHeight = 58;
        play.setSize(buttonWidth, buttonHeight);
        online.setSize(buttonWidth, buttonHeight);
        exit.setSize(buttonWidth, buttonHeight);
        skin.setSize(buttonWidth, buttonHeight);

        float x = Ui.width() * 0.5f - buttonWidth * 0.5f;
        play.setPosition(x, 330);
        online.setPosition(x, 250);
        skin.setPosition(x, 170);
        exit.setPosition(x, 90);

        stage.addActor(bgImg);
        stage.addActor(play);
        stage.addActor(online);
        stage.addActor(exit);
        stage.addActor(skin);

        play.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MapSelectScreen(game, car, false));
            }
        });

        online.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MapSelectScreen(game, car, true));
            }
        });

        exit.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                Gdx.app.exit();
            }
        });

        skin.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
               selectCar();
            }
        });

    }

    /**
     * select car skin menu, includes both button to choose and image of 3 different cars
     */
    private void selectCar(){
        clearStage();

        Image Car1img = new Image(new Texture("ui/car1.png"));
        Image Car2img = new Image(new Texture("ui/car2.png"));
        Image Car3img = new Image(new Texture("ui/car3.png"));
        TextButton Car1 = new TextButton("Car1", button);
        TextButton Car2 = new TextButton("Car2", button);
        TextButton Car3 = new TextButton("Car3", button);

        // the cars are small pixel art: show them 6x bigger (textures keep crisp nearest filtering)
        Car1img.setSize(Car1img.getWidth() * 6, Car1img.getHeight() * 6);
        Car2img.setSize(Car2img.getWidth() * 6, Car2img.getHeight() * 6);
        Car3img.setSize(Car3img.getWidth() * 6, Car3img.getHeight() * 6);
        Car1.setSize(140, 58);
        Car2.setSize(140, 58);
        Car3.setSize(140, 58);

        Car1img.setPosition(Ui.width() * 0.25f - Car1img.getWidth() * 0.5f, 300);
        Car2img.setPosition(Ui.width() * 0.50f - Car2img.getWidth() * 0.5f, 300);
        Car3img.setPosition(Ui.width() * 0.75f - Car3img.getWidth() * 0.5f, 300);
        Car1.setPosition(Ui.width() * 0.25f - Car1.getWidth() * 0.5f, 200);
        Car2.setPosition(Ui.width() * 0.50f - Car2.getWidth() * 0.5f, 200);
        Car3.setPosition(Ui.width() * 0.75f - Car3.getWidth() * 0.5f, 200);

        stage.addActor(bgImg);
        stage.addActor(Car1img);
        stage.addActor(Car2img);
        stage.addActor(Car3img);
        stage.addActor(Car1);
        stage.addActor(Car2);
        stage.addActor(Car3);

        Car1.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                car = 1;
                show();
            }
        });
         
        Car2.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                car = 2;
                show();
            }
        });
         
        Car3.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                car = 3;
                show();
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
        stage.act(delta);
        stage.draw();
    }

    /**
     * resizes stage
     * @param width new width
     * @param height new height
     */
    public void resize(int width, int height) {
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
    }

    // abstract methods we need to declare but arent used

    public void pause() {}

    public void resume() {}

}
