// One map in 3D: the ground (the map picture on its grass), the walls and turn boards, the
// scenery, the sky, the cars and the items, seen through either camera:
//  - overhead: an orthographic camera looking straight down, north up, following a car
//  - first person: from the driver's seat, over the bonnet
// Positions are map coordinates (x right, y up, z up off the ground).
import * as THREE from 'three';
import { pixels, texture } from '../assets';
import { mapLayout, type MapDef, type Sign, type Wall } from '../wasm';
import { CarView, eyeHeight, type CarLook } from './cars';
import { ItemsView, type ItemState } from './items';
import { buildLandmarks, type Area } from './landmarks';
import { buildScenery } from './scenery';
import { buildSky, skyColors, type SkyColors } from './sky';
import { barrier, chevrons } from './textures';

/** the overhead view shows at least this much of the map (map pixels) */
const VIEW_W = 675;
const VIEW_H = 360;
/** how quickly the overhead camera catches up with its car (per second) */
const FOLLOW_RATE = 12;
/** first person: the camera sits a little ahead of the car's centre, over the bonnet */
const EYE_FORWARD = 3.5;
const FAR = 2600;
const FOG_START = 700;
const FOG_END = 2400;
/** the barrier is knee high, under the driver's eyes */
const WALL_HEIGHT = 4.2;
const WALL_THICKNESS = 2.5;

export interface CarFrame {
  slot: number;
  x: number;
  y: number;
  heading: number;
  look: CarLook;
}

export interface Frame {
  cars: CarFrame[];
  items: ItemState;
  /** the car the camera follows (and, in first person, sits in) */
  follow: number | null;
  /** a crash's shake, in map pixels */
  shake: number;
}

export class World {
  readonly scene = new THREE.Scene();
  readonly overhead = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 4000);
  readonly first = new THREE.PerspectiveCamera(72, 1, 0.3, FAR);
  private readonly colors: SkyColors;
  private readonly fog: THREE.Fog;
  private readonly flatOnly = new THREE.Group();
  private readonly solidOnly = new THREE.Group();
  private readonly sky: THREE.Group;
  private readonly items: ItemsView;
  private readonly cars = new Map<number, CarView>();
  private firstPerson = false;
  private scenery: Promise<void> | null = null;
  private aspect = 16 / 9;
  private camX: number;
  private camY: number;
  /** overhead camera zoom: 1 = the race view, more = closer */
  zoom = 1;
  private heading = NaN;
  private eye = 6.3;
  private clock = 0;
  private followed: number | null = null;

  private constructor(readonly map: MapDef, layout: { walls: Wall[]; signs: Sign[]; boxes: { x: number; y: number }[][] }) {
    this.colors = skyColors(map.id);
    this.fog = new THREE.Fog(new THREE.Color(...this.colors.low), FOG_START, FOG_END);
    this.scene.background = new THREE.Color(...this.colors.low);
    this.first.up.set(0, 0, 1);
    this.overhead.position.z = 2000;
    this.camX = map.width / 2;
    this.camY = map.height / 2;
    this.sky = buildSky(map.id, this.colors);
    this.solidOnly.add(this.sky);
    this.scene.add(this.flatOnly, this.solidOnly);
    this.addWalls(layout.walls, layout.signs);
    this.items = new ItemsView(layout.boxes.flat().map((p) => [p.x, p.y]));
    this.scene.add(this.items.root);
    this.setFirstPerson(false);
  }

  /** builds a map's world (the ground picture is loaded before it's ready) */
  static async create(map: MapDef): Promise<World> {
    const world = new World(map, mapLayout(map.id));
    await world.addGround();
    return world;
  }

  private async addGround(): Promise<void> {
    const map = this.map;
    const picture = await texture(`maps/${map.id}/background.png`);
    const ground = new THREE.Group();
    const flat = (t: THREE.Texture) => new THREE.MeshBasicMaterial({ map: t, depthWrite: false, fog: true });
    const plane = (x0: number, y0: number, x1: number, y1: number, m: THREE.Material, uv?: number[]) => {
      const g = new THREE.PlaneGeometry(x1 - x0, y1 - y0);
      if (uv) g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
      const mesh = new THREE.Mesh(g, m);
      mesh.position.set((x0 + x1) / 2, (y0 + y1) / 2, 0);
      mesh.renderOrder = -50;
      ground.add(mesh);
      return mesh;
    };
    const w = map.width, h = map.height;
    if (map.scale > 1) {
      // big maps: past the edges, the map's own edge carries on (stretched), not grass
      const tw = picture.image.width, th = picture.image.height;
      const m = flat(picture);
      const far = 6000;
      const u0 = 0.5 / tw, u1 = 1 - 0.5 / tw, v0 = 0.5 / th, v1 = 1 - 0.5 / th;
      // PlaneGeometry corners: top-left, top-right, bottom-left, bottom-right
      plane(-far, 0, 0, h, m, [u0, 1, u0, 1, u0, 0, u0, 0]);
      plane(w, 0, w + far, h, m, [u1, 1, u1, 1, u1, 0, u1, 0]);
      plane(-far, h, w + far, h + far, m, [0, v1, 1, v1, 0, v1, 1, v1]);
      plane(-far, -far, w + far, 0, m, [0, v0, 1, v0, 0, v0, 1, v0]);
    } else {
      const grass = (await texture('ui/grass.png')).clone();
      grass.wrapS = grass.wrapT = THREE.RepeatWrapping;
      const span = 12000;
      grass.repeat.set(span / 256, span / 256);
      grass.needsUpdate = true;
      plane(w / 2 - span / 2, h / 2 - span / 2, w / 2 + span / 2, h / 2 + span / 2, flat(grass)).renderOrder = -51;
    }
    plane(0, 0, w, h, flat(picture));
    this.scene.add(ground);
  }

  /** the walls: a kerb strip overhead; a knee-high barrier and turn boards in first person */
  private addWalls(walls: Wall[], signs: Sign[]): void {
    if (!walls.length) return;
    const kerb = barrier();
    // overhead: the barrier's striped top edge, as a thin strip along each wall
    const strip = new THREE.BufferGeometry();
    const pos: number[] = [], uv: number[] = [], idx: number[] = [];
    const quad = (pts: number[][], uvs: number[]) => {
      const base = pos.length / 3;
      for (const p of pts) pos.push(...p);
      uv.push(...uvs);
      idx.push(base, base + 1, base + 2, base + 2, base + 3, base);
    };
    for (const wall of walls) {
      const x0 = Math.min(wall.ax, wall.bx) - 1, x1 = Math.max(wall.ax, wall.bx) + 1;
      const y0 = Math.min(wall.ay, wall.by) - 1, y1 = Math.max(wall.ay, wall.by) + 1;
      const along = Math.abs(wall.bx - wall.ax) > 0;
      // the top 6 of the texture's 64 rows: the kerb blocks
      const uvs = along ? [0, 0.906, 1, 0.906, 1, 1, 0, 1] : [0, 1, 0, 0.906, 1, 0.906, 1, 1];
      quad([[x0, y0, 0.3], [x1, y0, 0.3], [x1, y1, 0.3], [x0, y1, 0.3]], uvs);
    }
    strip.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    strip.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    strip.setIndex(idx);
    const stripMesh = new THREE.Mesh(strip, new THREE.MeshBasicMaterial({ map: kerb, depthWrite: false }));
    stripMesh.renderOrder = -40;
    this.flatOnly.add(stripMesh);

    // first person: the barrier, both faces and its top
    pos.length = uv.length = idx.length = 0;
    const cols: number[] = [];
    const face = (ax: number, ay: number, bx: number, by: number, low: number, high: number, repeat: number, shade: number[]) => {
      quad([[ax, ay, low], [bx, by, low], [bx, by, high], [ax, ay, high]], [0, 0, repeat, 0, repeat, 1, 0, 1]);
      for (let i = 0; i < 4; i++) cols.push(...shade);
    };
    for (const w of walls) {
      const ox = -w.nx * WALL_THICKNESS, oy = -w.ny * WALL_THICKNESS;
      const length = Math.abs(w.bx - w.ax) + Math.abs(w.by - w.ay);
      const shade = [0.86 + w.nx * 0.08, 0.89 + w.ny * 0.06, 0.95];
      face(w.ax, w.ay, w.bx, w.by, 0, WALL_HEIGHT, length / 64, shade);
      face(w.bx + ox, w.by + oy, w.ax + ox, w.ay + oy, 0, WALL_HEIGHT, length / 64, shade);
      quad(
        [[w.ax, w.ay, WALL_HEIGHT], [w.bx, w.by, WALL_HEIGHT], [w.bx + ox, w.by + oy, WALL_HEIGHT], [w.ax + ox, w.ay + oy, WALL_HEIGHT]],
        [0, 1, length / 64, 1, length / 64, 0.93, 0, 0.93],
      );
      for (let i = 0; i < 4; i++) cols.push(1, 1, 1);
    }
    const solid = new THREE.BufferGeometry();
    solid.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    solid.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    solid.setAttribute('color', new THREE.Float32BufferAttribute(cols, 3));
    solid.setIndex(idx);
    this.solidOnly.add(new THREE.Mesh(solid, new THREE.MeshBasicMaterial({ map: kerb, vertexColors: true, side: THREE.DoubleSide, fog: true })));

    // the turn boards: a small board on two posts, its bottom at the barrier's top
    const boards = { left: new THREE.Group(), right: new THREE.Group() };
    const boardMaterial = (right: boolean) => new THREE.MeshBasicMaterial({ map: chevrons(right), side: THREE.DoubleSide, fog: true });
    const materials = { left: boardMaterial(false), right: boardMaterial(true) };
    const post = new THREE.MeshBasicMaterial({ color: new THREE.Color(0.35, 0.36, 0.4), fog: true });
    const half = 20;
    for (const s of signs) {
      const g = new THREE.PlaneGeometry(half * 2, 10.5);
      const board = new THREE.Mesh(g, s.turn_right ? materials.right : materials.left);
      board.position.set(s.x, s.y, WALL_HEIGHT + 1.5 + 5.25);
      // the board's x axis runs to the driver's right, its face toward the driver
      board.quaternion.setFromRotationMatrix(
        new THREE.Matrix4().makeBasis(new THREE.Vector3(s.right_x, s.right_y, 0), new THREE.Vector3(0, 0, 1), new THREE.Vector3(s.right_y, -s.right_x, 0)),
      );
      (s.turn_right ? boards.right : boards.left).add(board);
      for (const side of [-1, 1]) {
        const p = new THREE.Mesh(new THREE.BoxGeometry(1.2, 1.2, WALL_HEIGHT + 1.5), post);
        p.position.set(s.x + side * s.right_x * (half - 3), s.y + side * s.right_y * (half - 3), (WALL_HEIGHT + 1.5) / 2);
        this.solidOnly.add(p);
      }
    }
    this.solidOnly.add(boards.left, boards.right);
  }

  /** the trees, buildings and landmarks, built the first time first person is used */
  private addScenery(): Promise<void> {
    this.scenery ??= (async () => {
      const { mesh, clear } = buildLandmarks(this.map.id);
      if (mesh) this.solidOnly.add(mesh);
      await this.addBuildings(clear).catch((e) => console.warn('scenery', e));
    })();
    return this.scenery;
  }

  private async addBuildings(clear: Area[]): Promise<void> {
    const id = this.map.id;
    const [heights, picture, roof] = await Promise.all([
      pixels(`maps/${id}/heights.png`),
      pixels(`maps/${id}/background.png`),
      texture(`maps/${id}/background.png`),
    ]);
    this.solidOnly.add(buildScenery(this.map, heights, picture, roof, clear));
  }

  setFirstPerson(on: boolean): void {
    this.firstPerson = on;
    this.flatOnly.visible = !on;
    this.solidOnly.visible = on;
    this.scene.fog = on ? this.fog : null;
    if (on) void this.addScenery();
    this.heading = NaN;
    for (const [slot, car] of this.cars) car.setMode(on, slot === this.followed);
  }

  isFirstPerson(): boolean {
    return this.firstPerson;
  }

  /** the cars in the race and their sprites (cars not listed are removed) */
  setCars(cars: { slot: number; sprite: string }[], smooth = true): void {
    const keep = new Set(cars.map((c) => c.slot));
    for (const [slot, view] of this.cars) {
      if (!keep.has(slot)) {
        this.scene.remove(view.root);
        view.dispose();
        this.cars.delete(slot);
      }
    }
    for (const c of cars) {
      const existing = this.cars.get(c.slot);
      if (existing && existing.spriteName === c.sprite) continue;
      if (existing) {
        this.scene.remove(existing.root);
        existing.dispose();
      }
      const view = new CarView(c.sprite, smooth);
      view.setMode(this.firstPerson, c.slot === this.followed);
      this.cars.set(c.slot, view);
      this.scene.add(view.root);
    }
  }

  resize(width: number, height: number): void {
    this.aspect = width / Math.max(1, height);
    this.first.aspect = this.aspect;
    // about 90 degrees across, whatever the shape of the screen
    this.first.fov = Math.max(40, Math.min(75, 2 * (Math.atan(1 / this.aspect) * 180) / Math.PI));
    this.first.updateProjectionMatrix();
  }

  /** puts the overhead camera somewhere directly (title screen, menus) */
  lookAt(x: number, y: number): void {
    this.camX = x;
    this.camY = y;
  }

  /** the overhead view's size in map pixels */
  viewSize(): { w: number; h: number } {
    const w = this.aspect > VIEW_W / VIEW_H ? VIEW_H * this.aspect : VIEW_W;
    return { w: w / this.zoom, h: w / this.aspect / this.zoom };
  }

  update(frame: Frame, delta: number): void {
    this.clock += delta;
    if (frame.follow !== this.followed) {
      const before = this.followed;
      this.followed = frame.follow;
      for (const slot of [before, frame.follow]) {
        if (slot !== null) this.cars.get(slot)?.setMode(this.firstPerson, slot === this.followed);
      }
    }
    let target: CarFrame | undefined;
    for (const c of frame.cars) {
      this.cars.get(c.slot)?.update(c.x, c.y, c.heading, c.look, this.clock);
      if (c.slot === frame.follow) target = c;
    }
    if (target) {
      const k = 1 - Math.exp(-FOLLOW_RATE * delta);
      this.camX += (target.x - this.camX) * k;
      this.camY += (target.y - this.camY) * k;
    }
    // overhead
    const { w, h } = this.viewSize();
    Object.assign(this.overhead, { left: -w / 2, right: w / 2, top: h / 2, bottom: -h / 2 });
    this.overhead.position.set(this.camX, this.camY, 2000);
    this.overhead.updateProjectionMatrix();
    // first person: the view swings round smoothly instead of snapping with every twitch
    if (target) {
      const view = this.cars.get(target.slot);
      if (view) this.eye = eyeHeight(view.spriteName);
      if (Number.isNaN(this.heading)) this.heading = target.heading;
      else {
        let d = target.heading - this.heading;
        d = Math.atan2(Math.sin(d), Math.cos(d));
        this.heading += d * (1 - Math.exp(-18 * delta));
      }
      const fx = Math.cos(this.heading), fy = Math.sin(this.heading);
      const s = frame.shake > 0.05 ? frame.shake : 0;
      const sx = s ? (Math.random() * 2 - 1) * s * 0.15 : 0, sz = s ? (Math.random() * 2 - 1) * s * 0.1 : 0;
      this.first.position.set(target.x + fx * EYE_FORWARD - fy * sx, target.y + fy * EYE_FORWARD + fx * sx, this.eye + sz);
      this.first.lookAt(this.first.position.x + fx, this.first.position.y + fy, this.first.position.z - 0.045);
      this.sky.position.copy(this.first.position);
    }
    this.items.update(frame.items, this.firstPerson, this.first.position, delta);
  }

  camera(): THREE.Camera {
    return this.firstPerson ? this.first : this.overhead;
  }

  /**
   * where a map point is on screen, in 0..1 (x right, y down), or null if it's behind the
   * first-person camera or off screen
   */
  project(x: number, y: number, z: number): { x: number; y: number } | null {
    const cam = this.camera();
    const v = new THREE.Vector3(x, y, z).project(cam);
    if (v.z > 1 || v.x < -1 || v.x > 1 || v.y < -1 || v.y > 1) return null;
    return { x: (v.x + 1) / 2, y: (1 - v.y) / 2 };
  }

  dispose(): void {
    this.scene.traverse((o) => {
      if (o instanceof THREE.Mesh || o instanceof THREE.Points || o instanceof THREE.Sprite) {
        o.geometry.dispose();
        const m = o.material as THREE.Material | THREE.Material[];
        for (const mat of Array.isArray(m) ? m : [m]) mat.dispose();
      }
    });
    for (const c of this.cars.values()) c.dispose();
  }
}
