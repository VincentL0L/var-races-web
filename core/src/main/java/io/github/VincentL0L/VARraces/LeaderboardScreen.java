package io.github.VincentL0L.VARraces;

import java.util.List;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import io.github.VincentL0L.VARraces.Multiplayer.client.NetworkClient;
import io.github.VincentL0L.VARraces.Multiplayer.server.cpu.TrackMap;

/**
 * The ranked leaderboard: the top 20 by rating, and your own record. You can sign out here.
 */
public class LeaderboardScreen implements Screen {
    private final Game game;
    private final int selectedCar;
    private final NetworkClient networkClient;
    private final Stage stage = new Stage(Ui.viewport());
    private final Skin skin = Ui.style(new Skin(Gdx.files.internal("ui/uiskin.json")));
    private final TrackBackdrop backdrop = new TrackBackdrop(TrackMap.get(TrackMap.CLASSIC));
    private Table list;
    private Label you;
    private boolean shown = false;
    private boolean leaving = true;

    /**
     * @param client the open connection (kept for the online menu)
     */
    public LeaderboardScreen(Game game, int selectedCar, NetworkClient client) {
        this.game = game;
        this.selectedCar = selectedCar;
        this.networkClient = client;
        Sounds.clickSounds(stage);
        client.requestTop();

        Table card = Cards.card();
        card.add(Cards.kicker("RANKED  /  SEASON 1")).left().row();
        card.add(Cards.title("TOP 20", 44)).left().padTop(2).padBottom(10).row();
        you = Cards.text("", true);
        card.add(you).left().padBottom(10).row();
        list = new Table();
        list.top();
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane scroll = new com.badlogic.gdx.scenes.scene2d.ui.ScrollPane(list, skin);
        scroll.setFadeScrollBars(false);
        card.add(scroll).height(330).row();

        TextButton back = Cards.smallButton("Back", skin);
        back.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                leaving = false;
                game.setScreen(new MultiplayerScreen(game, selectedCar, networkClient));
            }
        });
        TextButton signOut = Cards.smallButton("Sign out", skin);
        signOut.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                if (Ui.account != null) {
                    Ui.account.signOut();
                }
                leaving = false;
                game.setScreen(new MultiplayerScreen(game, selectedCar, networkClient));
            }
        });
        Table footer = new Table();
        footer.add(back).width(130).height(50);
        footer.add().expandX();
        footer.add(signOut).width(150).height(50);
        card.add(footer).padTop(14).row();
        stage.addActor(Cards.center(card));
        Gdx.input.setInputProcessor(stage);
    }

    private void fill() {
        list.clear();
        List<String[]> top = networkClient.getTop();
        if (top.isEmpty()) {
            list.add(Cards.emptyRow(networkClient.isRankedOn() ? "No ranked races yet. Be the first!" : "Ranked is offline right now"))
                .width(Cards.CARD_WIDTH).height(Cards.ROW_HEIGHT);
            return;
        }
        for (int i = 0; i < top.size(); i++) {
            String[] p = top.get(i);
            boolean me = p[0].equals(networkClient.getProfileName());
            Table row = Cards.row();
            row.add(Cards.text("#" + (i + 1), i < 3)).width(44).left();
            row.add(Cards.text(p[0], me)).expandX().left();
            row.add(Cards.pill(p[2], MultiplayerScreen.tierColor(p[2]), true)).padRight(12);
            row.add(Cards.text(p[1], true)).width(56).right();
            list.add(row).width(Cards.CARD_WIDTH - 14).height(Cards.ROW_HEIGHT).padBottom(5).row();
        }
    }

    public void render(float delta) {
        networkClient.update(delta);
        if (!shown && networkClient.isTopLoaded()) {
            shown = true;
            fill();
        }
        String name = networkClient.getProfileName();
        you.setText(name == null ? "Sign in to get a rank"
            : name + "   " + networkClient.getTier() + "  " + networkClient.getRating() + "   /   "
                + networkClient.getRankedRaces() + " races, " + networkClient.getRankedWins() + " wins");
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        backdrop.render(delta);
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
        if (leaving) {
            networkClient.stop();
        }
    }

    public void show() {}

    public void pause() {}

    public void resume() {}
}
