// All the game's sound (made by tools): short effects in assets/audio/*.wav, loaded the first
// time they're played, and music (*.mp3). One piece of music plays at a time and carries on
// across screens until another is asked for. Browsers only allow sound after the player has
// touched the page, so everything starts quietly once they have.
import { asset } from './gen/assets';
import { pref, setPref } from './prefs';

export type Music = 'music_title' | 'music_race' | 'music_battle' | 'music_results';
const MUSIC_VOLUME = 0.45;

let ctx: AudioContext | null = null;
let master: GainNode;
const buffers = new Map<string, Promise<AudioBuffer | null>>();
let music: HTMLAudioElement | null = null;
let musicName: Music | null = null;
let muted = pref('muted');

function context(): AudioContext | null {
  if (!ctx) {
    try {
      ctx = new AudioContext();
      master = ctx.createGain();
      master.gain.value = muted ? 0 : 1;
      master.connect(ctx.destination);
    } catch {
      return null;
    }
  }
  return ctx;
}

// the first touch or key press unlocks sound
const unlock = () => {
  context()?.resume();
  if (music && music.paused && !muted) void music.play().catch(() => {});
};
addEventListener('pointerdown', unlock, { capture: true });
addEventListener('keydown', unlock, { capture: true });

function buffer(name: string): Promise<AudioBuffer | null> {
  let b = buffers.get(name);
  if (!b) {
    const c = context();
    b = c
      ? fetch(asset(`audio/${name}.wav`))
          .then((r) => r.arrayBuffer())
          .then((data) => c.decodeAudioData(data))
          .catch(() => null)
      : Promise.resolve(null);
    buffers.set(name, b);
  }
  return b;
}

/** plays an effect once; pitch 1 = as recorded (a little variety keeps repeats lively) */
export function play(name: string, volume = 0.6, pitch = 1): void {
  if (muted) return;
  const c = context();
  if (!c || c.state !== 'running') return;
  void buffer(name).then((b) => {
    if (!b) return;
    const src = c.createBufferSource();
    src.buffer = b;
    src.playbackRate.value = pitch;
    const gain = c.createGain();
    gain.gain.value = volume;
    src.connect(gain).connect(master);
    src.start();
  });
}

export function random(lo: number, hi: number): number {
  return lo + Math.random() * (hi - lo);
}

/** starts a piece of music (unless it's already the one playing) */
export function playMusic(name: Music, loop = true): void {
  if (name === musicName && music) return;
  stopMusic();
  music = new Audio(asset(`audio/${name}.mp3`));
  music.loop = loop;
  music.volume = muted ? 0 : MUSIC_VOLUME;
  musicName = name;
  void music.play().catch(() => {});
}

export function stopMusic(): void {
  music?.pause();
  music = null;
  musicName = null;
}

/** pauses or resumes the music (the pause menu) */
export function pauseMusic(pause: boolean): void {
  if (!music) return;
  if (pause) music.pause();
  else void music.play().catch(() => {});
}

export function isMuted(): boolean {
  return muted;
}

export function setMuted(value: boolean): void {
  muted = value;
  setPref('muted', value);
  if (music) music.volume = muted ? 0 : MUSIC_VOLUME;
  if (ctx) master.gain.value = muted ? 0 : 1;
  if (!muted) unlock();
}

/**
 * The engine: one looping sound whose pitch rises smoothly with road speed, like a car in one
 * long gear, with a little extra when the gas is down; louder on the gas.
 */
export class Engine {
  private src: AudioBufferSourceNode | null = null;
  private gain: GainNode | null = null;
  private starting = false;
  private boosting = false;

  update(speed: number, gas: boolean, boosting: boolean): void {
    const c = context();
    if (!c || muted || c.state !== 'running') {
      this.stop();
      return;
    }
    if (!this.src) {
      this.start(c);
      return;
    }
    const s = Math.min(1.3, Math.max(0, speed / 520));
    const pitch = 0.7 + 1.75 * Math.pow(s, 0.8) + (gas ? 0.12 : 0) + (boosting ? 0.15 : 0);
    const volume = gas ? 0.42 : 0.18 + 0.15 * Math.min(1, s);
    // ease toward the targets (time constant 1/8 s)
    this.src.playbackRate.setTargetAtTime(pitch, c.currentTime, 0.125);
    this.gain!.gain.setTargetAtTime(volume, c.currentTime, 0.125);
    // a whoosh when the boost kicks in
    if (boosting && !this.boosting) play('nitro', 0.35, 1.2);
    this.boosting = boosting;
  }

  private start(c: AudioContext): void {
    if (this.starting) return;
    this.starting = true;
    void buffer('engine').then((b) => {
      this.starting = false;
      if (!b || this.src) return;
      this.src = c.createBufferSource();
      this.src.buffer = b;
      this.src.loop = true;
      this.src.playbackRate.value = 0.7;
      this.gain = c.createGain();
      this.gain.gain.value = 0.15;
      this.src.connect(this.gain).connect(master);
      this.src.start();
    });
  }

  stop(): void {
    this.src?.stop();
    this.src?.disconnect();
    this.src = null;
    this.gain = null;
  }
}
