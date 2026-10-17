// The race start: a gantry of three lights hangs under the speedometer and one more turns red on
// each of 3, 2, 1, then they all go green on GO!, with the count popping up big under the car.
// Before that, the red VAR RACES flag: a white flash, the flag rippling for a moment, then waved
// off to the left to show the cars on the grid.
import * as audio from '../audio';
import { h } from '../dom';

const GO_HOLD = 1;
const FADE = 0.4;

export class StartLights {
  readonly el: HTMLElement;
  private readonly lamps = [h('i'), h('i'), h('i')];
  private readonly count = h('div.count');
  private shown = '';
  private sinceChange = 0;
  private sinceGo = -1;

  constructor() {
    this.el = h('div', null, h('div.lights.panel', null, ...this.lamps), this.count);
    this.el.style.display = 'none';
  }

  get done(): boolean {
    return this.sinceGo >= GO_HOLD + FADE;
  }

  /** text: '' (waiting on the grid), '3', '2', '1' or 'GO!' */
  update(text: string, delta: number, hudScale: number): void {
    if (text !== this.shown) {
      this.shown = text;
      if (text === 'GO!') {
        audio.play('go', 0.55);
        this.sinceGo = 0;
      } else if (text) audio.play('beep', 0.5);
      this.sinceChange = 0;
    }
    this.sinceChange += delta;
    if (this.sinceGo >= 0) this.sinceGo += delta;
    if (this.done) {
      this.el.style.display = 'none';
      return;
    }
    this.el.style.display = '';
    const alpha = this.sinceGo < GO_HOLD ? 1 : 1 - (this.sinceGo - GO_HOLD) / FADE;
    const lights = this.el.firstElementChild as HTMLElement;
    lights.style.top = `${216 * hudScale}px`;
    lights.style.opacity = String(alpha);
    const go = text === 'GO!';
    const lit = text === '3' ? 1 : text === '2' ? 2 : text === '1' ? 3 : 0;
    this.lamps.forEach((l, i) => {
      l.className = go ? 'green' : i < lit ? 'red' : '';
    });
    // the count pops in big and settles, then fades just before the next one
    const t = 1 - Math.min(1, this.sinceChange / 0.3);
    const pop = 1 + 0.7 * t * t * t;
    const fade = go ? alpha : Math.max(0, Math.min(1, (1 - this.sinceChange) / 0.25));
    this.count.textContent = text;
    this.count.style.transform = `translateY(-50%) scale(${pop})`;
    this.count.style.opacity = String(fade);
    this.count.style.color = go ? '#40ff59' : '';
  }
}

const FLASH = 0.15;
const HOLD = 0.8;
const FLAG_TIME = 2.15;
const WAVE = FLAG_TIME - FLASH - HOLD;
const STRIPS = 28;

/** the red VAR RACES flag, drawn into a canvas over everything */
export class StartFlag {
  readonly el = h('canvas.flag');
  private picture: HTMLCanvasElement | null = null;
  private time = 0;

  constructor() {
    audio.play('flag', 0.6);
  }

  get done(): boolean {
    return this.time >= FLAG_TIME;
  }

  update(delta: number): void {
    this.time += delta;
    if (this.done) {
      this.el.remove();
      return;
    }
    const ratio = Math.min(2, devicePixelRatio);
    const w = Math.round(innerWidth * ratio), hgt = Math.round(innerHeight * ratio);
    if (this.el.width !== w || this.el.height !== hgt || !this.picture) {
      this.el.width = w;
      this.el.height = hgt;
      this.picture = paintFlag(w, hgt, ratio);
    }
    const g = this.el.getContext('2d')!;
    g.clearRect(0, 0, w, hgt);
    // 0 while the flag hangs, then 0..1 as it is waved off
    const wave = Math.max(0, Math.min(1, (this.time - FLASH - HOLD) / WAVE));
    const ripple = hgt * (0.006 + 0.03 * wave);
    const strip = w / STRIPS;
    // the whole flag slides off together, speeding up as it goes
    const slide = wave * wave * (3 - 2 * wave) * (w + ripple * 4);
    for (let i = 0; i < STRIPS; i++) {
      const phase = this.time * 9 - i * 0.45;
      const x = i * strip - slide;
      if (x + strip < 0) continue;
      // the trailing edge lifts and flaps more, like cloth being pulled away
      const lift = (wave * i) / STRIPS;
      const y = Math.sin(phase) * ripple * (0.4 + lift) + lift * ripple * 0.8;
      const sx = Math.floor(i * strip), sw = Math.ceil(strip) + 1;
      g.drawImage(this.picture, sx, 0, sw, hgt, x, -y - ripple * 2.2, sw, hgt + ripple * 4.4);
      // folds facing away from the light are a little darker
      const dark = (0.05 + 0.12 * wave) * (0.5 + 0.5 * Math.cos(phase));
      g.fillStyle = `rgba(0,0,0,${dark})`;
      g.fillRect(x, -y - ripple * 2.2, sw, hgt + ripple * 4.4);
    }
    if (this.time < FLASH) {
      g.fillStyle = `rgba(255,255,255,${1 - this.time / FLASH})`;
      g.fillRect(0, 0, w, hgt);
    }
  }
}

function paintFlag(w: number, hgt: number, ratio: number): HTMLCanvasElement {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = hgt;
  const g = c.getContext('2d')!;
  g.fillStyle = '#e8281e';
  g.fillRect(0, 0, w, hgt);
  const scale = Math.min(1, (w / ratio) * 0.8 / 900) * ratio;
  g.font = `${120 * scale}px Speed`;
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillStyle = '#730a08';
  g.fillText('VAR RACES', w / 2 + 5 * scale, hgt / 2 + 6 * scale);
  g.fillStyle = '#fff';
  g.fillText('VAR RACES', w / 2, hgt / 2);
  return c;
}
