package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;

/**
 * Building blocks for the lobby menus, in the same design language as the race HUD:
 * a gilded card, small gold section labels, recessed rows (like the HUD's screens)
 * and little status pills.
 */
public class Cards {
    public static final float CARD_WIDTH = 460f;
    public static final float ROW_HEIGHT = 42f;
    /** muted gold for section labels, same as the HUD's labels */
    public static final Color LABEL = new Color(0.85f, 0.65f, 0.3f, 1f);
    public static final Color ERROR = new Color(1f, 0.5f, 0.4f, 1f);
    public static final Color READY = new Color(0.27f, 0.66f, 0.32f, 1f);
    public static final Color WAITING = new Color(0.26f, 0.2f, 0.15f, 1f);
    public static final Color CPU = new Color(0.8f, 0.55f, 0.15f, 1f);

    private static LabelStyle kicker;
    private static LabelStyle body;
    private static LabelStyle bodyGold;
    private static LabelStyle pillDark;
    private static LabelStyle pillLight;

    private static void loadStyles() {
        if (kicker == null) {
            kicker = new LabelStyle(Ui.font(10), LABEL);
            body = new LabelStyle(Ui.font(13), Ui.CREAM);
            bodyGold = new LabelStyle(Ui.font(13), Ui.GOLD);
            pillDark = new LabelStyle(Ui.display(13), Ui.TEXT_DARK);
            pillLight = new LabelStyle(Ui.display(13), Ui.CREAM);
        }
    }

    /**
     * @return an empty gilded card; add rows to it, then put it on screen with center()
     */
    public static Table card() {
        Table card = new Table();
        card.setBackground(Ui.panel());
        card.pad(26, 34, 30, 34);
        card.defaults().width(CARD_WIDTH);
        return card;
    }

    /**
     * @return a full-screen table holding the card in the middle
     */
    public static Table center(Table card) {
        Table holder = new Table();
        holder.setFillParent(true);
        holder.add(card);
        return holder;
    }

    /**
     * @return a small, spaced-out gold label that names a section ("OPEN RACES")
     */
    public static Label kicker(String text) {
        loadStyles();
        return new Label(text, kicker);
    }

    /**
     * @return the big gold title
     */
    public static Label title(String text, float size) {
        return new Label(text, new LabelStyle(Ui.display(size), Ui.GOLD));
    }

    /**
     * @return normal text, cream or gold
     */
    public static Label text(String text, boolean gold) {
        loadStyles();
        return new Label(text, gold ? bodyGold : body);
    }

    /**
     * @return an empty recessed row, like the screens in the HUD
     */
    public static Table row() {
        Table row = new Table();
        row.setBackground(new NinePatchDrawable(Ui.patch("field", 3, 3, 3, 3)));
        row.pad(4, 14, 4, 10);
        return row;
    }

    /**
     * @return a little colored tag, like READY or CPU
     */
    public static Table pill(String text, Color background, boolean darkText) {
        loadStyles();
        Table pill = new Table();
        pill.setBackground(Ui.solid(background));
        pill.pad(3, 10, 2, 10);
        pill.add(new Label(text, darkText ? pillDark : pillLight));
        return pill;
    }

    /**
     * @param car a car sprite (car4-6 are the CPU colors)
     * @return the car's pixel-art sprite, standing up, sized for a row
     */
    public static Image carIcon(Texture car) {
        Image icon = new Image(car);
        icon.setSize(13, 26);
        return icon;
    }

    /**
     * @return a smaller gold button for secondary actions (Back, Leave, Join in a row)
     */
    public static TextButton smallButton(String text, Skin skin) {
        TextButtonStyle style = new TextButtonStyle(skin.get(TextButtonStyle.class));
        style.font = Ui.display(15);
        return new TextButton(text, style);
    }

    /**
     * @return a quiet placeholder for an empty list, centered in a row
     */
    public static Table emptyRow(String text) {
        Table row = row();
        Label label = new Label(text, new LabelStyle(Ui.font(12), LABEL));
        row.add(label).expandX().center();
        return row;
    }
}
