package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;

import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Picking the map, after Single Player or Multiplayer: one card per map with a preview,
 * its name and a line about it. The frosted track drifting behind is the highlighted map.
 */
public class MapSelectScreen implements Screen {
    private static final float TILE_WIDTH = 230f;
    /** the last map picked, highlighted first next time */
    private static String lastMap = TrackMap.CLASSIC;

    private final Game game;
    private final int selectedCar;
    private final boolean online;
    private final Stage stage;
    private final Skin skin;
    private final List<TrackMap> maps = TrackMap.all();
    private final List<Table> tiles = new ArrayList<>();
    private final List<Label> names = new ArrayList<>();
    private final List<Texture> previews = new ArrayList<>();
    private final TrackBackdrop[] backdrops;
    private int selected = 0;

    /**
     * @param game game
     * @param selectedCar car skin 1-3
     * @param online true after Multiplayer, false after Single Player
     */
    public MapSelectScreen(Game game, int selectedCar, boolean online) {
        this.game = game;
        this.selectedCar = selectedCar;
        this.online = online;
        stage = new Stage(Ui.viewport());
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        backdrops = new TrackBackdrop[maps.size()];
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).id.equals(lastMap)) {
                selected = i;
            }
        }
        createUI();
        select(selected);
        Gdx.input.setInputProcessor(stage);
    }

    private void createUI() {
        Table card = Cards.card();
        card.defaults().width(TILE_WIDTH * maps.size() + 16f * (maps.size() - 1));
        card.add(Cards.kicker(online ? "MULTIPLAYER" : "SINGLE PLAYER")).left().row();
        card.add(Cards.title("CHOOSE A TRACK", 40)).left().padTop(2).padBottom(16).row();

        Table row = new Table();
        for (int i = 0; i < maps.size(); i++) {
            row.add(tile(i)).width(TILE_WIDTH).fillY().padLeft(i == 0 ? 0 : 16);
        }
        card.add(row).row();

        TextButton back = Cards.smallButton("Back", skin);
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                game.setScreen(new MenuScreen(game, selectedCar));
            }
        });
        TextButton next = new TextButton("Next", skin);
        next.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                go();
            }
        });
        Table footer = new Table();
        footer.add(back).width(130).height(52);
        footer.add().expandX();
        footer.add(next).width(200).height(60);
        card.add(footer).padTop(20).row();
        stage.addActor(Cards.center(card));
    }

    /**
     * one map's card: preview, name and a line about it; click to highlight, again to go
     */
    private Table tile(final int index) {
        TrackMap map = maps.get(index);
        Texture preview = new Texture(Gdx.files.internal(map.preview));
        preview.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        previews.add(preview);

        Table tile = new Table();
        tile.pad(10, 10, 12, 10);
        tile.top();
        tile.setTouchable(Touchable.enabled);
        float imageWidth = TILE_WIDTH - 20f;
        tile.add(new Image(preview)).size(imageWidth, imageWidth * 9f / 16f).row();
        Label name = new Label(map.name.toUpperCase(), new LabelStyle(Ui.display(18), Ui.CREAM));
        tile.add(name).left().padTop(10).row();
        Label tagline = new Label(map.tagline, new LabelStyle(Ui.font(10), Cards.LABEL));
        tagline.setWrap(true);
        tile.add(tagline).width(imageWidth).left().padTop(4).row();
        tile.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (selected == index && getTapCount() >= 2) {
                    go();
                } else {
                    select(index);
                }
            }
        });
        tiles.add(tile);
        names.add(name);
        return tile;
    }

    /** gold frame and gold name on the highlighted map; the backdrop shows it too */
    private void select(int index) {
        selected = index;
        for (int i = 0; i < tiles.size(); i++) {
            boolean on = i == index;
            tiles.get(i).setBackground(on ? new NinePatchDrawable(Ui.frame())
                : new NinePatchDrawable(Ui.patch("field", 3, 3, 3, 3)));
            names.get(i).setColor(on ? Ui.GOLD : Ui.CREAM);
        }
    }

    private void go() {
        TrackMap map = maps.get(selected);
        lastMap = map.id;
        if (online) {
            game.setScreen(new MultiplayerScreen(game, selectedCar, map));
        } else {
            game.setScreen(new LobbyScreen(game, selectedCar, null, map));
        }
    }

    public void render(float delta) {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (backdrops[selected] == null) {
            backdrops[selected] = new TrackBackdrop(maps.get(selected));
            backdrops[selected].resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }
        backdrops[selected].render(delta);
        stage.act(delta);
        stage.getViewport().apply();
        stage.draw();
    }

    public void resize(int width, int height) {
        for (TrackBackdrop b : backdrops) {
            if (b != null) {
                b.resize(width, height);
            }
        }
        stage.getViewport().update(width, height, true);
    }

    public void hide() {
        dispose();
    }

    public void dispose() {
        for (TrackBackdrop b : backdrops) {
            if (b != null) {
                b.dispose();
            }
        }
        for (Texture t : previews) {
            t.dispose();
        }
        stage.dispose();
        skin.dispose();
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
