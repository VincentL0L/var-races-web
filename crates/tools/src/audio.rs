//! Composes and synthesises all of the game's audio in a 16-bit chiptune style: four pieces of
//! music (title, race, battle, results), the engine sound, and every sound effect.
//!
//! Everything is mono, 22050 Hz. Music loops seamlessly (the echo and the last notes' tails wrap
//! round to the start) and is encoded to MP3 with `lame`; effects stay WAV. The engine is one
//! second of a four-cylinder engine at idle that loops cleanly; the game raises its pitch with
//! the car's speed (see Engine in web/src/audio.ts).
//!
//! One seeded random stream runs through every sound in order, so the output is the same each
//! time (and matches what the original Java generator made).

use std::f64::consts::PI;
use std::path::Path;
use std::process::Command;

use crate::jrand::{self, JRandom};

const RATE: i32 = 22050;
const R: f64 = RATE as f64;

pub fn make_all(root: &Path) -> Result<(), String> {
    let dir = root.join("assets/audio");
    std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let save = |name: &str, data: Vec<f32>| save(&dir, name, &data);
    let music = |name: &str, data: Vec<f32>| {
        save(name, data)?;
        encode_mp3(&dir, name)
    };
    let mut s = Synth { rnd: JRandom::new(1906), prev_hat: 0.0 };
    // ---- music
    music("music_title", s.song(&TITLE))?;
    music("music_race", s.song(&RACE))?;
    music("music_battle", s.song(&BATTLE))?;
    music("music_results", s.fanfare())?;
    // ---- engine
    save("engine", s.engine())?;
    // ---- interface
    save("click", blip(1100.0, 0.035, 0.5))?;
    save("back", sweep(900.0, 520.0, 0.08, 0.45, 0.5))?;
    save("ready", arp(&[523.0, 659.0, 784.0, 1047.0], 0.05, 0.45))?;
    // ---- race
    save("beep", tone(660.0, 0.16, 0.5, 0.5))?;
    save("go", tone(1320.0, 0.45, 0.55, 0.5))?;
    save("flag", s.whoosh(0.9, 0.55))?;
    save("lap", arp(&[784.0, 1047.0], 0.09, 0.45))?;
    save("final_lap", arp(&[784.0, 1047.0, 1319.0, 1568.0], 0.08, 0.45))?;
    save("finish", arp(&[523.0, 659.0, 784.0, 1047.0, 1319.0, 1568.0, 2093.0], 0.06, 0.45))?;
    save("crash", s.crash(0.28, 0.75))?;
    save("spin", sweep(700.0, 180.0, 0.6, 0.35, 0.5))?;
    // ---- items
    save("item_pickup", arp(&[1047.0, 1319.0, 1568.0, 2093.0], 0.035, 0.4))?;
    save("item_land", tone(1568.0, 0.12, 0.35, 0.25))?;
    save("nitro", s.nitro())?;
    save("rocket", s.rocket())?;
    // an explosion the game doesn't play, made only so the sounds after it keep their noise
    s.explosion(0.7, 0.85);
    save("oil", s.splat())?;
    save("shield", shield())?;
    save("block", ping())?;
    save("pulse", s.zap())?;
    save("frost_shot", sweep(2200.0, 1300.0, 0.12, 0.35, 0.25))?;
    save("fire_shot", s.fire_shot())?;
    save("freeze", s.freeze())?;
    save("burn", s.crash(0.3, 0.6))?;
    // ---- battle
    save("hit", s.crash(0.35, 0.9))?;
    save("knockout", s.explosion(1.4, 0.95))?;
    println!("audio made");
    Ok(())
}

// ================================================================== music

/// one piece: tempo, the chord of every bar, the melody (8 eighth notes a bar) and a drum style
struct Song {
    bpm: f64,
    /// 0 light, 1 driving, 2 heavy
    drums: i32,
    lead_duty: f64,
    chords: [&'static str; 16],
    melody: [&'static str; 16],
}

/// the title theme: bright and hopeful, A minor into C major
const TITLE: Song = Song {
    bpm: 138.0,
    drums: 0,
    lead_duty: 0.25,
    chords: ["Am", "F", "C", "G", "Am", "F", "G", "E", "F", "G", "Em", "Am", "F", "G", "C", "E"],
    melody: [
        "E5 - D5 C5 D5 E5 - A4", "C5 - A4 C5 F5 - E5 D5", "E5 - G5 - E5 D5 C5 D5", "B4 - - D5 G5 - - .",
        "E5 - D5 C5 D5 E5 - A5", "G5 F5 E5 D5 C5 - A4 C5", "D5 - B4 - D5 - G5 -", "G#5 - - - B5 - - .",
        "A5 - G5 F5 E5 - F5 G5", "B5 - A5 G5 D5 - G5 -", "E5 - G5 B5 - - A5 G5", "A5 - - E5 C5 - A4 -",
        "F5 - E5 F5 A5 - G5 F5", "G5 - F5 G5 B5 - D6 -", "C6 - B5 A5 G5 E5 C5 E5", "G#5 - B5 - E6 - - .",
    ],
};

/// racing: fast, E minor, a driving bass under it
const RACE: Song = Song {
    bpm: 168.0,
    drums: 1,
    lead_duty: 0.5,
    chords: ["Em", "C", "D", "B", "Em", "C", "D", "Em", "C", "D", "Bm", "Em", "C", "D", "B", "B"],
    melody: [
        "B4 - E5 - G5 - F#5 E5", "G5 - E5 - C5 - E5 G5", "F#5 - D5 - A5 - F#5 D5", "D#5 - F#5 - B5 - - .",
        "E6 - D6 B5 G5 - E5 G5", "C6 - B5 G5 E5 - G5 C6", "D6 - C6 A5 F#5 - D5 F#5", "E5 - - - - - . .",
        "E5 G5 C6 - B5 - G5 -", "F#5 A5 D6 - C6 - A5 -", "B5 - A5 F#5 D5 - F#5 A5", "G5 - - B5 E6 - - .",
        "C6 B5 A5 G5 A5 - E5 -", "D6 C6 B5 A5 B5 - F#5 -", "D#6 - B5 - F#5 - D#5 -", "B4 - D#5 - F#5 - B5 .",
    ],
};

/// battle: heavy and urgent, D minor
const BATTLE: Song = Song {
    bpm: 150.0,
    drums: 2,
    lead_duty: 0.125,
    chords: ["Dm", "Dm", "Bb", "C", "Dm", "Dm", "Bb", "A", "Gm", "Bb", "C", "Dm", "Gm", "Bb", "A", "A"],
    melody: [
        "D5 . D5 F5 . D5 A5 .", "G5 F5 E5 F5 D5 - - .", "D5 . F5 A#5 - A5 G5 F5", "E5 - G5 - C6 - . .",
        "D6 . D6 C6 . A5 F5 .", "G5 A5 F5 E5 D5 - - .", "F5 - A#5 - D6 - C6 A#5", "A5 - C#6 - E6 - . .",
        "G5 - A#5 - D6 - C6 A#5", "A5 - F5 - D5 - F5 -", "E5 G5 C6 - G5 - E5 -", "F5 - A5 - D6 - - .",
        "A#5 A5 G5 A5 A#5 - D6 -", "C6 A#5 A5 A#5 F5 - D5 -", "E5 - A5 - C#6 - E6 -", "A5 . A5 . A5 - - .",
    ],
};

/// the parts that draw on the shared random stream (noise, and the hats' last sample)
struct Synth {
    rnd: JRandom,
    prev_hat: f64,
}

impl Synth {
    fn noise(&mut self) -> f64 {
        self.rnd.next_double() * 2.0 - 1.0
    }

    fn song(&mut self, s: &Song) -> Vec<f32> {
        let eighth = 60.0 / s.bpm / 2.0;
        let bar_samples = jrand::round(eighth * 8.0 * R) as usize;
        let length = bar_samples * s.chords.len();
        let mut out = vec![0f32; length + RATE as usize * 2]; // room for tails, wrapped round later
        let mut lead = vec![0f32; out.len()];
        for (bar, (&chord_name, &melody)) in s.chords.iter().zip(&s.melody).enumerate() {
            let bar_start = (bar * bar_samples) as i32;
            let chord = chord(chord_name);
            // bass: roots in eighths, the octave on the off-beats (driving styles push harder)
            for e in 0..8 {
                let mut note = chord[0] - 24 + if e % 2 == 1 && s.drums > 0 { 12 } else { 0 };
                if s.drums == 2 && e % 2 == 1 {
                    note = chord[0] - 24 + 7;
                }
                let at = bar_start + (e as f64 * eighth * R) as i32;
                add_note(&mut out, at, eighth * 0.92, midi(note), Wave::Triangle, 0.26, 0.0);
            }
            // harmony: the chord as quiet arpeggios in sixteenths
            for k in 0..16 {
                let note = chord[k % 3] + if k / 3 % 2 == 1 { 12 } else { 0 };
                let at = bar_start + (k as f64 * eighth / 2.0 * R) as i32;
                add_note(&mut out, at, eighth / 2.0 * 0.9, midi(note), Wave::Pulse, 0.09, 0.125);
            }
            // lead melody: a note holds through the "-" after it; "." is a rest
            let tokens: Vec<&str> = melody.split_whitespace().collect();
            for (e, &t) in tokens.iter().enumerate() {
                if t == "-" || t == "." {
                    continue;
                }
                let len = 1 + tokens[e + 1..].iter().take_while(|&&n| n == "-").count();
                let at = bar_start + (e as f64 * eighth * R) as i32;
                add_note(&mut lead, at, eighth * len as f64 * 0.95, midi(note_number(t)), Wave::Pulse, 0.3, s.lead_duty);
            }
            self.drums(&mut out, bar_start, eighth, s.drums, bar == s.chords.len() - 1);
        }
        // a short echo on the lead, then mix it in
        let delay = (eighth * 1.5 * R) as usize;
        for i in delay..lead.len() {
            lead[i] += lead[i - delay] * 0.28f32;
        }
        for (o, l) in out.iter_mut().zip(&lead) {
            *o += l;
        }
        // wrap the tails round so the loop is seamless
        let mut looped = vec![0f32; length];
        for (i, v) in out.iter().enumerate() {
            looped[i % length] += v;
        }
        master(looped, 0.8)
    }

    fn drums(&mut self, out: &mut [f32], bar_start: i32, eighth: f64, style: i32, last_bar: bool) {
        for k in 0..16 {
            let at = bar_start + (k as f64 * eighth / 2.0 * R) as i32;
            let beat = k % 4 == 0;
            let kick_here = match style {
                0 => k == 0 || k == 8,
                1 => beat,
                _ => beat || k == 6 || (k == 14 && !last_bar),
            };
            if kick_here {
                kick(out, at, if style == 2 { 0.75 } else { 0.6 });
            }
            // snare on 2 and 4 (a roll at the end of the loop)
            if k == 4 || k == 12 || (last_bar && k >= 12 && style > 0) {
                self.snare(out, at, if style == 0 { 0.25 } else { 0.35 });
            }
            if style != 0 || k % 2 == 0 {
                self.hat(out, at, if k % 2 == 0 { 0.08 } else { 0.05 });
            }
        }
    }

    /// a short fanfare for the results screen (plays once)
    fn fanfare(&mut self) -> Vec<f32> {
        let beat = 60.0 / 120.0;
        let mut out = vec![0f32; (R * 4.2) as usize];
        let mut t = 0.0;
        for (note, length) in ["C5", "E5", "G5", "C6", "G5", "C6"].iter().zip([0.33, 0.33, 0.33, 1.0, 0.5, 2.5]) {
            let d = length * beat;
            let n = note_number(note);
            add_note(&mut out, (t * R) as i32, d * 0.95, midi(n), Wave::Pulse, 0.35, 0.25);
            add_note(&mut out, (t * R) as i32, d * 0.95, midi(n - 12), Wave::Pulse, 0.15, 0.5);
            t += d;
        }
        // the final chord, held, with a cymbal
        let at = ((t - 2.5 * beat) * R) as i32;
        for note in [48, 52, 55, 60] {
            add_note(&mut out, at, 2.5 * beat, midi(note), Wave::Triangle, 0.25, 0.0);
        }
        self.snare(&mut out, at, 0.3);
        let at = at as usize;
        let mut i = 0;
        while (i as f64) < R * 1.5 && at + i < out.len() {
            out[at + i] += (self.noise() * 0.12 * (-(i as f64) / (R * 0.5)).exp()) as f32;
            i += 1;
        }
        master(out, 0.8)
    }

    // ============================================================== engine

    /// a one-second loop of an engine idling: firing pulses at a whole number of cycles a second
    /// (so it loops without a click), a throaty fundamental and a bit of grit
    fn engine(&mut self) -> Vec<f32> {
        let mut out = vec![0f32; RATE as usize];
        let fire = 40.0; // firing pulses per second at idle
        for (i, o) in out.iter_mut().enumerate() {
            let t = i as f64 / R;
            let phase = (t * fire) % 1.0;
            // each firing: a sharp pulse that dies away, slightly uneven like a real engine
            let pulse = (-phase * 7.0).exp() * (1.0 + 0.15 * (2.0 * PI * t * 4.0).sin());
            let body = 0.55 * (2.0 * PI * fire * t).sin()
                + 0.3 * (2.0 * PI * fire * 2.0 * t).sin()
                + 0.15 * (2.0 * PI * fire * 3.0 * t + 0.4).sin();
            *o = (0.55 * body * (0.6 + 0.4 * pulse) + 0.35 * pulse - 0.18) as f32;
        }
        // grit: noise that follows the firing, the same at both ends of the loop
        for (i, o) in out.iter_mut().enumerate() {
            let phase = (i as f64 / R * fire) % 1.0;
            *o += (self.noise() * 0.06 * (-phase * 5.0).exp()) as f32;
        }
        lowpass(&mut out, 0.35);
        master(out, 0.85)
    }

    // ============================================================== effects with noise

    /// air rushing past: noise swept through a filter
    fn whoosh(&mut self, len: f64, vol: f64) -> Vec<f32> {
        let mut out = samples(len);
        let n = out.len() as f64;
        let mut low = 0.0;
        for (i, o) in out.iter_mut().enumerate() {
            let k = i as f64 / n;
            let cutoff = 0.05 + 0.5 * (PI * k).sin();
            low += (self.noise() - low) * cutoff;
            *o = (low * vol * (PI * k).sin()) as f32;
        }
        master(out, 0.75)
    }

    /// a crunch of metal: a low thump and a burst of filtered noise
    fn crash(&mut self, len: f64, vol: f64) -> Vec<f32> {
        let mut out = samples(len);
        let mut low = 0.0;
        for (i, o) in out.iter_mut().enumerate() {
            let t = i as f64 / R;
            low += (self.noise() - low) * 0.25;
            let thump = (2.0 * PI * (90.0 - 40.0 * t / len) * t).sin() * (-t / 0.06).exp();
            let metal = square(t * 310.0, 0.3) * 0.15 * (-t / 0.05).exp();
            *o = ((low * 0.8 * (-t / (len * 0.35)).exp() + thump * 0.9 + metal) * vol) as f32;
        }
        master(out, 0.85)
    }

    fn explosion(&mut self, len: f64, vol: f64) -> Vec<f32> {
        let mut out = samples(len);
        let (mut low, mut lower) = (0.0, 0.0);
        for (i, o) in out.iter_mut().enumerate() {
            let t = i as f64 / R;
            low += (self.noise() - low) * (0.4 * (-t / (len * 0.4)).exp() + 0.02);
            lower += (low - lower) * 0.3;
            let boom = (2.0 * PI * (60.0 - 30.0 * t / len) * t).sin() * (-t / (len * 0.25)).exp();
            *o = ((lower * 1.2 + boom * 0.8) * vol * (-t / (len * 0.45)).exp()) as f32;
        }
        master(out, 0.9)
    }

    fn nitro(&mut self) -> Vec<f32> {
        let mut w = self.whoosh(0.7, 0.6);
        let rise = sweep(220.0, 880.0, 0.7, 0.25, 0.5);
        for (a, b) in w.iter_mut().zip(&rise) {
            *a = *a * 0.8f32 + b * 0.5f32;
        }
        master(w, 0.8)
    }

    fn rocket(&mut self) -> Vec<f32> {
        let mut out = samples(0.55);
        let n = out.len() as f64;
        let mut phase = 0.0;
        for (i, o) in out.iter_mut().enumerate() {
            let k = i as f64 / n;
            phase += (500.0 + 1600.0 * k) / R;
            let hiss = self.noise();
            let pop = if self.rnd.next_int(40) == 0 { 0.5 } else { 0.0 };
            *o = ((2.0 * PI * phase).sin() * 0.35 * (1.0 - k) + hiss * 0.25 * (-k * 3.0).exp() + pop * (1.0 - k)) as f32;
        }
        master(out, 0.75)
    }

    fn splat(&mut self) -> Vec<f32> {
        let mut out = samples(0.3);
        let mut low = 0.0;
        for (i, o) in out.iter_mut().enumerate() {
            let t = i as f64 / R;
            low += (self.noise() - low) * 0.12;
            *o = (low * (-t / 0.08).exp() + (2.0 * PI * 120.0 * t).sin() * 0.5 * (-t / 0.05).exp()) as f32;
        }
        master(out, 0.75)
    }

    /// a fireball whooshing out: a crackle over a falling tone
    fn fire_shot(&mut self) -> Vec<f32> {
        let mut out = samples(0.22);
        let n = out.len() as f64;
        let mut low = 0.0;
        for (i, o) in out.iter_mut().enumerate() {
            let k = i as f64 / n;
            low += (self.noise() - low) * 0.35;
            let buzz = square(i as f64 / R * (420.0 - 260.0 * k), 0.4) * 0.25;
            *o = ((low * 0.7 + buzz) * (1.0 - k) * (k * 40.0).min(1.0)) as f32;
        }
        master(out, 0.7)
    }

    /// ice cracking: sharp high clicks over a cold shimmer
    fn freeze(&mut self) -> Vec<f32> {
        let mut out = samples(0.5);
        let n = out.len() as f64;
        for (i, o) in out.iter_mut().enumerate() {
            let (t, k) = (i as f64 / R, i as f64 / n);
            let shimmer = ((2.0 * PI * 2600.0 * t).sin() + (2.0 * PI * 3300.0 * t).sin()) * 0.15 * (1.0 - k);
            let crack = if self.rnd.next_int(90) == 0 { self.noise() * 0.9 } else { 0.0 } * (1.0 - k);
            *o = (shimmer + crack) as f32;
        }
        master(out, 0.7)
    }

    /// an electric zap falling away
    fn zap(&mut self) -> Vec<f32> {
        let mut out = samples(0.45);
        let n = out.len() as f64;
        let mut phase = 0.0;
        for (i, o) in out.iter_mut().enumerate() {
            let k = i as f64 / n;
            phase += (1800.0 * 0.08f64.powf(k) + 60.0 * (k * 90.0).sin()) / R;
            let saw = 2.0 * (phase % 1.0) - 1.0;
            *o = (saw * 0.35 * (1.0 - k) + self.noise() * 0.1 * (1.0 - k)) as f32;
        }
        master(out, 0.75)
    }

    fn snare(&mut self, out: &mut [f32], at: i32, vol: f64) {
        let at = at as usize;
        let mut i = 0;
        while (i as f64) < R * 0.14 && at + i < out.len() {
            let t = i as f64 / R;
            out[at + i] += ((self.noise() * 0.8 + (2.0 * PI * 190.0 * t).sin() * 0.4) * vol * (-t / 0.045).exp()) as f32;
            i += 1;
        }
    }

    fn hat(&mut self, out: &mut [f32], at: i32, vol: f64) {
        let at = at as usize;
        let mut i = 0;
        while (i as f64) < R * 0.03 && at + i < out.len() {
            let t = i as f64 / R;
            let n = self.noise();
            let high = n - self.prev_hat; // a crude high-pass: just the fizz
            self.prev_hat = n;
            out[at + i] += (high * vol * (-t / 0.01).exp()) as f32;
            i += 1;
        }
    }
}

// ================================================================== effects without noise

fn samples(len: f64) -> Vec<f32> {
    vec![0f32; (len * R) as usize]
}

fn blip(freq: f64, len: f64, vol: f64) -> Vec<f32> {
    let mut out = samples(len);
    for (i, o) in out.iter_mut().enumerate() {
        let t = i as f64 / R;
        *o = (square(freq * t, 0.5) * vol * (-t / (len * 0.4)).exp()) as f32;
    }
    master(out, 0.7)
}

fn tone(freq: f64, len: f64, vol: f64, duty: f64) -> Vec<f32> {
    let mut out = samples(len);
    for (i, o) in out.iter_mut().enumerate() {
        let t = i as f64 / R;
        let env = (t / 0.005).min(1.0) * ((len - t) / 0.04).min(1.0);
        *o = (square(freq * t, duty) * vol * env) as f32;
    }
    master(out, 0.7)
}

/// notes one after another, the last held three times as long
fn arp(freqs: &[f64], each: f64, vol: f64) -> Vec<f32> {
    let last = freqs.len() - 1;
    freqs
        .iter()
        .enumerate()
        .flat_map(|(i, &f)| tone(f, if i == last { each * 3.0 } else { each }, vol, 0.25))
        .collect()
}

fn sweep(from: f64, to: f64, len: f64, vol: f64, duty: f64) -> Vec<f32> {
    let mut out = samples(len);
    let n = out.len() as f64;
    let mut phase = 0.0;
    for (i, o) in out.iter_mut().enumerate() {
        let k = i as f64 / n;
        phase += from * (to / from).powf(k) / R;
        *o = (square(phase, duty) * vol * (1.0 - k * 0.7) * ((1.0 - k) * 10.0).min(1.0)) as f32;
    }
    master(out, 0.7)
}

/// a shimmering bubble going up
fn shield() -> Vec<f32> {
    let mut out = samples(0.6);
    let n = out.len() as f64;
    for (i, o) in out.iter_mut().enumerate() {
        let (t, k) = (i as f64 / R, i as f64 / n);
        let f = 600.0 * 2f64.powf(k * 1.5);
        let s = (2.0 * PI * f * t).sin() + 0.5 * (2.0 * PI * f * 1.5 * t).sin() + 0.3 * (2.0 * PI * f * 2.0 * t).sin();
        *o = (s * 0.25 * (PI * k).sin() * (0.7 + 0.3 * (2.0 * PI * 18.0 * t).sin())) as f32;
    }
    master(out, 0.7)
}

/// a metallic ping
fn ping() -> Vec<f32> {
    let mut out = samples(0.5);
    for (i, o) in out.iter_mut().enumerate() {
        let t = i as f64 / R;
        let ring = (2.0 * PI * 1760.0 * t).sin() + 0.5 * (2.0 * PI * 2640.0 * t).sin() + 0.3 * (2.0 * PI * 4430.0 * t).sin();
        *o = (ring * 0.3 * (-t / 0.12).exp()) as f32;
    }
    master(out, 0.7)
}

// ================================================================== synth pieces

enum Wave {
    Pulse,
    Triangle,
}

/// adds one note, with a short release after its length
fn add_note(out: &mut [f32], start: i32, len: f64, freq: f64, wave: Wave, vol: f64, duty: f64) {
    let n = (len * R) as i32;
    let release = (0.03 * R) as i32;
    let mut i = 0;
    while i < n + release && ((start + i) as usize) < out.len() {
        if start + i >= 0 {
            let t = i as f64 / R;
            let mut env = (t / 0.004).min(1.0);
            env *= if i < n { 0.75 + 0.25 * (-t / 0.08).exp() } else { 0.75 * (1.0 - (i - n) as f64 / release as f64) };
            let v = match wave {
                Wave::Triangle => triangle(freq * t),
                Wave::Pulse => square(freq * t, if duty <= 0.0 { 0.5 } else { duty }),
            };
            out[(start + i) as usize] += (v * vol * env) as f32;
        }
        i += 1;
    }
}

fn kick(out: &mut [f32], at: i32, vol: f64) {
    let at = at as usize;
    let mut phase = 0.0;
    let mut i = 0;
    while (i as f64) < R * 0.16 && at + i < out.len() {
        let t = i as f64 / R;
        phase += (45.0 + 120.0 * (-t / 0.025).exp()) / R;
        out[at + i] += ((2.0 * PI * phase).sin() * vol * (-t / 0.07).exp()) as f32;
        i += 1;
    }
}

fn square(phase: f64, duty: f64) -> f64 {
    if phase % 1.0 < duty {
        1.0
    } else {
        -1.0
    }
}

fn triangle(phase: f64) -> f64 {
    let p = phase % 1.0;
    if p < 0.5 {
        4.0 * p - 1.0
    } else {
        3.0 - 4.0 * p
    }
}

/// two passes of a one-pole filter, the first only to settle it, so the loop's ends match
fn lowpass(out: &mut [f32], k: f64) {
    let mut y = out[out.len() - 1] as f64;
    for pass in 0..2 {
        for o in out.iter_mut() {
            y += (*o as f64 - y) * k;
            if pass == 1 {
                *o = y as f32;
            }
        }
    }
}

/// normalise to a peak, with a soft clip so loud mixes don't crackle
fn master(mut out: Vec<f32>, peak: f32) -> Vec<f32> {
    let max = out.iter().fold(1e-6f32, |m, v| m.max(v.abs()));
    for o in out.iter_mut() {
        let v = (*o / max) as f64 * 1.15;
        *o = (v.tanh() / 1.15f64.tanh() * peak as f64) as f32;
    }
    out
}

// ================================================================== notes and chords

fn midi(note: i32) -> f64 {
    440.0 * 2f64.powf((note - 69) as f64 / 12.0)
}

/// "C#5" -> 73, "Bb4" -> 70
fn note_number(name: &str) -> i32 {
    const BASE: [i32; 7] = [9, 11, 0, 2, 4, 5, 7];
    let b = name.as_bytes();
    let mut n = BASE[(b[0] - b'A') as usize];
    let mut i = 1;
    if b[1] == b'#' {
        n += 1;
        i += 1;
    } else if b[1] == b'b' && b.len() > 2 {
        n -= 1;
        i += 1;
    }
    n + 12 * (name[i..].parse::<i32>().expect("octave") + 1)
}

/// "Em" / "Bb" / "C" -> the chord's notes around the fourth octave (root, third, fifth)
fn chord(name: &str) -> [i32; 3] {
    let minor = name.ends_with('m');
    let root = if minor { &name[..name.len() - 1] } else { name };
    let mut r = note_number(&format!("{root}4"));
    if r > 66 {
        r -= 12;
    }
    [r, r + if minor { 3 } else { 4 }, r + 7]
}

// ================================================================== saving

/// 16-bit mono PCM WAV
fn save(dir: &Path, name: &str, data: &[f32]) -> Result<(), String> {
    let bytes = data.len() as u32 * 2;
    let mut out = Vec::with_capacity(44 + bytes as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + bytes).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes()); // PCM
    out.extend_from_slice(&1u16.to_le_bytes()); // mono
    out.extend_from_slice(&(RATE as u32).to_le_bytes());
    out.extend_from_slice(&(RATE as u32 * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&bytes.to_le_bytes());
    for &v in data {
        let s = (v.clamp(-1.0, 1.0) * 32767.0) as f64;
        out.extend_from_slice(&((s + 0.5).floor() as i16).to_le_bytes());
    }
    let path = dir.join(format!("{name}.wav"));
    std::fs::write(&path, out).map_err(|e| format!("{}: {e}", path.display()))
}

/// music is streamed, so it ships as MP3 (112 kbps) rather than WAV
fn encode_mp3(dir: &Path, name: &str) -> Result<(), String> {
    let (wav, mp3) = (dir.join(format!("{name}.wav")), dir.join(format!("{name}.mp3")));
    let status = Command::new("lame")
        .args(["--quiet", "-b", "112"])
        .arg(&wav)
        .arg(&mp3)
        .status()
        .map_err(|e| format!("lame (needed to encode the music): {e}"))?;
    if !status.success() {
        return Err(format!("lame failed on {}", wav.display()));
    }
    std::fs::remove_file(&wav).map_err(|e| format!("{}: {e}", wav.display()))
}
