package io.github.VincentL0L.VARraces;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
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

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * Picking the map, after Single Player or when hosting an online race: one card per map
 * with a preview, its name and a line about it. The frosted track drifting behind is the
 * highlighted map.
 */
public class MapSelectScreen implements Screen {
    private static final float TILE_WIDTH = 210f;
    /** maps per row of the grid */
    private static final int COLUMNS = 4;
    /** the last map picked, highlighted first next time */
    private static String lastMap = TrackMap.CLASSIC;

    private final Game game;
    private final int selectedCar;
    /** hosting online: the open connection and whether the race is public (null = single player) */
    private final NetworkClient host;
    private final boolean hostPublic;
    private Label kicker;
    private boolean creating = false;
    private final Stage stage;
    private final Skin skin;
    private final List<TrackMap> maps = TrackMap.all();
    private final List<Table> tiles = new ArrayList<>();
    private final List<Label> names = new ArrayList<>();
    private final List<Texture> previews = new ArrayList<>();
    private final TrackBackdrop[] backdrops;
    private int selected = 0;
    private final TextButton[] tabs = new TextButton[3];
    private final Table[] grids = {new Table(), new Table(), new Table()};
    private com.badlogic.gdx.scenes.scene2d.ui.Cell<Table> gridCell;
    private Label sprintsNote;

    private static final String[] TAB_NOTES = {"", "One run from start to finish. No laps.",
        "Demolition derby: ram everyone, last car driving wins. Heavy cars hit harder."};

    /** shows one kind of map (CIRCUIT, SPRINT or BATTLE); its tab is lit */
    private void showTab(int category) {
        gridCell.setActor(grids[category]);
        for (int t = 0; t < 3; t++) {
            boolean on = t == category;
            tabs[t].getLabel().setColor(on ? Ui.TEXT_DARK : Cards.LABEL);
            tabs[t].setColor(on ? Color.WHITE : new Color(1f, 1f, 1f, 0.55f));
        }
        sprintsNote.setText(TAB_NOTES[category]);
        // highlight the first map of the tab unless the picked one is already on it
        if (maps.get(selected).category() != category) {
            for (int i = 0; i < maps.size(); i++) {
                if (maps.get(i).category() == category) {
                    select(i);
                    break;
                }
            }
        }
    }

    /**
     * single player: pick a map, then the lobby
     * @param game game
     * @param selectedCar car skin 1-3
     */
    public MapSelectScreen(Game game, int selectedCar) {
        this(game, selectedCar, null, false);
    }

    /**
     * hosting online: pick a map, then the room is created on it
     * @param client the open connection to the race server (null for single player)
     * @param isPublic true to list the race for everyone
     */
    public MapSelectScreen(Game game, int selectedCar, NetworkClient client, boolean isPublic) {
        this.game = game;
        this.selectedCar = selectedCar;
        this.host = client;
        this.hostPublic = isPublic;
        Sounds.music(Sounds.TITLE, true);
        stage = new Stage(Ui.viewport());
        Sounds.clickSounds(stage);
        skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
        backdrops = new TrackBackdrop[maps.size()];
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).id.equals(lastMap)) {
                selected = i;
            }
        }
        createUI();
        select(selected);
        showTab(maps.get(selected).category());
        Gdx.input.setInputProcessor(stage);
    }

    private void createUI() {
        Table card = Cards.card();
        int columns = Math.min(COLUMNS, maps.size());
        card.defaults().width(TILE_WIDTH * columns + 14f * (columns - 1));
        kicker = Cards.kicker(host == null ? "SINGLE PLAYER" : hostPublic ? "HOST A PUBLIC RACE" : "HOST A PRIVATE RACE");
        card.add(kicker).left().row();
        // the title, with tabs on the right: circuits (laps) or sprints (A to B)
        Table header = new Table();
        header.add(Cards.title("CHOOSE A TRACK", 40)).left().expandX();
        String[] tabNames = {"Circuits", "Sprints", "Battle"};
        for (int t = 0; t < 3; t++) {
            final int tab = t;
            tabs[t] = Cards.smallButton(tabNames[t], skin);
            tabs[t].addListener(new ClickListener() {
                public void clicked(InputEvent e, float x, float y) {
                    showTab(tab);
                }
            });
            header.add(tabs[t]).width(118).height(44).padLeft(8);
        }
        card.add(header).padTop(2).padBottom(14).row();

        // a grid of cards per kind of map, four to a row; one is shown at a time
        for (int g = 0; g < 3; g++) {
            int placed = 0;
            for (int i = 0; i < maps.size(); i++) {
                if (maps.get(i).category() != g) {
                    continue;
                }
                grids[g].add(tile(i)).width(TILE_WIDTH).fillY().padLeft(placed % columns == 0 ? 0 : 14).padBottom(12);
                placed++;
                if (placed % columns == 0) {
                    grids[g].row();
                }
            }
        }
        gridCell = card.add(grids[0]);
        card.row();
        sprintsNote = new Label("", new Label.LabelStyle(Ui.font(10), Cards.LABEL));
        card.add(sprintsNote).left().padBottom(4).row();

        TextButton back = Cards.smallButton("Back", skin);
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (host != null) {
                    game.setScreen(new MultiplayerScreen(game, selectedCar, host));
                } else {
                    game.setScreen(new MenuScreen(game, selectedCar));
                }
            }
        });
        TextButton next = new TextButton(host != null ? "Create" : "Next", skin);
        next.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                go();
            }
        });
        Table footer = new Table();
        footer.add(back).width(130).height(52);
        footer.add().expandX();
        footer.add(next).width(200).height(60);
        card.add(footer).padTop(8).row();
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
        tile.pad(8, 8, 10, 8);
        tile.top();
        tile.setTouchable(Touchable.enabled);
        float imageWidth = TILE_WIDTH - 16f;
        tile.add(new Image(preview)).size(imageWidth, imageWidth * 9f / 16f).row();
        Label name = new Label(map.name.toUpperCase(), new LabelStyle(Ui.display(18), Ui.CREAM));
        tile.add(name).left().padTop(6).row();
        Label tagline = new Label(map.tagline, new LabelStyle(Ui.font(9), Cards.LABEL));
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
        if (host != null) {
            // the server makes the room; render() moves on to the lobby once we're in it
            if (!creating && host.isConnected()) {
                creating = true;
                host.createRoom(hostPublic, selectedCar, map);
                kicker.setText("CREATING THE RACE...");
            }
        } else {
            game.setScreen(new LobbyScreen(game, selectedCar, null, map));
        }
    }

    public void render(float delta) {
        if (host != null) {
            host.update(delta);
            if (host.isInRoom()) {
                game.setScreen(new LobbyScreen(game, selectedCar, host, null));
                return;
            }
            if (host.getError() != null) {
                kicker.setText(host.getError().toUpperCase());
                kicker.setColor(Cards.ERROR);
                creating = false;
            }
        }
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
