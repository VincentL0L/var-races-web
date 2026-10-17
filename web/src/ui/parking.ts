// Parking: six levels, from an easy first spot to a timed squeeze in a busy multi-storey garage.
// Drive into the glowing bay, inside the lines and facing the way the arrow points, and stop.
// Every bump into a parked car, pillar, wall or cone counts; three and the level's failed. Stars
// for a clean, quick park; the best are remembered.
//
// The car handles like a real one at parking speed: the front wheels steer and the back follows
// (a bicycle model), so it swings wide going forward and tucks in reversing. Drawn overhead on
// a 2D canvas of its own; there's nothing behind it in the WebGL world.
import type { Screen, Stage } from '../app';
import { asset, carSprite } from '../assets';
import * as audio from '../audio';
import { button, h } from '../dom';
import { down, onKey, touchScreen } from '../input';
import { nav } from '../nav';
import { pref } from '../prefs';
import { card, cardTitle, kicker, smallButton } from './cards';

const WORLD_W = 1000, WORLD_H = 640;
const CAR_W = 26, CAR_L = 52, WHEELBASE = 34;
const MAX_FORWARD = 150, MAX_REVERSE = 75, MAX_STEER = 36;
const MAX_BUMPS = 3;
const PARKED = ['ui/car4.png', 'ui/car5.png', 'ui/car6.png', 'ui/cab.png', 'ui/waymo.png',
  'ui/kei.png', 'ui/2cv.png', 'ui/cop.png', 'ui/cars/roadster_1.png', 'ui/cars/hauler_2.png'];
const RAD = Math.PI / 180;

/** a solid box at (x, y), w across x and h along y before turning; kind 0 wall/pillar, 1 parked car, 2 cone */
interface Thing { x: number; y: number; w: number; h: number; angle: number; kind: number; sprite: number }

interface Level {
  name: string;
  hint: string;
  garage: boolean;
  startX: number; startY: number; startHeading: number;
  spotX: number; spotY: number; spotW: number; spotH: number; spotAngle: number;
  timeLimit: number;
  par: number;
  things: Thing[];
  /** painted bays: x, y, angle (and optionally its two sides) */
  bays: number[][];
}

function level(name: string, hint: string, sx: number, sy: number, sh: number): Level {
  return { name, hint, garage: false, startX: sx, startY: sy, startHeading: sh, spotX: 0, spotY: 0, spotW: 40, spotH: 68,
    spotAngle: 0, timeLimit: 0, par: 30, things: [], bays: [] };
}
const thing = (x: number, y: number, w: number, hh: number, angle: number, kind: number, sprite: number): Thing =>
  ({ x, y, w, h: hh, angle, kind, sprite });
function spot(l: Level, x: number, y: number, angle: number): void {
  l.spotX = x;
  l.spotY = y;
  l.spotAngle = angle;
}
/** a row of bays facing `angle`; bay `gap` is the spot (or -1: all full), some others empty */
function row(l: Level, x0: number, y: number, count: number, step: number, angle: number, gap: number): void {
  for (let i = 0; i < count; i++) {
    const bx = x0 + i * step;
    l.bays.push([bx, y, angle]);
    if (i === gap) spot(l, bx, y, angle);
    else if ((i * 7) % 5 !== 1 || gap < 0) l.things.push(thing(bx, y, CAR_W, CAR_L, angle + (i % 3 === 0 ? 180 : 0), 1, (i + Math.trunc(y)) % PARKED.length));
  }
}
function cones(l: Level, x0: number, y0: number, x1: number, y1: number, n: number): void {
  for (let i = 0; i < n; i++) {
    const t = n === 1 ? 0 : i / (n - 1);
    l.things.push(thing(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 10, 10, 0, 2, 0));
  }
}
/** a raised kerb strip behind a row of bays, so you can't drive straight through */
const kerb = (l: Level, y: number) => l.things.push(thing(WORLD_W / 2, y, WORLD_W - 120, 14, 0, 0, 0));
function garageWalls(l: Level): void {
  l.things.push(thing(WORLD_W / 2, WORLD_H - 8, WORLD_W, 16, 0, 0, 0), thing(WORLD_W / 2, 8, WORLD_W, 16, 0, 0, 0),
    thing(WORLD_W - 8, WORLD_H / 2, 16, WORLD_H, 0, 0, 0));
}

function buildLevels(): Level[] {
  const levels: Level[] = [];
  // 1: drive straight into a gap in a row of bays
  let l = level('First Spot', 'Drive forward into the glowing bay.', 500, 120, 90);
  row(l, 220, 540, 9, 64, 90, 4);
  kerb(l, 590);
  l.par = 20;
  levels.push(l);

  // 2: reverse in: the arrow points out of the bay
  l = level('Back It In', 'Reverse in: the arrow shows which way the car must face.', 160, 300, 0);
  row(l, 260, 540, 8, 64, -90, 5);
  row(l, 260, 100, 8, 64, 90, -1);
  kerb(l, 590);
  kerb(l, 50);
  levels.push(l);

  // 3: angled bays
  l = level('Angled Bays', 'These bays are on a slant. Swing wide, then in.', 120, 300, 0);
  for (let i = 0; i < 9; i++) {
    const bx = 260 + i * 62, by = 520;
    l.bays.push([bx, by, 120]);
    if (i === 5) spot(l, bx, by, 120);
    else if (i % 3 !== 2) l.things.push(thing(bx, by, CAR_W, CAR_L, 120 + 180 * (i % 2), 1, i % PARKED.length));
  }
  cones(l, 260, 200, 760, 200, 9);
  kerb(l, 595);
  levels.push(l);

  // 4: parallel parking between two cars at the kerb
  l = level('Parallel Park', 'Pull alongside the gap, then reverse in at an angle.', 860, 400, 180);
  l.things.push(thing(WORLD_W / 2, 590, WORLD_W, 36, 0, 0, 0));
  const kerbY = 572 - CAR_W / 2 - 4;
  for (let cx = 110; cx < 940; cx += 74) {
    if (Math.abs(cx - 480) < 60) continue;
    l.things.push(thing(cx, kerbY, CAR_L, CAR_W, 180, 1, Math.trunc(cx / 74) % PARKED.length));
  }
  l.spotX = 480;
  l.spotY = kerbY;
  l.spotW = CAR_L + 14;
  l.spotH = CAR_W + 8;
  l.spotAngle = 180;
  l.bays.push([480, kerbY, 0, CAR_L + 14, CAR_W + 8]);
  l.par = 35;
  levels.push(l);

  // 5: a multi-storey garage with pillars between the bays
  l = level('Pillar Squeeze', 'Narrow bays and concrete pillars. Take it slow.', 140, 320, 0);
  l.garage = true;
  for (let i = 0; i < 8; i++) {
    const bx = 250 + i * 46, top = 540, bottom = 100;
    l.bays.push([bx, top, 90, 38, 64], [bx, bottom, -90, 38, 64]);
    if (i === 6) {
      l.spotX = bx;
      l.spotY = top;
      l.spotW = 38;
      l.spotAngle = 90;
    } else l.things.push(thing(bx, top, CAR_W, CAR_L, 90 + (i % 2) * 180, 1, i % PARKED.length));
    if (i !== 3) l.things.push(thing(bx, bottom, CAR_W, CAR_L, -90 + (i % 2) * 180, 1, (i + 3) % PARKED.length));
    if (i % 2 === 0) l.things.push(thing(bx - 23, top + 26, 12, 12, 0, 0, 0), thing(bx - 23, bottom - 26, 12, 12, 0, 0, 0));
  }
  garageWalls(l);
  l.par = 35;
  levels.push(l);

  // 6: against the clock through a busy garage, reversing in at the far end
  l = level('Rush Hour Garage', '45 seconds. Weave through, then back into the bay.', 100, 110, 0);
  l.garage = true;
  l.timeLimit = 45;
  garageWalls(l);
  for (let i = 0; i < 6; i++) {
    const bx = 260 + i * 46;
    l.bays.push([bx, 230, 90, 40, 64]);
    l.things.push(thing(bx, 230, CAR_W, CAR_L, 90, 1, i % PARKED.length));
  }
  for (let i = 0; i < 6; i++) {
    const bx = 520 + i * 46;
    l.bays.push([bx, 420, -90, 40, 64]);
    l.things.push(thing(bx, 420, CAR_W, CAR_L, -90, 1, (i + 4) % PARKED.length));
  }
  l.things.push(thing(330, 470, 16, 16, 0, 0, 0), thing(660, 170, 16, 16, 0, 0, 0));
  cones(l, 420, 330, 420, 330, 1);
  cones(l, 560, 300, 640, 300, 3);
  for (let i = 0; i < 5; i++) {
    const by = 150 + i * 70;
    l.bays.push([890, by, 0, 64, 40]);
    if (i === 3) {
      l.spotX = 890;
      l.spotY = by;
      l.spotW = 64;
      l.spotH = 40;
      l.spotAngle = 180;
    } else l.things.push(thing(890, by, CAR_L, CAR_W, i % 2 === 0 ? 0 : 180, 1, (i + 2) % PARKED.length));
  }
  levels.push(l);
  return levels;
}

type P = [number, number];
/** the four corners of a box of length l (along its angle) and width w */
function box(cx: number, cy: number, l: number, w: number, angle: number): P[] {
  const c = Math.cos(angle * RAD), s = Math.sin(angle * RAD);
  return [[1, 1], [1, -1], [-1, -1], [-1, 1]].map(([a, b]) => {
    const lx = (a * l) / 2, ly = (b * w) / 2;
    return [cx + lx * c - ly * s, cy + lx * s + ly * c] as P;
  });
}
/** separating axis test between two boxes */
function overlap(a: P[], b: P[]): boolean {
  for (const s of [a, b]) {
    for (let i = 0; i < 2; i++) {
      const nx = -(s[i + 1][1] - s[i][1]), ny = s[i + 1][0] - s[i][0];
      const pa = a.map((p) => p[0] * nx + p[1] * ny), pb = b.map((p) => p[0] * nx + p[1] * ny);
      if (Math.max(...pa) < Math.min(...pb) || Math.max(...pb) < Math.min(...pa)) return false;
    }
  }
  return true;
}
const approach = (v: number, target: number, step: number) => (v < target ? Math.min(target, v + step) : Math.max(target, v - step));
const starText = (n: number) => '*'.repeat(n) + '-'.repeat(3 - n);
function bestStars(i: number): number {
  try {
    return Number(localStorage.getItem(`var-races-parking${i}`) ?? 0);
  } catch {
    return 0;
  }
}
function saveStars(i: number, stars: number): void {
  try {
    if (stars > bestStars(i)) localStorage.setItem(`var-races-parking${i}`, String(stars));
  } catch {
    // not remembered this time
  }
}

function image(name: string): HTMLImageElement {
  const img = new Image();
  img.src = asset(name.replace('.png', '.webp'));
  return img;
}

/** a speckled asphalt / concrete tile */
function noise(r: number, g: number, b: number): HTMLCanvasElement {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const x = c.getContext('2d')!;
  let seed = 5;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  for (let py = 0; py < 64; py++) {
    for (let px = 0; px < 64; px++) {
      const f = 1 + (rnd() - 0.5) * 0.12;
      x.fillStyle = `rgb(${r * f * 255},${g * f * 255},${b * f * 255})`;
      x.fillRect(px, py, 1, 1);
    }
  }
  return c;
}

export class ParkingScreen implements Screen {
  readonly el: HTMLElement;
  /** nothing in the WebGL world: the lot is drawn on a canvas of its own */
  readonly stage: Stage | null = null;
  private readonly levels = buildLevels();
  private readonly canvas = h('canvas', { style: { position: 'absolute', inset: '0', width: '100%', height: '100%' } });
  private readonly g = this.canvas.getContext('2d')!;
  private readonly carArt = image(carSprite(pref('car')));
  private readonly parkedArt = PARKED.map(image);
  private readonly floor = noise(0.36, 0.36, 0.38);
  private readonly concrete = noise(0.52, 0.52, 0.5);
  private readonly menu: HTMLElement;
  private readonly hud: HTMLElement;
  private readonly result: HTMLElement;
  private readonly title = h('span.title.small.plain', { style: { flex: '1' } });
  private readonly status = h('span', { style: { marginRight: '14px' } });
  private readonly hint = h('div.muted', { style: { textAlign: 'center', marginTop: '6px' } });
  private readonly resultTitle = cardTitle('', 32);
  private readonly resultText = h('div.label');
  private readonly nextButton: HTMLButtonElement;
  private readonly touch = [false, false, false, false];
  private readonly stopKeys: () => void;

  private level: Level | null = null;
  private levelIndex = -1;
  private x = 0; private y = 0; private heading = 0; private speed = 0; private steer = 0;
  private clock = 0; private stillTime = 0; private shake = 0;
  private bumps = 0;
  private finished = false;

  constructor() {
    audio.playMusic('music_title');
    const list = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '6px', width: 'min(400px, 100%)' } },
      ...this.levels.map((l, i) => {
        const b = smallButton(`${i + 1}. ${l.name.toUpperCase()}  ${starText(bestStars(i))}`, () => this.start(i));
        b.style.textAlign = 'left';
        return b;
      }));
    this.menu = h('div.screen.frosted', null, card(kicker('SINGLE PLAYER'), cardTitle('PARKING', 36),
      h('div.muted', { style: { whiteSpace: 'pre-line', marginBottom: '10px' } },
        touchScreen ? 'Park in the glowing bay, facing the arrow, and stop.\nThree bumps and you fail.'
          : 'Park in the glowing bay, facing the arrow, and stop.\nW / S drive and reverse, A / D steer, SPACE brake. Three bumps and you fail.'),
      list, smallButton('Back', () => void nav.mapSelect())));

    let pads: HTMLElement | null = null;
    if (touchScreen) {
      const pad = (label: string, k: number) => {
        const b = button(label, () => {});
        const set = (v: boolean) => (e: Event) => {
          e.preventDefault();
          this.touch[k] = v;
        };
        b.addEventListener('pointerdown', set(true));
        b.addEventListener('pointerup', set(false));
        b.addEventListener('pointerleave', set(false));
        b.addEventListener('pointercancel', set(false));
        b.style.width = '110px';
        b.style.height = '70px';
        return b;
      };
      pads = h('div', { style: { display: 'flex', justifyContent: 'space-between', width: '100%', padding: '8px', pointerEvents: 'auto' } },
        h('div.row', null, pad('LEFT', 0), pad('RIGHT', 1)), h('div.row', null, pad('REVERSE', 2), pad('GAS', 3)));
    }
    this.hud = h('div.screen', { style: { justifyContent: 'space-between', padding: '12px', display: 'none' } },
      h('div', { style: { width: '100%' } },
        h('div.soccer-strip', null, this.title, this.status, smallButton('Levels', () => this.showMenu())), this.hint),
      pads);

    this.nextButton = button('Next level', () => (this.levelIndex + 1 < this.levels.length ? this.start(this.levelIndex + 1) : this.showMenu()));
    this.result = h('div.screen.frosted', { style: { display: 'none' } }, card(this.resultTitle, this.resultText,
      h('div.row', null, smallButton('Levels', () => this.showMenu()), smallButton('Retry', () => this.start(this.levelIndex)), this.nextButton)));

    this.el = h('div', { style: { position: 'absolute', inset: '0', background: '#141417' } }, this.canvas, this.menu, this.hud, this.result);
    this.stopKeys = onKey((k) => {
      if (k !== 'Escape') return;
      if (this.level && !this.finished && this.menu.style.display === 'none') this.showMenu();
      else void nav.mapSelect();
    });
  }

  private start(i: number): void {
    this.levelIndex = i;
    const l = (this.level = this.levels[i]);
    this.x = l.startX;
    this.y = l.startY;
    this.heading = l.startHeading;
    this.speed = this.steer = this.clock = this.stillTime = this.shake = 0;
    this.bumps = 0;
    this.finished = false;
    this.menu.style.display = 'none';
    this.result.style.display = 'none';
    this.hud.style.display = '';
    this.title.textContent = `${i + 1}. ${l.name.toUpperCase()}`;
    this.hint.textContent = l.hint;
    audio.play('click', 0.5);
  }

  private showMenu(): void {
    void nav.parking();
  }

  frame(delta: number): void {
    const dt = Math.min(delta, 0.05);
    if (this.level && !this.finished && this.menu.style.display === 'none') this.update(dt);
    this.draw();
  }

  private update(dt: number): void {
    const l = this.level!;
    const t = this.touch;
    const gas = down('KeyW', 'ArrowUp') || t[3], back = down('KeyS', 'ArrowDown') || t[2], brake = down('Space');
    const steerIn = (down('KeyA', 'ArrowLeft') || t[0] ? 1 : 0) - (down('KeyD', 'ArrowRight') || t[1] ? 1 : 0);
    // pedals: gas forward, S brakes then reverses; let go and it rolls to a stop
    if (brake) this.speed = approach(this.speed, 0, 400 * dt);
    else if (gas) this.speed = this.speed < 0 ? approach(this.speed, 0, 300 * dt) : Math.min(MAX_FORWARD, this.speed + 110 * dt);
    else if (back) this.speed = this.speed > 0 ? approach(this.speed, 0, 300 * dt) : Math.max(-MAX_REVERSE, this.speed - 80 * dt);
    else this.speed = approach(this.speed, 0, 70 * dt);
    this.steer = approach(this.steer, steerIn * MAX_STEER, 120 * dt);
    // the back wheels follow the front: turn rate from speed and the wheels' angle
    const px = this.x, py = this.y, ph = this.heading;
    this.heading += (this.speed / WHEELBASE) * Math.tan(this.steer * RAD) / RAD * dt;
    this.x += Math.cos(this.heading * RAD) * this.speed * dt;
    this.y += Math.sin(this.heading * RAD) * this.speed * dt;
    if (this.hits()) {
      this.x = px;
      this.y = py;
      this.heading = ph;
      if (Math.abs(this.speed) > 12) {
        this.bumps++;
        this.shake = 6;
        audio.play('hit', 0.6, audio.random(0.9, 1.1));
        if (this.bumps >= MAX_BUMPS) this.end(false, 'Three bumps. Your insurance has questions.');
      }
      this.speed = -this.speed * 0.2;
    }
    this.clock += dt;
    if (l.timeLimit > 0 && this.clock >= l.timeLimit && !this.finished) this.end(false, 'Out of time.');
    // parked: fully inside the lines, the right way round, and stopped for a moment
    const inside = this.parked();
    this.stillTime = inside && Math.abs(this.speed) < 4 ? this.stillTime + dt : 0;
    if (this.stillTime > 0.8 && !this.finished) {
      const stars = this.bumps === 0 && this.clock <= l.par ? 3 : this.bumps <= 1 ? 2 : 1;
      saveStars(this.levelIndex, stars);
      this.end(true, `Parked in ${this.clock.toFixed(1)}s with ${this.bumps} ${this.bumps === 1 ? 'bump' : 'bumps'}.  ${starText(stars)}`);
    }
    const time = l.timeLimit > 0 ? `TIME ${Math.max(0, Math.ceil(l.timeLimit - this.clock))}` : `TIME ${Math.trunc(this.clock)}s`;
    this.status.textContent = `${time}   BUMPS ${this.bumps}/${MAX_BUMPS}   ${this.speed < -1 ? 'R' : 'D'}${inside ? '   IN THE BAY - STOP' : ''}`;
    this.shake *= Math.exp(-10 * dt);
  }

  private end(won: boolean, text: string): void {
    this.finished = true;
    this.speed = 0;
    audio.play(won ? 'finish' : 'back', 0.6);
    this.resultTitle.textContent = won ? 'PARKED!' : 'FAILED';
    this.resultTitle.style.color = won ? '' : '#e0503c';
    this.resultText.textContent = text;
    this.nextButton.style.display = won ? '' : 'none';
    this.nextButton.textContent = this.levelIndex + 1 < this.levels.length ? 'Next level' : 'All done';
    this.result.style.display = '';
  }

  private hits(): boolean {
    const mine = box(this.x, this.y, CAR_L, CAR_W, this.heading);
    if (mine.some(([x, y]) => x < 0 || y < 0 || x > WORLD_W || y > WORLD_H)) return true;
    return this.level!.things.some((t) => overlap(mine, t.kind === 1 && t.h > t.w ? box(t.x, t.y, t.h, t.w, t.angle) : box(t.x, t.y, t.w, t.h, t.angle)));
  }

  /** inside the bay with every corner, and facing within 12 degrees of the arrow */
  private parked(): boolean {
    const l = this.level!;
    const c = Math.cos(l.spotAngle * RAD), s = Math.sin(l.spotAngle * RAD);
    const along = Math.max(l.spotW, l.spotH), across = Math.min(l.spotW, l.spotH);
    for (const [x, y] of box(this.x, this.y, CAR_L, CAR_W, this.heading)) {
      const dx = x - l.spotX, dy = y - l.spotY;
      if (Math.abs(dx * c + dy * s) > along / 2 || Math.abs(-dx * s + dy * c) > across / 2) return false;
    }
    return Math.abs((((this.heading - l.spotAngle) % 360) + 540) % 360 - 180) < 12;
  }

  private draw(): void {
    const cv = this.canvas, g = this.g;
    const w = Math.round(cv.clientWidth * devicePixelRatio), hh = Math.round(cv.clientHeight * devicePixelRatio);
    if (cv.width !== w || cv.height !== hh) {
      cv.width = w;
      cv.height = hh;
    }
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = '#141417';
    g.fillRect(0, 0, w, hh);
    const l = this.level;
    if (!l || this.menu.style.display !== 'none') return;
    // fit the lot in the window, world y up
    const k = Math.min(w / WORLD_W, hh / WORLD_H);
    const sx = (Math.random() * 2 - 1) * this.shake, sy = (Math.random() * 2 - 1) * this.shake;
    g.setTransform(k, 0, 0, -k, (w - WORLD_W * k) / 2 + sx * k, (hh + WORLD_H * k) / 2 + sy * k);
    g.imageSmoothingEnabled = false;
    g.fillStyle = g.createPattern(l.garage ? this.concrete : this.floor, 'repeat')!;
    g.save();
    g.scale(2, 2);
    g.fillRect(0, 0, WORLD_W / 2, WORLD_H / 2);
    g.restore();

    const rect = (x: number, y: number, rw: number, rh: number, angle: number, col: string) => {
      g.save();
      g.translate(x, y);
      g.rotate(angle * RAD);
      g.fillStyle = col;
      g.fillRect(-rw / 2, -rh / 2, rw, rh);
      g.restore();
    };
    // painted bay lines: two sides and the far end
    g.strokeStyle = l.garage ? '#f2cc33' : '#f2f2eb';
    g.lineWidth = 2.5;
    for (const b of l.bays) {
      const along = b.length > 4 ? Math.max(b[3], b[4]) : 68, across = b.length > 4 ? Math.min(b[3], b[4]) : 40;
      g.save();
      g.translate(b[0], b[1]);
      g.rotate(b[2] * RAD);
      g.beginPath();
      g.moveTo(-along / 2, -across / 2);
      g.lineTo(along / 2, -across / 2);
      g.lineTo(along / 2, across / 2);
      g.lineTo(-along / 2, across / 2);
      g.stroke();
      g.restore();
    }
    // the target bay: a pulsing green fill and an arrow for the way to face
    const along = Math.max(l.spotW, l.spotH), across = Math.min(l.spotW, l.spotH);
    rect(l.spotX, l.spotY, along, across, l.spotAngle, `rgba(51,230,102,${0.35 + 0.2 * Math.sin(this.clock * 5)})`);
    g.save();
    g.translate(l.spotX, l.spotY);
    g.rotate(l.spotAngle * RAD);
    g.fillStyle = 'rgba(77,255,128,0.9)';
    g.beginPath();
    g.moveTo(along * 0.32, 0);
    g.lineTo(-along * 0.05, across * 0.3);
    g.lineTo(-along * 0.05, -across * 0.3);
    g.fill();
    g.fillRect(-along * 0.3, -across * 0.09, along * 0.25, across * 0.18);
    g.restore();
    // walls, pillars and cones
    for (const t of l.things) {
      if (t.kind === 0) {
        rect(t.x, t.y, t.w, t.h, t.angle, '#404045');
        if (t.w < 30 && t.h < 30) {
          // pillars wear yellow and black hazard stripes
          g.fillStyle = '#f2cc33';
          g.fillRect(t.x - t.w / 2, t.y - t.h / 2, t.w, t.h * 0.3);
          g.fillRect(t.x - t.w / 2, t.y + t.h * 0.2, t.w, t.h * 0.3);
        }
      } else if (t.kind === 2) {
        g.fillStyle = '#f2731a';
        g.beginPath();
        g.arc(t.x, t.y, t.w / 2 + 1, 0, Math.PI * 2);
        g.fill();
        g.fillStyle = '#fff';
        g.beginPath();
        g.arc(t.x, t.y, t.w / 4, 0, Math.PI * 2);
        g.fill();
      }
    }
    for (const t of l.things) {
      if (t.kind === 1) this.drawCar(this.parkedArt[t.sprite], t.x, t.y, Math.max(t.w, t.h), Math.min(t.w, t.h), t.angle);
    }
    g.globalAlpha = 0.35;
    g.filter = 'brightness(0)';
    this.drawCar(this.carArt, this.x + 3, this.y - 3, CAR_L, CAR_W, this.heading);
    g.filter = 'none';
    g.globalAlpha = 1;
    this.drawCar(this.carArt, this.x, this.y, CAR_L, CAR_W, this.heading);
  }

  /** a car sprite (drawn pointing up) centred at x, y, pointing at angle */
  private drawCar(art: HTMLImageElement, cx: number, cy: number, l: number, w: number, angle: number): void {
    if (!art.complete || !art.naturalWidth) return;
    const g = this.g;
    g.save();
    g.translate(cx, cy);
    g.rotate((angle - 90) * RAD);
    g.scale(1, -1);  // the picture's top is the car's nose; world y is up
    g.drawImage(art, -w / 2, -l / 2, w, l);
    g.restore();
  }

  dispose(): void {
    this.stopKeys();
  }
}
