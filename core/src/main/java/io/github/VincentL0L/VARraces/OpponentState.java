package io.github.VincentL0L.VARraces;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.ui.Image;

/**
 * Keeps track of the state of opponents by keeping track of positions of previous and current frame
 */
public class OpponentState {

    public Image img;
    public Vector2 lastPos;
    public Vector2 targetPos;
    public float interp;
    /** how fast it's moving (worked out from frame to frame), for bumps */
    public final Vector2 velocity = new Vector2();

    /**
     * creates a new OpponentState with an image and an initial position
     * @param i image of opponent
     * @param initPos positin of opponent
     */
    public OpponentState(Image i, Vector2 initPos) {
        img = i;
        lastPos = new Vector2(initPos);
        targetPos = new Vector2(initPos);
        interp = 1f;
    }
}
