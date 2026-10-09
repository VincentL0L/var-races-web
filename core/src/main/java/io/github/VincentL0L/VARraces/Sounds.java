package io.github.VincentL0L.VARraces;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Table;

/**
 * All the game's sound (made by tools/MakeAudio.java): short effects in assets/audio/*.wav
 * and the music tracks (*.mp3). Effects load the first time they're played. One piece of
 * music plays at a time and carries on across screens until another is asked for.
 */
public class Sounds {
    public static final String TITLE = "music_title", RACE = "music_race", BATTLE = "music_battle", RESULTS = "music_results";
    private static final float MUSIC_VOLUME = 0.45f;

    private static final Map<String, Sound> effects = new HashMap<>();
    private static Music music;
    private static String musicName;
    private static boolean muted = false;

    /**
     * @param name an effect, like "click" or "explosion"
     * @return the loaded sound (null if it can't be loaded)
     */
    public static Sound get(String name) {
        Sound s = effects.get(name);
        if (s == null && !effects.containsKey(name)) {
            try {
                s = Gdx.audio.newSound(Gdx.files.internal("audio/" + name + ".wav"));
            } catch (RuntimeException e) {
                s = null;       // no audio on this device: just stay quiet
            }
            effects.put(name, s);
        }
        return s;
    }

    /**
     * plays an effect once
     * @param name the effect   @param volume 0..1
     */
    public static void play(String name, float volume) {
        play(name, volume, 1f);
    }

    public static void play(String name) {
        play(name, 0.6f, 1f);
    }

    /**
     * @param pitch 1 = as recorded (a little variety keeps repeated effects from sounding robotic)
     */
    public static void play(String name, float volume, float pitch) {
        if (muted) {
            return;
        }
        Sound s = get(name);
        if (s != null) {
            s.play(volume, pitch, 0f);
        }
    }

    /**
     * starts a piece of music (if it isn't already the one playing)
     * @param name TITLE, RACE, BATTLE or RESULTS
     * @param loop false for one-shots like the results fanfare
     */
    public static void music(String name, boolean loop) {
        if (name.equals(musicName) && music != null) {
            return;
        }
        stopMusic();
        try {
            music = Gdx.audio.newMusic(Gdx.files.internal("audio/" + name + ".mp3"));
            music.setLooping(loop);
            music.setVolume(muted ? 0f : MUSIC_VOLUME);
            music.play();
            musicName = name;
        } catch (RuntimeException e) {
            music = null;
        }
    }

    public static void stopMusic() {
        if (music != null) {
            music.stop();
            music.dispose();
            music = null;
        }
        musicName = null;
    }

    /** pauses or resumes the music (the pause menu) */
    public static void pauseMusic(boolean pause) {
        if (music != null) {
            if (pause) {
                music.pause();
            } else {
                music.play();
            }
        }
    }

    /**
     * @return the music that's playing (or null)
     */
    public static String currentMusic() {
        return musicName;
    }

    public static boolean isMuted() {
        return muted;
    }

    /**
     * @param value true to silence everything
     */
    public static void setMuted(boolean value) {
        muted = value;
        if (music != null) {
            music.setVolume(muted ? 0f : MUSIC_VOLUME);
        }
    }

    /**
     * every button on this stage clicks when pressed
     */
    public static void clickSounds(Stage stage) {
        stage.addCaptureListener(new InputListener() {
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                for (com.badlogic.gdx.scenes.scene2d.Actor a = event.getTarget(); a != null; a = a.getParent()) {
                    if (a instanceof Button) {
                        play(((Button) a).isDisabled() ? "back" : "click", 0.5f);
                        break;
                    }
                    if (a instanceof Table && a.getListeners().size > 0 && !(a.getParent() instanceof Button)
                            && a.getTouchable() == com.badlogic.gdx.scenes.scene2d.Touchable.enabled && a != event.getStage().getRoot()) {
                        play("click", 0.4f);     // clickable cards (the map picker's tiles, the paint swatches)
                        break;
                    }
                }
                return false;
            }
        });
    }
}
