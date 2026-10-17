// Race Car Soccer's stadium, seen from the driver's seat: a striped pitch with goals and nets,
// team-coloured walls and grandstands, a light cage marking the ceiling, the ball, the pickups
// and the cars (team colour, roof, wheels, and their boost flames, power shot and magnets).
// Pitch coordinates are the simulation's (x along the pitch, y across it, z up).
import * as THREE from 'three';
import { Builder, shade, vertexColors, type Rgb } from './geom';
import type { SoccerCar, SoccerPickup } from '../wasm';

export const BLUE: Rgb = [0.12, 0.55, 1];
export const ORANGE: Rgb = [1, 0.42, 0.08];
const GOLD: Rgb = [1, 0.77, 0.1];
const NITRO: Rgb = [0.1, 0.95, 1];
const SHOT: Rgb = [1, 0.22, 0.48];
const MAGNET: Rgb = [0.76, 0.35, 1];
const INACTIVE: Rgb = [0.09, 0.19, 0.17];
const WHITE: Rgb = [1, 1, 1];
const MAGNET_RANGE = 240;

const color = (c: Rgb) => new THREE.Color(...c);

/** the stadium, which never changes */
function stadium(): THREE.Group {
  const g = new THREE.Group();
  const b = new Builder();
  const box = (x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, c: Rgb) => b.box(x0, y0, z0, x1, y1, z1, c);
  box(-100, -100, -3, 1500, 1000, -0.2, [0.06, 0.12, 0.17]);
  // the striped pitch
  for (let i = 0; i < 10; i++) {
    const c: Rgb = i % 2 === 0 ? [0.08, 0.36, 0.25] : [0.1, 0.4, 0.25];
    b.quad([i * 140, 0, 0], [(i + 1) * 140, 0, 0], [(i + 1) * 140, 900, 0], [i * 140, 900, 0], c);
  }
  const low = 310, high = 590;
  for (let end = 0; end < 2; end++) {
    const x = end === 0 ? -9 : 1400;
    const team = end === 0 ? BLUE : ORANGE;
    box(x, -9, 0, x + 9, low, 52, team);
    box(x, high, 0, x + 9, 909, 52, team);
    // above the crossbar is a wall too: high shots rebound instead of scoring
    box(x, low, 105, x + 9, high, 300, [0.09, 0.15, 0.23]);
    box(x - 3, low - 5, 0, x + 10, low + 5, 105, team);
    box(x - 3, high - 5, 0, x + 10, high + 5, 105, team);
    box(x - 3, low, 99, x + 10, high, 108, team);
    const back = end === 0 ? -80 : 1480;
    box(Math.min(x, back), low, -1, Math.max(x + 9, back), high, 0, team);
  }
  for (let side = 0; side < 2; side++) {
    const y = side === 0 ? -9 : 900;
    for (let i = 0; i < 14; i++) {
      const c = i < 7 ? BLUE : ORANGE;
      box(i * 100, y, 0, (i + 1) * 100, y + 9, 52, shade(c, 0.5));
      box(i * 100, y - 1, 49, (i + 1) * 100, y + 10, 53, c);
      // grandstands beyond the walls; they never hide the pitch
      const standY = side === 0 ? -80 : 950;
      box(i * 100, standY, 0, i * 100 + 90, standY + 35, 80, [0.09, 0.14, 0.22]);
      box(i * 100 + 4, standY + 3, 80, i * 100 + 85, standY + 20, 88, c);
    }
  }
  // the pitch markings
  const line: Rgb = [0.78, 0.94, 0.89];
  const mark = (x0: number, y0: number, w: number, h: number) => b.quad([x0, y0, 0.08], [x0 + w, y0, 0.08], [x0 + w, y0 + h, 0.08], [x0, y0 + h, 0.08], line);
  mark(698, 0, 4, 900);
  mark(0, 0, 1400, 3);
  mark(0, 897, 1400, 3);
  mark(0, 0, 3, 900);
  mark(1397, 0, 3, 900);
  mark(0, 250, 180, 3);
  mark(0, 647, 180, 3);
  mark(177, 250, 3, 400);
  mark(1220, 250, 180, 3);
  mark(1220, 647, 180, 3);
  mark(1220, 250, 3, 400);
  g.add(b.mesh(vertexColors()));
  // the centre circle, the nets and a light cage at the ceiling, as lines
  const segs: number[] = [], cols: number[] = [];
  const seg = (a: number[], c: number[], col: Rgb) => {
    segs.push(...a, ...c);
    cols.push(...col, ...col);
  };
  for (let i = 0; i < 64; i++) {
    const a = (i * Math.PI * 2) / 64, c = ((i + 1) * Math.PI * 2) / 64;
    seg([700 + 110 * Math.cos(a), 450 + 110 * Math.sin(a), 0.12], [700 + 110 * Math.cos(c), 450 + 110 * Math.sin(c), 0.12], [0.8, 0.97, 0.92]);
  }
  for (let end = 0; end < 2; end++) {
    const back = end === 0 ? -80 : 1480, front = end === 0 ? 0 : 1400, team = end === 0 ? BLUE : ORANGE;
    for (let y = 310; y <= 590; y += 20) seg([back, y, 0], [back, y, 105], team);
    for (let z = 0; z <= 105; z += 15) {
      seg([back, 310, z], [back, 590, z], team);
      seg([front, 310, z], [back, 310, z], team);
      seg([front, 590, z], [back, 590, z], team);
    }
  }
  for (let x = 0; x <= 1400; x += 100) {
    seg([x, 0, 52], [x, 0, 300], [0.29, 0.43, 0.55]);
    seg([x, 900, 52], [x, 900, 300], [0.29, 0.43, 0.55]);
    seg([x, 0, 300], [x, 900, 300], [0.29, 0.43, 0.55]);
  }
  const lines = new THREE.BufferGeometry();
  lines.setAttribute('position', new THREE.Float32BufferAttribute(segs, 3));
  lines.setAttribute('color', new THREE.Float32BufferAttribute(cols, 3));
  g.add(new THREE.LineSegments(lines, new THREE.LineBasicMaterial({ vertexColors: true })));
  return g;
}

/** the ball: a white sphere with dark patches */
function ball(): THREE.Mesh {
  const g = new THREE.SphereGeometry(26, 16, 8);
  const pos = g.getAttribute('position');
  const cols = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const lat = Math.min(7, Math.floor((Math.acos(Math.max(-1, Math.min(1, pos.getY(i) / 26))) / Math.PI) * 8));
    const lon = Math.floor(((Math.atan2(pos.getZ(i), pos.getX(i)) + Math.PI) / (Math.PI * 2)) * 16) % 16;
    const dark = (lat + Math.floor(lon / 2)) % 3 === 0;
    cols.set(dark ? [0.12, 0.18, 0.25] : [1, 1, 1], i * 3);
  }
  g.setAttribute('color', new THREE.BufferAttribute(cols, 3));
  return new THREE.Mesh(g, new THREE.MeshBasicMaterial({ vertexColors: true }));
}

/** a car in its team colour; own = our car, seen from inside (no roof in the way) */
class CarMesh {
  readonly group = new THREE.Group();
  private readonly shot: THREE.Mesh;
  private readonly magnets: THREE.Mesh[];
  private readonly flame: THREE.Mesh;
  private readonly flameCore: THREE.Mesh;

  constructor(team: Rgb, own: boolean) {
    const b = new Builder();
    b.box(-11, -18, 2, 11, 19, 7, team);
    b.box(-2, -18, 7, 2, 19, 7.15, WHITE);
    if (!own) {
      b.box(-9, -10, 7, 9, 3, 13, [0.06, 0.13, 0.2]);
      b.box(-9, -10, 13, 9, 3, 14, team);
    }
    for (const side of [-1, 1]) {
      b.box(side * 12 - 2, -14, 0, side * 12 + 2, -7, 6, [0.25, 0.25, 0.25]);
      b.box(side * 12 - 2, 9, 0, side * 12 + 2, 16, 6, [0.25, 0.25, 0.25]);
    }
    this.group.add(b.mesh(vertexColors()));
    const part = (x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, c: Rgb) => {
      const m = new THREE.Mesh(new THREE.BoxGeometry(x1 - x0, y1 - y0, z1 - z0), new THREE.MeshBasicMaterial({ color: color(c) }));
      m.position.set((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2);
      this.group.add(m);
      return m;
    };
    this.shot = part(-8, 16, 7.05, 8, 20, own ? 7.3 : 9, SHOT);
    this.magnets = [part(-11, 14, 7, -7, 24, 10, MAGNET), part(7, 14, 7, 11, 24, 10, MAGNET)];
    // the boost flame, pointing back, its length flickering
    this.flame = part(-8, -1, 2, 8, 0, 8, ORANGE);
    this.flameCore = part(-4, -1, 3, 4, 0, 7, WHITE);
  }

  update(c: SoccerCar, clock: number): void {
    this.group.position.set(c.x, c.y, c.z);
    this.group.rotation.z = c.heading - Math.PI / 2;
    this.shot.visible = c.shot_time > 0;
    for (const m of this.magnets) m.visible = c.magnet_time > 0;
    const on = c.boost_active;
    this.flame.visible = this.flameCore.visible = on;
    if (on) {
      const length = 18 + 8 * Math.sin(clock * 35);
      this.flame.scale.y = length;
      this.flame.position.y = -18 - length / 2;
      (this.flame.material as THREE.MeshBasicMaterial).color.set(color(c.nitro_time > 0 ? NITRO : ORANGE));
      this.flameCore.scale.y = length - 3;
      this.flameCore.position.y = -18 - (length - 3) / 2;
    }
  }
}

/** a pickup pad: a ring in its colour, and the boost post or spinning cube over it */
class PadMesh {
  readonly group = new THREE.Group();
  private readonly ring: THREE.Mesh;
  private readonly progress: THREE.Mesh;
  private readonly top: THREE.Group;
  private readonly c: Rgb;

  constructor(private readonly p: SoccerPickup) {
    const boost = p.kind === 'BoostPad' || p.kind === 'FullBoost';
    this.c = boost ? GOLD : p.kind === 'Nitro' ? NITRO : p.kind === 'PowerShot' ? SHOT : MAGNET;
    const r = p.kind === 'BoostPad' ? 14 : p.kind === 'FullBoost' ? 24 : 20;
    this.group.position.set(p.x, p.y, 0.14);
    this.ring = new THREE.Mesh(new THREE.RingGeometry(r - 3, r, 24), new THREE.MeshBasicMaterial({ color: color(this.c), side: THREE.DoubleSide }));
    const middle = new THREE.Mesh(new THREE.CircleGeometry(r - 3, 24), new THREE.MeshBasicMaterial({ color: new THREE.Color(0.07, 0.12, 0.15) }));
    middle.position.z = -0.01;
    this.progress = new THREE.Mesh(new THREE.RingGeometry(r - 4, r - 1, 24, 1, 0, Math.PI * 2), new THREE.MeshBasicMaterial({ color: color(this.c), side: THREE.DoubleSide }));
    this.progress.position.z = 0.02;
    this.group.add(this.ring, middle, this.progress);
    this.top = new THREE.Group();
    const b = new Builder();
    if (boost) {
      const tall = p.kind === 'FullBoost' ? 21 : 10;
      b.box(-5, -5, 2, 5, 5, tall, this.c);
      b.box(-2, -2, 3, 2, 2, tall + 3, WHITE);
    } else {
      b.box(-8, -8, -7, 8, 8, 7, this.c);
      b.box(-8.5, -2, -2, 8.5, 2, 2, WHITE);
      b.box(-2, -8.5, -2, 2, 8.5, 2, WHITE);
    }
    this.top.add(b.mesh(vertexColors()));
    this.group.add(this.top);
  }

  update(cooldown: number, respawn: number, clock: number): void {
    const available = cooldown <= 0;
    (this.ring.material as THREE.MeshBasicMaterial).color.set(color(available ? this.c : INACTIVE));
    this.top.visible = available;
    this.progress.visible = !available;
    if (available) {
      const bob = Math.sin(clock * 3 + this.p.x * 0.01) * 3;
      if (this.p.kind === 'BoostPad' || this.p.kind === 'FullBoost') this.top.position.z = bob;
      else {
        this.top.position.z = 16 + bob;
        this.top.rotation.z = (clock * 65 * Math.PI) / 180;
      }
    } else {
      // a growing ring shows how close this pad is to coming back
      const t = Math.max(0.001, Math.min(1, 1 - cooldown / respawn));
      this.progress.geometry.dispose();
      const r = this.p.kind === 'BoostPad' ? 14 : this.p.kind === 'FullBoost' ? 24 : 20;
      this.progress.geometry = new THREE.RingGeometry(r - 4, r - 1, 24, 1, 0, Math.PI * 2 * t);
    }
  }
}

export interface SoccerFrame {
  cars: SoccerCar[];
  ball: { x: number; y: number; z: number };
  pickups: SoccerPickup[];
  /** our slot, or -1 */
  me: number;
}

export class SoccerView {
  readonly scene = new THREE.Scene();
  readonly cam = new THREE.PerspectiveCamera(60, 16 / 9, 0.5, 2500);
  private readonly ball = ball();
  private readonly cars = new Map<number, CarMesh>();
  private readonly pads: PadMesh[] = [];
  private readonly magnetLines: THREE.LineSegments;
  private clock = 0;

  constructor(pickups: SoccerPickup[]) {
    this.scene.background = new THREE.Color(0.19, 0.32, 0.49);
    this.cam.up.set(0, 0, 1);
    this.scene.add(stadium(), this.ball);
    for (const p of pickups) {
      const pad = new PadMesh(p);
      this.pads.push(pad);
      this.scene.add(pad.group);
    }
    this.magnetLines = new THREE.LineSegments(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: color(MAGNET) }));
    this.scene.add(this.magnetLines);
  }

  camera(): THREE.Camera {
    return this.cam;
  }

  resize(width: number, height: number): void {
    const aspect = width / Math.max(1, height);
    this.cam.aspect = aspect;
    this.cam.fov = Math.max(45, Math.min(85, (2 * Math.atan(1.05 / aspect) * 180) / Math.PI));
    this.cam.updateProjectionMatrix();
  }

  update(f: SoccerFrame, delta: number): void {
    this.clock += Math.min(delta, 0.1);
    this.ball.position.set(f.ball.x, f.ball.y, f.ball.z);
    const seen = new Set<number>();
    for (const c of f.cars) {
      seen.add(c.slot);
      const own = c.slot === f.me;
      let mesh = this.cars.get(c.slot);
      if (!mesh || mesh.group.userData.own !== own) {
        if (mesh) this.scene.remove(mesh.group);
        mesh = new CarMesh(c.slot % 2 === 0 ? BLUE : ORANGE, own);
        mesh.group.userData.own = own;
        this.cars.set(c.slot, mesh);
        this.scene.add(mesh.group);
      }
      mesh.update(c, this.clock);
    }
    for (const [slot, mesh] of this.cars) {
      if (!seen.has(slot)) {
        this.scene.remove(mesh.group);
        this.cars.delete(slot);
      }
    }
    f.pickups.forEach((p, i) => {
      const respawn = p.kind === 'BoostPad' ? 4 : p.kind === 'FullBoost' ? 10 : 12;
      this.pads[i]?.update(p.cooldown, respawn, this.clock);
    });
    // magnets: lines from the bumper to a ball in range
    const pts: number[] = [];
    for (const c of f.cars) {
      if (c.magnet_time <= 0) continue;
      const dx = f.ball.x - c.x, dy = f.ball.y - c.y;
      if (dx * dx + dy * dy < MAGNET_RANGE * MAGNET_RANGE && Math.abs(f.ball.z - c.z) < 150) {
        pts.push(c.x + Math.cos(c.heading) * 19, c.y + Math.sin(c.heading) * 19, c.z + 8, f.ball.x, f.ball.y, f.ball.z);
        pts.push(c.x, c.y, c.z + 12, f.ball.x, f.ball.y, f.ball.z + 8);
      }
    }
    this.magnetLines.geometry.dispose();
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3));
    this.magnetLines.geometry = g;
    // the driver's seat
    const me = f.cars.find((c) => c.slot === f.me);
    const d = me ?? { x: 260, y: 450, z: 0, heading: 0 };
    const fx = Math.cos(d.heading), fy = Math.sin(d.heading);
    this.cam.position.set(d.x + fx * 3, d.y + fy * 3, d.z + 13.3);
    this.cam.lookAt(this.cam.position.x + fx, this.cam.position.y + fy, this.cam.position.z - 0.035);
  }
}
