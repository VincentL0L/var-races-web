import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Composes and synthesizes all of the game's audio in a 16-bit chiptune style: four pieces
 * of music (title, race, battle, results), the engine sound, and every sound effect.
 *
 * Run from the project folder:  java tools/MakeAudio.java
 * then:  for f in assets/audio/music_*.wav; do lame --quiet -b 112 "$f" "${f%.wav}.mp3" && rm "$f"; done
 *
 * Everything is mono, 22050 Hz. Music loops seamlessly (the echo and the last notes' tails
 * wrap round to the start). The engine is one second of a four-cylinder engine at idle that
 * loops cleanly; the game raises its pitch with the car's speed (see EngineSound).
 */
public class MakeAudio {
    static final int RATE = 22050;
    static final Random RND = new Random(1906);

    public static void main(String[] args) throws Exception {
        new File("assets/audio").mkdirs();
        // ---- music
        save("music_title", song(TITLE));
        save("music_race", song(RACE));
        save("music_battle", song(BATTLE));
        save("music_results", fanfare());
        // ---- engine
        save("engine", engine());
        // ---- interface
        save("click", blip(1100, 0.035, 0.5, 1.0));
        save("back", sweep(900, 520, 0.08, 0.45, 0.5));
        save("select", twoTone(784, 1175, 0.06, 0.5));
        save("ready", arp(new double[] {523, 659, 784, 1047}, 0.05, 0.45));
        // ---- race
        save("beep", tone(660, 0.16, 0.5, 0.5));
        save("go", tone(1320, 0.45, 0.55, 0.5));
        save("flag", whoosh(0.9, 0.55));
        save("lap", arp(new double[] {784, 1047}, 0.09, 0.45));
        save("final_lap", arp(new double[] {784, 1047, 1319, 1568}, 0.08, 0.45));
        save("finish", arp(new double[] {523, 659, 784, 1047, 1319, 1568, 2093}, 0.06, 0.45));
        save("crash", crash(0.28, 0.75));
        save("spin", sweep(700, 180, 0.6, 0.35, 0.5));
        // ---- items
        save("item_pickup", arp(new double[] {1047, 1319, 1568, 2093}, 0.035, 0.4));
        save("item_land", tone(1568, 0.12, 0.35, 0.25));
        save("nitro", nitro());
        save("rocket", rocket());
        save("explosion", explosion(0.7, 0.85));
        save("oil", splat());
        save("shield", shield());
        save("block", ping());
        save("pulse", zap());
        // ---- battle
        save("hit", crash(0.35, 0.9));
        save("knockout", explosion(1.4, 0.95));
        System.out.println("audio made");
    }

    // ================================================================== music

    /** one piece: tempo, the chord of every bar, the melody (8 eighth notes a bar) and a drum style */
    static class Song {
        double bpm;
        String[] chords;
        String[] melody;
        int drums;          // 0 light, 1 driving, 2 heavy
        double leadDuty;

        Song(double bpm, int drums, double leadDuty, String[] chords, String[] melody) {
            this.bpm = bpm;
            this.drums = drums;
            this.leadDuty = leadDuty;
            this.chords = chords;
            this.melody = melody;
        }
    }

    /** the title theme: bright and hopeful, A minor into C major */
    static final Song TITLE = new Song(138, 0, 0.25,
        new String[] {"Am", "F", "C", "G", "Am", "F", "G", "E", "F", "G", "Em", "Am", "F", "G", "C", "E"},
        new String[] {
            "E5 - D5 C5 D5 E5 - A4", "C5 - A4 C5 F5 - E5 D5", "E5 - G5 - E5 D5 C5 D5", "B4 - - D5 G5 - - .",
            "E5 - D5 C5 D5 E5 - A5", "G5 F5 E5 D5 C5 - A4 C5", "D5 - B4 - D5 - G5 -", "G#5 - - - B5 - - .",
            "A5 - G5 F5 E5 - F5 G5", "B5 - A5 G5 D5 - G5 -", "E5 - G5 B5 - - A5 G5", "A5 - - E5 C5 - A4 -",
            "F5 - E5 F5 A5 - G5 F5", "G5 - F5 G5 B5 - D6 -", "C6 - B5 A5 G5 E5 C5 E5", "G#5 - B5 - E6 - - ."});

    /** racing: fast, E minor, a driving bass under it */
    static final Song RACE = new Song(168, 1, 0.5,
        new String[] {"Em", "C", "D", "B", "Em", "C", "D", "Em", "C", "D", "Bm", "Em", "C", "D", "B", "B"},
        new String[] {
            "B4 - E5 - G5 - F#5 E5", "G5 - E5 - C5 - E5 G5", "F#5 - D5 - A5 - F#5 D5", "D#5 - F#5 - B5 - - .",
            "E6 - D6 B5 G5 - E5 G5", "C6 - B5 G5 E5 - G5 C6", "D6 - C6 A5 F#5 - D5 F#5", "E5 - - - - - . .",
            "E5 G5 C6 - B5 - G5 -", "F#5 A5 D6 - C6 - A5 -", "B5 - A5 F#5 D5 - F#5 A5", "G5 - - B5 E6 - - .",
            "C6 B5 A5 G5 A5 - E5 -", "D6 C6 B5 A5 B5 - F#5 -", "D#6 - B5 - F#5 - D#5 -", "B4 - D#5 - F#5 - B5 ."});

    /** battle: heavy and urgent, D minor */
    static final Song BATTLE = new Song(150, 2, 0.125,
        new String[] {"Dm", "Dm", "Bb", "C", "Dm", "Dm", "Bb", "A", "Gm", "Bb", "C", "Dm", "Gm", "Bb", "A", "A"},
        new String[] {
            "D5 . D5 F5 . D5 A5 .", "G5 F5 E5 F5 D5 - - .", "D5 . F5 A#5 - A5 G5 F5", "E5 - G5 - C6 - . .",
            "D6 . D6 C6 . A5 F5 .", "G5 A5 F5 E5 D5 - - .", "F5 - A#5 - D6 - C6 A#5", "A5 - C#6 - E6 - . .",
            "G5 - A#5 - D6 - C6 A#5", "A5 - F5 - D5 - F5 -", "E5 G5 C6 - G5 - E5 -", "F5 - A5 - D6 - - .",
            "A#5 A5 G5 A5 A#5 - D6 -", "C6 A#5 A5 A#5 F5 - D5 -", "E5 - A5 - C#6 - E6 -", "A5 . A5 . A5 - - ."});

    static float[] song(Song s) {
        double eighth = 60.0 / s.bpm / 2.0;
        int barSamples = (int) Math.round(eighth * 8 * RATE);
        int length = barSamples * s.chords.length;
        float[] out = new float[length + RATE * 2];          // room for tails, wrapped round later
        float[] lead = new float[out.length];
        for (int bar = 0; bar < s.chords.length; bar++) {
            int barStart = bar * barSamples;
            int[] chord = chord(s.chords[bar]);
            // bass: roots in eighths, the octave on the off-beats (driving styles push harder)
            for (int e = 0; e < 8; e++) {
                int note = chord[0] - 24 + ((e % 2 == 1 && s.drums > 0) ? 12 : 0);
                if (s.drums == 2 && e % 2 == 1) note = chord[0] - 24 + 7;
                addNote(out, barStart + (int) (e * eighth * RATE), eighth * 0.92, midi(note), 2, 0.26, 0);
            }
            // harmony: the chord as quiet arpeggios in sixteenths
            for (int k = 0; k < 16; k++) {
                int note = chord[k % 3] + (k / 3 % 2 == 1 ? 12 : 0);
                addNote(out, barStart + (int) (k * eighth / 2 * RATE), eighth / 2 * 0.9, midi(note), 0, 0.09, 0.125);
            }
            // lead melody
            String[] tokens = s.melody[bar].trim().split("\\s+");
            for (int e = 0; e < tokens.length; e++) {
                String t = tokens[e];
                if (t.equals("-") || t.equals(".")) {
                    continue;
                }
                int len = 1;
                while (e + len < tokens.length && tokens[e + len].equals("-")) {
                    len++;
                }
                addNote(lead, barStart + (int) (e * eighth * RATE), eighth * len * 0.95, midi(noteNumber(t)), 0, 0.3, s.leadDuty);
            }
            drums(out, barStart, eighth, s.drums, bar == s.chords.length - 1);
        }
        // a short echo on the lead, then mix it in
        int delay = (int) (eighth * 1.5 * RATE);
        for (int i = delay; i < lead.length; i++) {
            lead[i] += lead[i - delay] * 0.28f;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] += lead[i];
        }
        // wrap the tails round so the loop is seamless
        float[] loop = new float[length];
        for (int i = 0; i < out.length; i++) {
            loop[i % length] += out[i];
        }
        return master(loop, 0.8f);
    }

    static void drums(float[] out, int barStart, double eighth, int style, boolean lastBar) {
        for (int k = 0; k < 16; k++) {
            int at = barStart + (int) (k * eighth / 2 * RATE);
            boolean beat = k % 4 == 0;
            // kick
            if (style == 0 ? (k == 0 || k == 8) : style == 1 ? beat : (beat || k == 6 || k == 14 && !lastBar)) {
                kick(out, at, style == 2 ? 0.75 : 0.6);
            }
            // snare on 2 and 4 (a roll at the end of the loop)
            if (k == 4 || k == 12 || (lastBar && k >= 12 && style > 0)) {
                snare(out, at, style == 0 ? 0.25 : 0.35);
            }
            // hats
            if (style == 0 ? k % 2 == 0 : true) {
                hat(out, at, k % 2 == 0 ? 0.08 : 0.05);
            }
        }
    }

    /** a short fanfare for the results screen (plays once) */
    static float[] fanfare() {
        double beat = 60.0 / 120;
        float[] out = new float[(int) (RATE * 4.2)];
        double t = 0;
        int n = 0;
        for (String note : new String[] {"C5", "E5", "G5", "C6", "G5", "C6"}) {
            double d = new double[] {0.33, 0.33, 0.33, 1.0, 0.5, 2.5}[n++] * beat;
            addNote(out, (int) (t * RATE), d * 0.95, midi(noteNumber(note)), 0, 0.35, 0.25);
            addNote(out, (int) (t * RATE), d * 0.95, midi(noteNumber(note) - 12), 0, 0.15, 0.5);
            t += d;
        }
        // the final chord, held, with a cymbal
        int at = (int) ((t - 2.5 * beat) * RATE);
        for (int note : new int[] {48, 52, 55, 60}) {
            addNote(out, at, 2.5 * beat, midi(note), 2, 0.25, 0);
        }
        snare(out, at, 0.3);
        for (int i = 0; i < RATE * 1.5 && at + i < out.length; i++) {
            out[at + i] += (float) (noise() * 0.12 * Math.exp(-i / (RATE * 0.5)));
        }
        return master(out, 0.8f);
    }

    // ================================================================== engine

    /**
     * a one-second loop of an engine idling: firing pulses at a whole number of cycles a
     * second (so it loops without a click), a throaty fundamental and a bit of grit
     */
    static float[] engine() {
        float[] out = new float[RATE];
        double fire = 40;          // firing pulses per second at idle
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double phase = (t * fire) % 1.0;
            // each firing: a sharp pulse that dies away, slightly uneven like a real engine
            double pulse = Math.exp(-phase * 7.0) * (1.0 + 0.15 * Math.sin(2 * Math.PI * t * 4));
            double body = 0.55 * Math.sin(2 * Math.PI * fire * t) + 0.3 * Math.sin(2 * Math.PI * fire * 2 * t)
                + 0.15 * Math.sin(2 * Math.PI * fire * 3 * t + 0.4);
            out[i] = (float) (0.55 * body * (0.6 + 0.4 * pulse) + 0.35 * pulse - 0.18);
        }
        // grit: noise that follows the firing, the same at both ends of the loop
        for (int i = 0; i < out.length; i++) {
            double phase = (i / (double) RATE * fire) % 1.0;
            out[i] += (float) (noise() * 0.06 * Math.exp(-phase * 5));
        }
        lowpass(out, 0.35);
        return master(out, 0.85f);
    }

    // ================================================================== sound effects

    static float[] blip(double freq, double len, double vol, double duty) {
        float[] out = new float[(int) (len * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            out[i] = (float) (square(freq * t, 0.5) * vol * Math.exp(-t / (len * 0.4)));
        }
        return master(out, 0.7f);
    }

    static float[] tone(double freq, double len, double vol, double duty) {
        float[] out = new float[(int) (len * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.005) * Math.min(1, (len - t) / 0.04);
            out[i] = (float) (square(freq * t, duty) * vol * env);
        }
        return master(out, 0.7f);
    }

    static float[] twoTone(double f1, double f2, double each, double vol) {
        return concat(tone(f1, each, vol, 0.5), tone(f2, each * 1.6, vol, 0.5));
    }

    static float[] arp(double[] freqs, double each, double vol) {
        float[] out = new float[0];
        for (int i = 0; i < freqs.length; i++) {
            out = concat(out, tone(freqs[i], i == freqs.length - 1 ? each * 3 : each, vol, 0.25));
        }
        return out;
    }

    static float[] sweep(double from, double to, double len, double vol, double duty) {
        float[] out = new float[(int) (len * RATE)];
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double k = i / (double) out.length;
            double f = from * Math.pow(to / from, k);
            phase += f / RATE;
            out[i] = (float) (square(phase, duty) * vol * (1 - k * 0.7) * Math.min(1, (1 - k) * 10));
        }
        return master(out, 0.7f);
    }

    /** air rushing past: noise swept through a filter */
    static float[] whoosh(double len, double vol) {
        float[] out = new float[(int) (len * RATE)];
        double low = 0;
        for (int i = 0; i < out.length; i++) {
            double k = i / (double) out.length;
            double cutoff = 0.05 + 0.5 * Math.sin(Math.PI * k);
            low += (noise() - low) * cutoff;
            out[i] = (float) (low * vol * Math.sin(Math.PI * k));
        }
        return master(out, 0.75f);
    }

    /** a crunch of metal: a low thump and a burst of filtered noise */
    static float[] crash(double len, double vol) {
        float[] out = new float[(int) (len * RATE)];
        double low = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            low += (noise() - low) * 0.25;
            double thump = Math.sin(2 * Math.PI * (90 - 40 * t / len) * t) * Math.exp(-t / 0.06);
            double metal = square(t * 310, 0.3) * 0.15 * Math.exp(-t / 0.05);
            out[i] = (float) ((low * 0.8 * Math.exp(-t / (len * 0.35)) + thump * 0.9 + metal) * vol);
        }
        return master(out, 0.85f);
    }

    static float[] explosion(double len, double vol) {
        float[] out = new float[(int) (len * RATE)];
        double low = 0, lower = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            low += (noise() - low) * (0.4 * Math.exp(-t / (len * 0.4)) + 0.02);
            lower += (low - lower) * 0.3;
            double boom = Math.sin(2 * Math.PI * (60 - 30 * t / len) * t) * Math.exp(-t / (len * 0.25));
            out[i] = (float) ((lower * 1.2 + boom * 0.8) * vol * Math.exp(-t / (len * 0.45)));
        }
        return master(out, 0.9f);
    }

    static float[] nitro() {
        float[] w = whoosh(0.7, 0.6);
        float[] rise = sweep(220, 880, 0.7, 0.25, 0.5);
        for (int i = 0; i < w.length && i < rise.length; i++) {
            w[i] = w[i] * 0.8f + rise[i] * 0.5f;
        }
        return master(w, 0.8f);
    }

    static float[] rocket() {
        float[] out = new float[(int) (0.55 * RATE)];
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double k = i / (double) out.length;
            phase += (500 + 1600 * k) / RATE;
            out[i] = (float) (Math.sin(2 * Math.PI * phase) * 0.35 * (1 - k) + noise() * 0.25 * Math.exp(-k * 3)
                + (RND.nextInt(40) == 0 ? 0.5 : 0) * (1 - k));
        }
        return master(out, 0.75f);
    }

    static float[] splat() {
        float[] out = new float[(int) (0.3 * RATE)];
        double low = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            low += (noise() - low) * 0.12;
            out[i] = (float) (low * Math.exp(-t / 0.08) + Math.sin(2 * Math.PI * 120 * t) * 0.5 * Math.exp(-t / 0.05));
        }
        return master(out, 0.75f);
    }

    /** a shimmering bubble going up */
    static float[] shield() {
        float[] out = new float[(int) (0.6 * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE, k = i / (double) out.length;
            double f = 600 * Math.pow(2, k * 1.5);
            double s = Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * f * 1.5 * t) + 0.3 * Math.sin(2 * Math.PI * f * 2 * t);
            out[i] = (float) (s * 0.25 * Math.sin(Math.PI * k) * (0.7 + 0.3 * Math.sin(2 * Math.PI * 18 * t)));
        }
        return master(out, 0.7f);
    }

    /** a metallic ping */
    static float[] ping() {
        float[] out = new float[(int) (0.5 * RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            out[i] = (float) ((Math.sin(2 * Math.PI * 1760 * t) + 0.5 * Math.sin(2 * Math.PI * 2640 * t)
                + 0.3 * Math.sin(2 * Math.PI * 4430 * t)) * 0.3 * Math.exp(-t / 0.12));
        }
        return master(out, 0.7f);
    }

    /** an electric zap falling away */
    static float[] zap() {
        float[] out = new float[(int) (0.45 * RATE)];
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double k = i / (double) out.length;
            phase += (1800 * Math.pow(0.08, k) + 60 * Math.sin(k * 90)) / RATE;
            double saw = 2 * (phase % 1) - 1;
            out[i] = (float) (saw * 0.35 * (1 - k) + noise() * 0.1 * (1 - k));
        }
        return master(out, 0.75f);
    }

    // ================================================================== synth pieces

    /**
     * adds one note
     * @param wave 0 pulse, 2 triangle
     */
    static void addNote(float[] out, int start, double len, double freq, int wave, double vol, double duty) {
        int n = (int) (len * RATE);
        int release = (int) (0.03 * RATE);
        for (int i = 0; i < n + release && start + i < out.length; i++) {
            if (start + i < 0) continue;
            double t = i / (double) RATE;
            double env = Math.min(1, t / 0.004);
            env *= i < n ? (0.75 + 0.25 * Math.exp(-t / 0.08)) : 0.75 * (1 - (i - n) / (double) release);
            double v = wave == 2 ? triangle(freq * t) : square(freq * t, duty <= 0 ? 0.5 : duty);
            out[start + i] += (float) (v * vol * env);
        }
    }

    static void kick(float[] out, int at, double vol) {
        double phase = 0;
        for (int i = 0; i < RATE * 0.16 && at + i < out.length; i++) {
            double t = i / (double) RATE;
            phase += (45 + 120 * Math.exp(-t / 0.025)) / RATE;
            out[at + i] += (float) (Math.sin(2 * Math.PI * phase) * vol * Math.exp(-t / 0.07));
        }
    }

    static void snare(float[] out, int at, double vol) {
        for (int i = 0; i < RATE * 0.14 && at + i < out.length; i++) {
            double t = i / (double) RATE;
            out[at + i] += (float) ((noise() * 0.8 + Math.sin(2 * Math.PI * 190 * t) * 0.4) * vol * Math.exp(-t / 0.045));
        }
    }

    static double prevHat = 0;

    static void hat(float[] out, int at, double vol) {
        for (int i = 0; i < RATE * 0.03 && at + i < out.length; i++) {
            double t = i / (double) RATE;
            double n = noise();
            double high = n - prevHat;      // a crude high-pass: just the fizz
            prevHat = n;
            out[at + i] += (float) (high * vol * Math.exp(-t / 0.01));
        }
    }

    static double square(double phase, double duty) {
        return (phase % 1.0) < duty ? 1 : -1;
    }

    static double triangle(double phase) {
        double p = phase % 1.0;
        return p < 0.5 ? 4 * p - 1 : 3 - 4 * p;
    }

    static double noise() {
        return RND.nextDouble() * 2 - 1;
    }

    static void lowpass(float[] out, double k) {
        double y = out[out.length - 1];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < out.length; i++) {
                y += (out[i] - y) * k;
                if (pass == 1) out[i] = (float) y;
            }
        }
    }

    /** normalise to a peak, with a soft clip so loud mixes don't crackle */
    static float[] master(float[] out, float peak) {
        float max = 1e-6f;
        for (float v : out) {
            max = Math.max(max, Math.abs(v));
        }
        for (int i = 0; i < out.length; i++) {
            double v = out[i] / max * 1.15;
            out[i] = (float) (Math.tanh(v) / Math.tanh(1.15) * peak);
        }
        return out;
    }

    static float[] concat(float[] a, float[] b) {
        float[] out = new float[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ================================================================== notes and chords

    static double midi(int note) {
        return 440.0 * Math.pow(2, (note - 69) / 12.0);
    }

    /** "C#5" -> 73 */
    static int noteNumber(String name) {
        int[] base = {9, 11, 0, 2, 4, 5, 7};
        int n = base[name.charAt(0) - 'A'];
        int i = 1;
        if (name.charAt(1) == '#') {
            n++;
            i++;
        } else if (name.charAt(1) == 'b' && name.length() > 2) {
            n--;
            i++;
        }
        return n + 12 * (Integer.parseInt(name.substring(i)) + 1);
    }

    /** "Em" / "Bb" / "C" -> the chord's notes around the fourth octave (root, third, fifth) */
    static int[] chord(String name) {
        boolean minor = name.endsWith("m");
        String root = minor ? name.substring(0, name.length() - 1) : name;
        int r = noteNumber(root + "4");
        if (r > 66) r -= 12;
        return new int[] {r, r + (minor ? 3 : 4), r + 7};
    }

    // ================================================================== saving

    static void save(String name, float[] data) throws IOException {
        File f = new File("assets/audio/" + name + ".wav");
        try (FileOutputStream out = new FileOutputStream(f)) {
            int bytes = data.length * 2;
            out.write(new byte[] {'R', 'I', 'F', 'F'});
            le(out, 36 + bytes, 4);
            out.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            le(out, 16, 4);
            le(out, 1, 2);
            le(out, 1, 2);
            le(out, RATE, 4);
            le(out, RATE * 2, 4);
            le(out, 2, 2);
            le(out, 16, 2);
            out.write(new byte[] {'d', 'a', 't', 'a'});
            le(out, bytes, 4);
            byte[] pcm = new byte[bytes];
            for (int i = 0; i < data.length; i++) {
                int v = (int) Math.round(Math.max(-1, Math.min(1, data[i])) * 32767);
                pcm[i * 2] = (byte) v;
                pcm[i * 2 + 1] = (byte) (v >> 8);
            }
            out.write(pcm);
        }
    }

    static void le(FileOutputStream out, int v, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            out.write((v >> (8 * i)) & 0xff);
        }
    }
}
