// One map in 3D: the ground (the map picture on its grass), the walls and turn boards, the
// scenery, the sky, the cars and the items, seen through either camera:
//  - third person: a gently smoothed chase camera behind the car
//  - first person: from the driver's seat, over the bonnet
// Positions are map coordinates (x right, y up, z up off the ground).
import * as THREE from 'three';
import { CameraRig, RACE_CAMERA, type CameraMode } from './camera';
import { disposeObject } from './dispose';
import { pixels, texture } from '../assets';
import { mapLayout, type MapDef, type Sign, type Wall } from '../wasm';
import { CarView, eyeHeight, type CarLook } from './cars';
import { ItemsView, type ItemState } from './items';
import { buildLandmarks, type Area } from './landmarks';
import { buildScenery } from './scenery';
import { buildSky, skyColors, type SkyColors } from './sky';
import { barrier, chevrons } from './textures';

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
  private readonly rig = new CameraRig(RACE_CAMERA);
  private readonly colors: SkyColors;
  private readonly fog: THREE.Fog;
  private readonly environment = new THREE.Group();
  private readonly sky: THREE.Group;
  private readonly items: ItemsView;
  private readonly ownedTextures: THREE.Texture[] = [];
  private readonly cars = new Map<number, CarView>();
  private mode: CameraMode = 'thirdPerson';
  private ghost: CarView | null = null;
  private disposed = false;
  private clock = 0;
  private followed: number | null = null;

  private constructor(readonly map: MapDef, layout: { walls: Wall[]; signs: Sign[]; boxes: { x: number; y: number }[][] }) {
    this.colors = skyColors(map.id);
    this.fog = new THREE.Fog(new THREE.Color(...this.colors.low), FOG_START, FOG_END);
    this.scene.background = new THREE.Color(...this.colors.low);
    this.sky = buildSky(map.id, this.colors);
    this.scene.add(this.sky, this.environment);
    this.scene.fog = this.fog;
    this.addWalls(layout.walls, layout.signs);
    this.items = new ItemsView(layout.boxes.flat().map((p) => [p.x, p.y]));
    this.scene.add(this.items.root);
  }

  /** builds a map's world (the ground picture is loaded before it's ready) */
  static async create(map: MapDef): Promise<World> {
    const world = new World(map, mapLayout(map.id));
    try {
      await Promise.all([world.addGround(), world.addScenery()]);
      return world;
    } catch (error) {
      world.dispose();
      throw error;
    }
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
      this.ownedTextures.push(grass);
      grass.wrapS = grass.wrapT = THREE.RepeatWrapping;
      const span = 12000;
      grass.repeat.set(span / 256, span / 256);
      grass.needsUpdate = true;
      plane(w / 2 - span / 2, h / 2 - span / 2, w / 2 + span / 2, h / 2 + span / 2, flat(grass)).renderOrder = -51;
    }
    plane(0, 0, w, h, flat(picture));
    this.scene.add(ground);
  }

  /** Knee-high barriers and turn boards, shared by both perspective views. */
  private addWalls(walls: Wall[], signs: Sign[]): void {
    if (!walls.length) return;
    const kerb = barrier();
    const pos: number[] = [], uv: number[] = [], idx: number[] = [];
    const quad = (pts: number[][], uvs: number[]) => {
      const base = pos.length / 3;
      for (const p of pts) pos.push(...p);
      uv.push(...uvs);
      idx.push(base, base + 1, base + 2, base + 2, base + 3, base);
    };
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
    this.environment.add(new THREE.Mesh(solid, new THREE.MeshBasicMaterial({ map: kerb, vertexColors: true, side: THREE.DoubleSide, fog: true })));

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
        this.environment.add(p);
      }
    }
    this.environment.add(boards.left, boards.right);
  }

  /** Scenery is required by both camera modes. */
  private async addScenery(): Promise<void> {
    const { mesh, clear } = buildLandmarks(this.map.id);
    if (mesh) this.environment.add(mesh);
    await this.addBuildings(clear).catch((e) => console.warn('scenery', e));
  }

  private async addBuildings(clear: Area[]): Promise<void> {
    const id = this.map.id;
    const [heights, picture, roof] = await Promise.all([
      pixels(`maps/${id}/heights.png`),
      pixels(`maps/${id}/background.png`),
      texture(`maps/${id}/background.png`),
    ]);
    if (!this.disposed) this.environment.add(buildScenery(this.map, heights, picture, roof, clear));
  }

  setCameraMode(mode: CameraMode): void {
    this.mode = mode;
    this.rig.setMode(mode);
    for (const [slot, car] of this.cars) car.setInside(mode === 'firstPerson' && slot === this.followed);
  }

  cameraMode(): CameraMode { return this.mode; }

  /** The ghost has no participant slot, physics, or camera ownership. */
  showGhost(sprite: string, frame: CarFrame | null): void {
    if (!frame) { if (this.ghost) this.ghost.root.visible = false; return; }
    if (this.ghost?.spriteName !== sprite) {
      if (this.ghost) { this.scene.remove(this.ghost.root); this.ghost.dispose(); }
      this.ghost = new CarView(sprite, 0.32);
      this.scene.add(this.ghost.root);
    }
    this.ghost.root.visible = true;
    this.ghost.update(frame.x, frame.y, frame.heading, frame.look, this.clock);
  }

  /** the cars in the race and their sprites (cars not listed are removed) */
  setCars(cars: { slot: number; sprite: string }[]): void {
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
      const view = new CarView(c.sprite);
      view.setInside(this.mode === 'firstPerson' && c.slot === this.followed);
      this.cars.set(c.slot, view);
      this.scene.add(view.root);
    }
  }

  resize(width: number, height: number): void {
    this.rig.resize(width, height);
  }

  update(frame: Frame, delta: number): void {
    this.clock += delta;
    if (frame.follow !== this.followed) {
      const before = this.followed;
      this.followed = frame.follow;
      this.rig.reset();
      for (const slot of [before, frame.follow]) {
        if (slot !== null) this.cars.get(slot)?.setInside(this.mode === 'firstPerson' && slot === this.followed);
      }
    }
    let target: CarFrame | undefined;
    for (const c of frame.cars) {
      this.cars.get(c.slot)?.update(c.x, c.y, c.heading, c.look, this.clock);
      if (c.slot === frame.follow) target = c;
    }
    const pose = target ? { ...target, z: 0 } : null;
    if (pose) {
      const view = target ? this.cars.get(target.slot) : null;
      this.environment.updateMatrixWorld(true);
      this.rig.update(pose, view ? eyeHeight(view.spriteName) : 6.3, delta, [this.environment], frame.shake);
      this.sky.position.copy(this.rig.camera.position);
    }
    this.items.update(frame.items, this.rig.camera.position, delta);
  }

  camera(): THREE.Camera { return this.rig.camera; }

  /**
   * where a map point is on screen, in 0..1 (x right, y down), or null if it's behind the
   * active perspective camera or off screen
   */
  project(x: number, y: number, z: number): { x: number; y: number } | null {
    const cam = this.camera();
    const v = new THREE.Vector3(x, y, z).project(cam);
    if (v.z < -1 || v.z > 1 || v.x < -1 || v.x > 1 || v.y < -1 || v.y > 1) return null;
    return { x: (v.x + 1) / 2, y: (1 - v.y) / 2 };
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    if (this.ghost) { this.scene.remove(this.ghost.root); this.ghost.dispose(); this.ghost = null; }
    for (const car of this.cars.values()) {
      this.scene.remove(car.root);
      car.dispose();
    }
    this.cars.clear();
    disposeObject(this.scene);
    this.scene.clear();
    for (const texture of this.ownedTextures) texture.dispose();
    this.ownedTextures.length = 0;
  }
}
