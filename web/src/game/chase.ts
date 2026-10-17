// Police Chase: an endless getaway down a three-lane city street, seen from behind the car
// (third person). Switch lanes round slower traffic, jump the road barriers, hit the ramps for
// air and grab the coins. Clip something while changing lanes and you stumble: the cop closes
// right in behind you, and a second stumble before you've shaken him (or anything head on) and
// you're caught. The street keeps getting faster.
//
// The world runs along +y: lanes at x = -LANE, 0, +LANE, buildings past the pavements.
// Everything ahead is made up as you go and forgotten once it's behind.
import * as THREE from 'three';
import type { Screen, Stage } from '../app';
import { carSprite } from '../assets';
import * as audio from '../audio';
import { button, h } from '../dom';
import { onKey } from '../input';
import { nav } from '../nav';
import { pref } from '../prefs';
import { CAR_L as MODEL_L, CarView } from '../render/cars';
import type { View } from '../render/renderer';
import { card, cardTitle, kicker, smallButton } from '../ui/cards';

const LANE = 26, ROAD_HALF = 42, WALK = 24, CAR_W = 12, CAR_L = 24;
const ROW = 70, AHEAD = 1100;
const GRAVITY = 420, JUMP = 150;
const TRAFFIC = 0, BARRIER = 1, RAMP = 2, COIN = 3, NITRO = 4, MAGNET = 5;
const TRAFFIC_ART = ['ui/car4.png', 'ui/car5.png', 'ui/car6.png', 'ui/cab.png', 'ui/waymo.png',
  'ui/kei.png', 'ui/2cv.png', 'ui/bus.png', 'ui/cars/roadster_1.png', 'ui/cars/hauler_2.png'];
const BUS = 7;
const SKY_TOP = new THREE.Color(0.27, 0.52, 0.9), SKY_LOW = new THREE.Color(0.78, 0.87, 0.97);
const PAINTS = [[0.93, 0.86, 0.74], [0.78, 0.84, 0.92], [0.92, 0.76, 0.7], [0.8, 0.9, 0.8], [0.88, 0.88, 0.88], [0.95, 0.9, 0.6]];

interface Thing { kind: number; lane: number; sprite: number; x: number; y: number; z: number; speed: number; gone: boolean; obj?: THREE.Object3D; car?: CarView }
interface Block { y1: number; mesh: THREE.Mesh }

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const rand = (a: number, b: number) => a + Math.random() * (b - a);
const randInt = (n: number) => Math.floor(Math.random() * n);

function best(): number {
  try {
    return Number(localStorage.getItem('var-races-chaseBest') ?? 0);
  } catch {
    return 0;
  }
}

/** the street, across its width: pavements, kerbs, asphalt and dashed lane lines (repeats along y) */
function roadTexture(): THREE.Texture {
  const w = 132, hh = 64;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = hh;
  const g = c.getContext('2d')!;
  for (let py = 0; py < hh; py++) {
    for (let px = 0; px < w; px++) {
      const u = (px / w) * (ROAD_HALF + WALK) * 2 - (ROAD_HALF + WALK);
      const n = (Math.random() - 0.5) * 0.05;
      let col: number[];
      if (Math.abs(u) > ROAD_HALF + 2) col = py % 16 === 0 || Math.trunc(Math.abs(u)) % 12 === 0 ? [0.6, 0.6, 0.6] : [0.72 + n, 0.72 + n, 0.7 + n];
      else if (Math.abs(u) > ROAD_HALF) col = (py >> 3) % 2 === 0 ? [0.85, 0.2, 0.17] : [1, 1, 1];
      else if (Math.abs(Math.abs(u) - LANE / 2) < 1.2 && py % 32 < 18) col = [0.95, 0.95, 0.92];
      else col = [0.27 + n, 0.27 + n, 0.29 + n];
      g.fillStyle = `rgb(${col[0] * 255},${col[1] * 255},${col[2] * 255})`;
      g.fillRect(px, py, 1, 1);
    }
  }
  const t = new THREE.CanvasTexture(c);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.wrapT = THREE.RepeatWrapping;
  return t;
}

/** a white-ish wall of windows, floors 12 high, tinted per building by vertex colour */
function facadeTexture(): THREE.Texture {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d')!;
  g.fillStyle = '#fff';
  g.fillRect(0, 0, 64, 64);
  for (let fy = 0; fy < 64; fy += 16) {
    g.fillStyle = '#d6d6d6';
    g.fillRect(0, fy + 14, 64, 2);
    for (let fx = 0; fx < 64; fx += 16) {
      g.fillStyle = Math.random() < 0.2 ? '#f5e7a1' : '#3b4a63';
      g.fillRect(fx + 4, fy + 3, 8, 9);
      g.fillStyle = '#8a8a8a';
      g.fillRect(fx + 4, fy + 12, 8, 1);
    }
  }
  const t = new THREE.CanvasTexture(c);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  return t;
}

const box = (w: number, d: number, hh: number, color: THREE.ColorRepresentation, x = 0, y = 0, z = 0): THREE.Mesh => {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, d, hh), new THREE.MeshBasicMaterial({ color }));
  m.position.set(x, y, z + hh / 2);
  return m;
};

export class ChaseStage implements Stage, View {
  readonly screen: Screen;
  readonly scene = new THREE.Scene();
  private readonly cam = new THREE.PerspectiveCamera(66, 16 / 9, 0.5, 1500);
  private readonly ground: THREE.Mesh;
  private readonly road: THREE.Mesh;
  private readonly roadTex = roadTexture();
  private readonly facade = facadeTexture();
  private readonly facadeMaterial: THREE.MeshBasicMaterial;
  private readonly player = new CarView(carSprite(pref('car')));
  private readonly cop = new CarView('ui/cop.png');
  private things: Thing[] = [];
  private blocks: Block[] = [];
  private readonly scoreLabel = h('span.title.small.plain', { style: { marginRight: '18px' } }, '0');
  private readonly coinLabel = h('span', { style: { color: '#ffd94d', marginRight: '18px' } }, 'COINS 0');
  private readonly powerLabel = h('span', { style: { flex: '1' } });
  private readonly bestLabel = h('span', { style: { marginRight: '12px' } }, `BEST ${best()}`);
  private readonly startCard: HTMLElement;
  private readonly endCard: HTMLElement;
  private readonly endText = h('div.label', { style: { whiteSpace: 'pre-line', textAlign: 'center' } });
  private readonly stopKeys: () => void;
  private readonly offTouch: () => void;

  private running = false; private over = false;
  private lane = 1; private prevLane = 1; private coins = 0;
  private x = 0; private y = 0; private z = 0; private vz = 0; private speed = 0; private distance = 0; private clock = 0; private camX = 0;
  private stumble = 0; private nitro = 0; private magnet = 0; private copGap = 140; private nextRow = 0; private nextBlock = 0; private shake = 0;
  private caughtAt = 0;
  private pending = { left: false, right: false, jump: false };

  constructor() {
    this.cam.up.set(0, 0, 1);
    this.scene.background = this.skyTexture();
    this.scene.fog = new THREE.Fog(SKY_LOW, 450, 1050);
    this.ground = new THREE.Mesh(new THREE.PlaneGeometry(1400, AHEAD + 300), new THREE.MeshBasicMaterial({ color: new THREE.Color(0.55, 0.55, 0.56) }));
    this.ground.position.z = -0.1;
    this.road = new THREE.Mesh(new THREE.PlaneGeometry((ROAD_HALF + WALK) * 2, AHEAD + 300), new THREE.MeshBasicMaterial({ map: this.roadTex }));
    this.roadTex.repeat.set(1, (AHEAD + 300) / 64);
    this.facadeMaterial = new THREE.MeshBasicMaterial({ map: this.facade, vertexColors: true });
    this.scene.add(this.ground, this.road, this.player.root, this.cop.root);
    for (const c of [this.player, this.cop]) {
      c.root.scale.setScalar(CAR_L / MODEL_L);
    }
    audio.playMusic('music_race');

    const quit = () => void nav.mapSelect();
    const strip = h('div.soccer-strip', null, this.scoreLabel, this.coinLabel, this.powerLabel, this.bestLabel, smallButton('Quit', quit));
    this.startCard = h('div.screen.frosted', null, card(kicker('SINGLE PLAYER'), cardTitle('POLICE CHASE', 34),
      h('div.muted', { style: { whiteSpace: 'pre-line', marginBottom: '14px' } },
        'Outrun the cops down an endless city street.\nLEFT / RIGHT (or swipe) to change lanes, UP or SPACE (or swipe up) to jump.\n'
        + "Dodge traffic, jump the barriers, hit the ramps, grab the coins.\nClip something changing lanes and the cop closes in. Twice and you're caught."),
      button('Floor it', () => this.begin())));
    this.endCard = h('div.screen.frosted', { style: { display: 'none' } }, card(cardTitle('BUSTED!', 36), this.endText,
      h('div.row', null, smallButton('Menu', quit), button('Run again', () => {
        // a moment's pause first, so a tap still held from the crash doesn't skip the result
        if (performance.now() - this.caughtAt > 800) this.begin();
      }))));
    const el = h('div.screen', { style: { padding: '12px', justifyContent: 'flex-start' } }, h('div', { style: { width: '100%' } }, strip), this.startCard, this.endCard);
    this.screen = { el };

    this.stopKeys = onKey((k) => {
      if (k === 'Escape') quit();
      else if (k === 'ArrowLeft' || k === 'KeyA') this.pending.left = true;
      else if (k === 'ArrowRight' || k === 'KeyD') this.pending.right = true;
      else if (k === 'ArrowUp' || k === 'KeyW' || k === 'Space') this.pending.jump = true;
      if (!this.running && !this.over && (k === 'Space' || k === 'Enter')) this.begin();
    });
    // a swipe: one swipe, one move
    let sx = 0, sy = 0, live = false;
    const downH = (e: PointerEvent) => {
      sx = e.clientX;
      sy = e.clientY;
      live = true;
    };
    const moveH = (e: PointerEvent) => {
      if (!live) return;
      const dx = e.clientX - sx, dy = e.clientY - sy;
      if (Math.abs(dx) < 40 && Math.abs(dy) < 40) return;
      if (Math.abs(dx) > Math.abs(dy)) {
        if (dx < 0) this.pending.left = true;
        else this.pending.right = true;
      } else if (dy < 0) this.pending.jump = true;
      live = false;
    };
    const upH = () => (live = false);
    el.addEventListener('pointerdown', downH);
    addEventListener('pointermove', moveH);
    addEventListener('pointerup', upH);
    this.offTouch = () => {
      removeEventListener('pointermove', moveH);
      removeEventListener('pointerup', upH);
    };
    this.reset();
  }

  private skyTexture(): THREE.Texture {
    const c = document.createElement('canvas');
    c.width = 1;
    c.height = 64;
    const g = c.getContext('2d')!;
    const grad = g.createLinearGradient(0, 0, 0, 64);
    grad.addColorStop(0, `#${SKY_TOP.getHexString()}`);
    grad.addColorStop(0.55, `#${SKY_LOW.getHexString()}`);
    grad.addColorStop(1, `#${SKY_LOW.getHexString()}`);
    g.fillStyle = grad;
    g.fillRect(0, 0, 1, 64);
    return new THREE.CanvasTexture(c);
  }

  // ---------------------------------------------------------------- the run

  private reset(): void {
    for (const t of this.things) this.drop(t);
    this.things = [];
    for (const b of this.blocks) this.dropBlock(b);
    this.blocks = [];
    this.lane = this.prevLane = 1;
    this.x = this.camX = 0;
    this.y = this.z = this.vz = this.distance = this.clock = 0;
    this.speed = 170;
    this.coins = 0;
    this.stumble = this.nitro = this.magnet = this.shake = 0;
    this.copGap = 30; // starts right behind you (in view), then drops back
    this.nextRow = 260;
    this.nextBlock = -300;
    this.generate();
  }

  private begin(): void {
    this.reset();
    this.running = true;
    this.over = false;
    this.startCard.style.display = 'none';
    this.endCard.style.display = 'none';
    audio.play('go', 0.6);
  }

  private caught(): void {
    this.running = false;
    this.over = true;
    this.caughtAt = performance.now();
    this.copGap = 16;
    this.shake = 8;
    audio.play('crash', 0.7);
    const score = this.score();
    const record = score > best();
    if (record) {
      try {
        localStorage.setItem('var-races-chaseBest', String(score));
      } catch {
        // not remembered this time
      }
    }
    this.bestLabel.textContent = `BEST ${best()}`;
    this.endText.textContent = `Score ${score}   |   ${Math.trunc(this.distance / 10)} m   |   ${this.coins} coins${record ? '\nNEW BEST!' : ''}`;
    this.endCard.style.display = '';
  }

  private score(): number {
    return Math.trunc(this.distance / 10) + this.coins * 10;
  }

  private update(dt: number): void {
    this.clock += dt;
    const { left, right, jump } = this.pending;
    if (left && this.lane > 0) this.prevLane = this.lane--;
    if (right && this.lane < 2) this.prevLane = this.lane++;
    if (jump && this.z <= 0.01) {
      this.vz = JUMP;
      audio.play('item_land', 0.4, 1.3);
    }
    this.x = lerp(this.x, (this.lane - 1) * LANE, 1 - Math.exp(-14 * dt));
    this.vz -= GRAVITY * dt;
    this.z = Math.max(0, this.z + this.vz * dt);
    if (this.z === 0) this.vz = Math.max(0, this.vz);

    // faster and faster; nitro faster still
    this.speed = Math.min(360, 170 + this.distance * 0.0045) * (this.nitro > 0 ? 1.45 : 1) * (this.stumble > 3.5 ? 0.8 : 1);
    this.y += this.speed * dt;
    this.distance += this.speed * dt;
    this.nitro = Math.max(0, this.nitro - dt);
    this.magnet = Math.max(0, this.magnet - dt);
    this.stumble = Math.max(0, this.stumble - dt);
    // the cop: right on your bumper after a stumble, dropping back once you're clear
    const want = this.stumble > 0 ? 30 : 160;
    this.copGap += (want - this.copGap) * Math.min(1, dt * (this.stumble > 0 ? 3 : 0.4));

    for (const t of this.things) {
      if (t.kind === TRAFFIC) t.y += t.speed * dt;
      if (t.kind === COIN && this.magnet > 0 && Math.abs(t.y - this.y) < 140) {
        t.x += (this.x - t.x) * Math.min(1, dt * 8);
        t.y += (this.y - t.y) * Math.min(1, dt * 4);
        t.z += (this.z + 6 - t.z) * Math.min(1, dt * 6);
      }
    }
    this.collide();
    // tidy away what's behind and make up what's ahead
    this.things = this.things.filter((t) => {
      const keep = !t.gone && t.y >= this.y - 120;
      if (!keep) this.drop(t);
      return keep;
    });
    this.blocks = this.blocks.filter((b) => {
      const keep = b.y1 >= this.y - 200;
      if (!keep) this.dropBlock(b);
      return keep;
    });
    this.generate();
    this.scoreLabel.textContent = String(this.score());
    this.coinLabel.textContent = `COINS ${this.coins}`;
    this.powerLabel.textContent = this.nitro > 0 ? `NITRO ${Math.ceil(this.nitro)}` : this.magnet > 0 ? `MAGNET ${Math.ceil(this.magnet)}`
      : this.stumble > 0 ? "THE COP'S RIGHT BEHIND YOU!" : '';
    this.powerLabel.style.color = this.stumble > 0 && this.nitro <= 0 && this.magnet <= 0 ? '#e0503c' : '#80e6ff';
    this.shake *= Math.exp(-8 * dt);
  }

  private collide(): void {
    const changing = Math.abs(this.x - (this.lane - 1) * LANE) > 5;
    for (const t of this.things) {
      if (t.gone) continue;
      const dx = Math.abs(t.x - this.x), dy = t.y - this.y;
      if (t.kind === COIN) {
        if (dx < 10 && Math.abs(dy) < 14 && Math.abs(t.z - (this.z + 6)) < 12) {
          t.gone = true;
          this.coins++;
          audio.play('item_pickup', 0.25, 1.4 + (this.coins % 5) * 0.05);
        }
        continue;
      }
      if (t.kind === NITRO || t.kind === MAGNET) {
        if (dx < 12 && Math.abs(dy) < 16 && this.z < 14) {
          t.gone = true;
          if (t.kind === NITRO) {
            this.nitro = 6;
            audio.play('nitro', 0.6);
          } else {
            this.magnet = 10;
            audio.play('shield', 0.5);
          }
        }
        continue;
      }
      if (t.kind === RAMP) {
        // drive up the ramp and get launched off the end
        if (dx < 13 && dy < 0 && dy > -36 && this.z < 4) {
          this.z = ((dy + 36) / 36) * 10;
          this.vz = Math.max(this.vz, 175);
        }
        continue;
      }
      const length = t.kind === TRAFFIC ? (t.sprite === BUS ? 34 : CAR_L) : 6;
      const height = t.kind === TRAFFIC ? (t.sprite === BUS ? 22 : 8) : 7;
      const overlapY = Math.abs(dy) < (length + CAR_L) / 2, overlapX = dx < (CAR_W + (t.kind === BARRIER ? 22 : CAR_W)) / 2 - 1;
      if (!overlapX || !overlapY || this.z > height) continue;
      if (this.nitro > 0) {
        // nitro smashes straight through
        t.gone = true;
        this.shake = 4;
        audio.play('hit', 0.5, 1.2);
        continue;
      }
      if (changing && t.lane === this.lane && t.lane !== this.prevLane) {
        // clipped it changing lanes: bounce back and stumble
        this.lane = this.prevLane;
        this.shake = 5;
        audio.play('hit', 0.6);
        if (this.stumble > 0) {
          this.caught();
          return;
        }
        this.stumble = 5;
        audio.play('pulse', 0.5, 0.7);
      } else {
        this.caught();
        return;
      }
    }
  }

  // ---------------------------------------------------------------- making the street

  private generate(): void {
    while (this.nextRow < this.y + AHEAD) {
      this.spawnRow(this.nextRow);
      const hard = Math.min(1, this.distance / 9000);
      this.nextRow += ROW * (1.25 - hard * 0.4) * (0.8 + Math.random() * 0.5);
    }
    while (this.nextBlock < this.y + AHEAD + 200) {
      this.buildBlock(this.nextBlock, this.nextBlock + 400);
      this.nextBlock += 400;
    }
  }

  private spawnRow(ry: number): void {
    const hard = Math.min(1, this.distance / 9000);
    const free = randInt(3); // one lane always stays open
    let ramped = false;
    for (let l = 0; l < 3; l++) {
      const r = Math.random();
      if (l === free) {
        if (r < 0.35) this.coinLine(l, ry, 5, 0);
        else if (r < 0.4) this.add(Math.random() < 0.5 ? NITRO : MAGNET, l, ry, 4);
        continue;
      }
      if (r < 0.32 + 0.22 * hard) {
        const sprite = randInt(TRAFFIC_ART.length);
        this.add(TRAFFIC, l, ry, 0, sprite, 40 + Math.random() * 40);
      } else if (r < 0.5 + 0.15 * hard) {
        this.add(BARRIER, l, ry, 0);
        if (Math.random() < 0.5) this.coinLine(l, ry - 10, 3, 1);
      } else if (!ramped && r < 0.56) {
        ramped = true;
        this.add(RAMP, l, ry, 0);
        this.coinLine(l, ry + 30, 6, 2);
      } else if (r < 0.75) this.coinLine(l, ry, 4, 0);
    }
  }

  private add(kind: number, lane: number, y: number, z: number, sprite = 0, speed = 0): void {
    const t: Thing = { kind, lane, sprite, x: (lane - 1) * LANE, y, z, speed, gone: false };
    if (kind === TRAFFIC) {
      t.car = new CarView(TRAFFIC_ART[sprite]);
      t.car.root.scale.setScalar((sprite === BUS ? 34 : CAR_L) / MODEL_L);
      this.scene.add(t.car.root);
    } else {
      t.obj = this.model(kind);
      this.scene.add(t.obj);
    }
    this.things.push(t);
  }

  private model(kind: number): THREE.Object3D {
    const g = new THREE.Group();
    if (kind === BARRIER) {
      // a striped road block on two legs
      for (let k = 0; k < 4; k++) g.add(box(5.5, 3, 3.5, k % 2 === 0 ? 0xe6261f : 0xffffff, -8.25 + k * 5.5, 0, 3.5));
      g.add(box(1.5, 2, 3.5, 0x4d4d52, -9.25, 0, 0), box(1.5, 2, 3.5, 0x4d4d52, 9.25, 0, 0));
    } else if (kind === RAMP) {
      // a yellow ramp rising toward its far end (stacked slabs)
      for (let k = 0; k < 6; k++) g.add(box(24, 6, ((k + 1) * 10) / 6, k % 2 === 0 ? 0xfacc26 : 0x26262b, 0, -33 + k * 6, 0));
    } else if (kind === COIN) {
      g.add(box(7, 1.2, 7, 0xffd133, 0, 0, -3.5), box(3, 1.4, 3, 0xfff299, 0, 0, -1.5));
    } else {
      // power-ups: a glowing box, cyan for nitro, purple for the magnet
      g.add(box(8, 8, 8, kind === NITRO ? 0x4dd9ff : 0xbf66ff, 0, 0, -4), box(4, 8.4, 4, 0xffffff, 0, 0, -2));
    }
    return g;
  }

  private drop(t: Thing): void {
    if (t.car) {
      this.scene.remove(t.car.root);
      t.car.dispose();
    }
    if (t.obj) {
      this.scene.remove(t.obj);
      t.obj.traverse((o) => {
        if (o instanceof THREE.Mesh) {
          o.geometry.dispose();
          (o.material as THREE.Material).dispose();
        }
      });
    }
  }

  private dropBlock(b: Block): void {
    this.scene.remove(b.mesh);
    b.mesh.geometry.dispose();
  }

  /** a trail of coins along a lane: flat, hopping over a barrier, or an arc off a ramp */
  private coinLine(l: number, y0: number, n: number, style: number): void {
    for (let i = 0; i < n; i++) {
      let z0 = 6;
      if (style === 1) z0 = 6 + 26 * Math.sin((Math.PI * (i + 0.5)) / n);
      else if (style === 2) z0 = 18 + 40 * Math.sin((Math.PI * (i + 0.5)) / n);
      this.add(COIN, l, y0 + i * 18, z0);
    }
  }

  /** buildings down both sides of a stretch of street: windows, a colour each, roofs, and gaps */
  private buildBlock(y0: number, y1: number): void {
    const pos: number[] = [], uv: number[] = [], col: number[] = [];
    const quad = (p: number[][], u: number[][], c: number[]) => {
      for (const i of [0, 1, 2, 0, 2, 3]) {
        pos.push(...p[i]);
        uv.push(...u[i]);
        col.push(...c);
      }
    };
    const wall = (ax: number, ay: number, bx: number, by: number, hh: number, c: number[], shade: number) => {
      const ua = (ax === bx ? ay : ax) / 64, ub = (ax === bx ? by : bx) / 64;
      quad([[ax, ay, 0], [bx, by, 0], [bx, by, hh], [ax, ay, hh]], [[ua, 0], [ub, 0], [ub, hh / 48], [ua, hh / 48]], c.map((v) => v * shade));
    };
    for (const side of [-1, 1]) {
      let yy = y0;
      while (yy < y1) {
        const len = 50 + randInt(5) * 20, hh = 40 + randInt(8) * 14;
        const front = side * (ROAD_HALF + WALK), back = side * (ROAD_HALF + WALK + 70 + randInt(3) * 20);
        const c = PAINTS[randInt(PAINTS.length)];
        const ya = yy, yb = Math.min(y1, yy + len);
        // the face to the street, the end walls and the roof (drawn both sides)
        wall(front, ya, front, yb, hh, c, 1);
        wall(back, ya, front, ya, hh, c, 0.7);
        wall(front, yb, back, yb, hh, c, 0.8);
        const lo = Math.min(front, back), hi = Math.max(front, back);
        quad([[lo, ya, hh], [hi, ya, hh], [hi, yb, hh], [lo, yb, hh]], [[0.5, 0.5], [0.5, 0.5], [0.5, 0.5], [0.5, 0.5]], [c[0] * 0.6, c[1] * 0.6, c[2] * 0.62]);
        yy = yb + (randInt(4) === 0 ? 16 : 2);
      }
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    const mesh = new THREE.Mesh(geo, this.facadeMaterial);
    this.facadeMaterial.side = THREE.DoubleSide;
    this.scene.add(mesh);
    this.blocks.push({ y1, mesh });
  }

  // ---------------------------------------------------------------- each frame

  frame(delta: number): void {
    const dt = Math.min(delta, 0.05);
    if (this.running) this.update(dt);
    this.pending = { left: false, right: false, jump: false };
    // the chase camera: behind and above, swinging gently after the car
    this.camX = lerp(this.camX, this.x * 0.75, 1 - Math.exp(-6 * dt));
    const sx = rand(-this.shake, this.shake), sz = rand(-this.shake, this.shake) * 0.5;
    this.cam.position.set(this.camX + sx, this.y - 58, 30 + this.z * 0.4 + sz);
    this.cam.lookAt(this.camX * 0.6 + this.x * 0.4, this.y + 70, 6 + this.z * 0.3);

    const y0 = this.y - 120, len = AHEAD + 300;
    this.ground.position.set(0, y0 + len / 2, -0.1);
    this.road.position.set(0, y0 + len / 2, 0);
    this.roadTex.offset.y = y0 / 64;

    const look = { frozen: false, burning: 0 };
    this.player.update(this.x, this.y, Math.PI / 2, look, this.clock);
    this.player.root.position.z = this.z;
    this.cop.update(lerp(this.x, 0, 0.3), this.y - this.copGap, Math.PI / 2, look, this.clock);
    for (const t of this.things) {
      if (t.car) t.car.update(t.x, t.y, Math.PI / 2, look, this.clock);
      else if (t.obj) {
        t.obj.position.set(t.x, t.y, t.z);
        if (t.kind === COIN || t.kind === NITRO || t.kind === MAGNET) t.obj.rotation.z = ((this.clock * 200 + t.y) * Math.PI) / 180;
      }
    }
  }

  view(): View {
    return this;
  }

  camera(): THREE.Camera {
    return this.cam;
  }

  resize(width: number, height: number): void {
    const aspect = width / Math.max(1, height);
    if (this.cam.aspect !== aspect) {
      this.cam.aspect = aspect;
      this.cam.updateProjectionMatrix();
    }
  }

  dispose(): void {
    this.stopKeys();
    this.offTouch();
    for (const t of this.things) this.drop(t);
    for (const b of this.blocks) this.dropBlock(b);
    this.player.dispose();
    this.cop.dispose();
    this.roadTex.dispose();
    this.facade.dispose();
  }
}
