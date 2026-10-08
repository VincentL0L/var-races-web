package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.CarModel;

/**
 * The garage: pick one of the five cars (each with its own trade-offs, see CarModel) and
 * one of its three paint jobs. The title screen's race carries on behind it.
 */
public class GarageScreen implements Screen {
    private static final int SEGMENTS = 10;
    private static final Color BAR_OFF = new Color(0.16f, 0.12f, 0.09f, 1f);
    private static final Color[] BAR_ON = {
        new Color(1f, 0.8f, 0.28f, 1f),        // speed
        new Color(0.4f, 0.85f, 0.45f, 1f),     // acceleration
        new Color(0.35f, 0.7f, 1f, 1f),        // handling
        new Color(0.95f, 0.45f, 0.3f, 1f),     // weight
    };

    private final Game game;
    private final Stage stage = new Stage(Ui.viewport());
    private final Skin skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
    private final TitleBackdrop backdrop = new TitleBackdrop();
    /** the race behind is frosted, like behind the other menus */
    private final FrostedBackdrop frost = new FrostedBackdrop();
    private final Texture[] sprites = new Texture[CarModel.count()];
    private int model;
    private int paint;
    private Label name, tagline;
    private Image bigCar;
    private final Image[][] bars = new Image[4][SEGMENTS];
    private final Table[] swatches = new Table[CarModel.PAINTS];
    private final Image[] swatchCars = new Image[CarModel.PAINTS];

    /**
     * @param game game
     * @param car the car picked now (1-15)
     */
    public GarageScreen(Game game, int car) {
        this.game = game;
        model = Math.max(0, Math.min(CarModel.ALL.length - 1, (car - 1) / CarModel.PAINTS));
        paint = Math.max(0, (car - 1) % CarModel.PAINTS);
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = new Texture(Gdx.files.internal(CarModel.sprite(i + 1)));
            sprites[i].setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        }
        createUI();
        show(model, paint);
        Gdx.input.setInputProcessor(stage);
    }

    private void createUI() {
        Table card = Cards.card();
        card.defaults().width(560f);
        card.add(Cards.kicker("GARAGE")).left().row();
        name = Cards.title("", 44);
        card.add(name).left().padTop(2).padBottom(14).row();

        // left: the car on a pad, with arrows to flip through the models
        Table body = new Table();
        TextButton prev = Cards.smallButton("<", skin);
        TextButton next = Cards.smallButton(">", skin);
        prev.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                show((model + CarModel.ALL.length - 1) % CarModel.ALL.length, paint);
            }
        });
        next.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                show((model + 1) % CarModel.ALL.length, paint);
            }
        });
        Table pad = new Table();
        pad.setBackground(new NinePatchDrawable(Ui.patch("field", 3, 3, 3, 3)));
        bigCar = new Image();
        pad.add(bigCar).size(14 * 6, 28 * 6).pad(14, 28, 14, 28);
        Table showcase = new Table();
        showcase.add(prev).size(40, 40).padRight(8);
        showcase.add(pad);
        showcase.add(next).size(40, 40).padLeft(8);
        body.add(showcase).top();

        // right: what it's like, its stats, and its paint jobs
        Table info = new Table();
        info.defaults().left();
        tagline = new Label("", new Label.LabelStyle(Ui.font(11), Ui.CREAM));
        tagline.setWrap(true);
        info.add(tagline).width(250).padBottom(14).row();
        String[] statNames = {"SPEED", "ACCEL", "HANDLING", "WEIGHT"};
        for (int s = 0; s < 4; s++) {
            Table row = new Table();
            row.add(new Label(statNames[s], new Label.LabelStyle(Ui.font(9), Cards.LABEL))).width(78).left();
            for (int k = 0; k < SEGMENTS; k++) {
                bars[s][k] = new Image(Ui.solid(Color.WHITE));
                row.add(bars[s][k]).size(14, 12).padRight(3);
            }
            info.add(row).padBottom(8).row();
        }
        info.add(Cards.kicker("PAINT")).padTop(8).padBottom(6).row();
        Table paints = new Table();
        for (int p = 0; p < CarModel.PAINTS; p++) {
            final int choice = p;
            swatches[p] = new Table();
            swatches[p].setTouchable(Touchable.enabled);
            swatchCars[p] = new Image();
            swatches[p].add(swatchCars[p]).size(14 * 2, 28 * 2).pad(6, 12, 6, 12);
            swatches[p].addListener(new ClickListener() {
                public void clicked(InputEvent e, float x, float y) {
                    show(model, choice);
                }
            });
            paints.add(swatches[p]).padRight(10);
        }
        info.add(paints).row();
        body.add(info).top().padLeft(26);
        card.add(body).row();

        TextButton back = Cards.smallButton("Back", skin);
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MenuScreen(game, savedCar()));
            }
        });
        TextButton select = new TextButton("Select", skin);
        select.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                int car = CarModel.number(model, paint);
                saveCar(car);
                game.setScreen(new MenuScreen(game, car));
            }
        });
        Table footer = new Table();
        footer.add(back).width(130).height(52);
        footer.add().expandX();
        footer.add(select).width(220).height(60);
        card.add(footer).padTop(22).row();
        stage.addActor(Cards.center(card));
    }

    /** shows a model and paint: the big car, name, blurb, stat bars and the paint picker */
    private void show(int newModel, int newPaint) {
        model = newModel;
        paint = newPaint;
        CarModel car = CarModel.ALL[model];
        name.setText(car.name.toUpperCase());
        tagline.setText(car.tagline);
        bigCar.setDrawable(new TextureRegionDrawable(new TextureRegion(sprites[CarModel.number(model, paint) - 1])));
        int[] ratings = {car.speedRating(), car.accelRating(), car.handlingRating(), car.weightRating()};
        for (int s = 0; s < 4; s++) {
            for (int k = 0; k < SEGMENTS; k++) {
                bars[s][k].setColor(k < ratings[s] ? BAR_ON[s] : BAR_OFF);
            }
        }
        for (int p = 0; p < CarModel.PAINTS; p++) {
            swatchCars[p].setDrawable(new TextureRegionDrawable(new TextureRegion(sprites[CarModel.number(model, p) - 1])));
            swatches[p].setBackground(p == paint ? new NinePatchDrawable(Ui.frame())
                : new NinePatchDrawable(Ui.patch("field", 3, 3, 3, 3)));
        }
    }

    // ---------------------------------------------------------------- remembering the car

    private static Preferences prefs() {
        try {
            return Gdx.app.getPreferences("var-races");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @return the car picked last time (the Roadster to begin with)
     */
    public static int savedCar() {
        Preferences p = prefs();
        int car = p == null ? 0 : p.getInteger("car", 0);
        return car >= 1 && car <= CarModel.count() ? car : CarModel.number(1, 0);
    }

    private static void saveCar(int car) {
        Preferences p = prefs();
        if (p != null) {
            try {
                p.putInteger("car", car);
                p.flush();
            } catch (RuntimeException e) {
                // the browser may not allow storage; the car is still used this session
            }
        }
    }

    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        frost.begin();
        backdrop.render(delta, false);
        frost.end();
        frost.draw(1f);
        stage.getViewport().apply();
        stage.act(delta);
        stage.draw();
    }

    public void resize(int width, int height) {
        backdrop.resize(width, height);
        stage.getViewport().update(width, height, true);
    }

    public void hide() {
        dispose();
    }

    public void dispose() {
        stage.dispose();
        skin.dispose();
        backdrop.dispose();
        frost.dispose();
        for (Texture t : sprites) {
            t.dispose();
        }
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
