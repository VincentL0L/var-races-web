// What moves behind the menus:
//  - the title race: the classic track up close in crisp pixel art, five cars racing round it
//    forever, the camera following the action (like an arcade game's attract mode)
//  - the drifting map: a map seen from above, drifting steadily and bouncing off its edges
//    (like the old DVD logo), behind frosted menus
import type { Stage } from '../app';
import { spriteFor } from '../render/cars';
import { World } from '../render/world';
import { LocalRace, maps, type MapDef, type Snapshot } from '../wasm';
import { FixedStep, NO_ITEMS, framesOf } from './frames';

/** how quickly the title camera catches up with the car it follows */
const FOLLOW = 1.6;

export class TitleStage implements Stage {
  private readonly race = LocalRace.demo('classic', (Math.random() * 2 ** 31) >>> 0, 5);
  private readonly step = new FixedStep();
  private prev: Snapshot;
  private now: Snapshot;
  private camX = 420;
  private camY = 330;

  private constructor(private readonly w: World) {
    this.prev = this.now = this.race.snapshot();
    // zoomed in a lot more than in a race, so the pixel art reads big and chunky
    w.zoom = 675 / 470;
    w.setCars(this.now.cars.map((c) => ({ slot: c.slot, sprite: spriteFor(c.slot, 0, undefined, true) })), false);
  }

  static async create(): Promise<TitleStage> {
    return new TitleStage(await World.create(mapById('classic')));
  }

  frame(delta: number): void {
    this.step.run(delta, () => {
      this.race.step(0, 0);
      this.prev = this.now;
      this.now = this.race.snapshot();
    });
    const cars = framesOf(this.prev, this.now, this.step.alpha());
    const lead = cars.find((c) => c.slot === 0)!;
    const k = 1 - Math.exp(-FOLLOW * delta);
    this.camX += (lead.x - this.camX) * k;
    this.camY += (lead.y - this.camY) * k;
    const { w, h } = this.w.viewSize();
    const map = this.w.map;
    this.w.lookAt(clamp(this.camX, w / 2, map.width - w / 2), clamp(this.camY, h / 2, map.height - h / 2));
    this.w.update({ cars, items: NO_ITEMS, follow: null, shake: 0 }, delta);
  }

  view(): World {
    return this.w;
  }

  dispose(): void {
    this.race.free();
    this.w.dispose();
  }
}

const SPEED_X = 70;
const SPEED_Y = 45;
// shared by every menu that uses it, so moving between them carries on the same drift
let drift = { x: 1000, y: 500, dx: (Math.random() < 0.5 ? -1 : 1) * SPEED_X, dy: (Math.random() < 0.5 ? -1 : 1) * SPEED_Y };

export class DriftStage implements Stage {
  private constructor(private readonly w: World) {}

  static async create(mapId = 'classic'): Promise<DriftStage> {
    return new DriftStage(await World.create(mapById(mapId)));
  }

  get mapId(): string {
    return this.w.map.id;
  }

  frame(delta: number): void {
    const { w, h } = this.w.viewSize();
    const map = this.w.map;
    const halfW = Math.min(w / 2, map.width / 2), halfH = Math.min(h / 2, map.height / 2);
    drift.x += drift.dx * delta;
    drift.y += drift.dy * delta;
    if (drift.x < halfW) drift = { ...drift, x: halfW, dx: Math.abs(drift.dx) };
    if (drift.x > map.width - halfW) drift = { ...drift, x: map.width - halfW, dx: -Math.abs(drift.dx) };
    if (drift.y < halfH) drift = { ...drift, y: halfH, dy: Math.abs(drift.dy) };
    if (drift.y > map.height - halfH) drift = { ...drift, y: map.height - halfH, dy: -Math.abs(drift.dy) };
    this.w.lookAt(drift.x, drift.y);
    this.w.update({ cars: [], items: NO_ITEMS, follow: null, shake: 0 }, delta);
  }

  view(): World {
    return this.w;
  }

  dispose(): void {
    this.w.dispose();
  }
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

let mapList: MapDef[] | null = null;

/** every map, in picker order */
export function allMaps(): MapDef[] {
  mapList ??= maps() as MapDef[];
  return mapList;
}

export function mapById(id: string): MapDef {
  return allMaps().find((m) => m.id === id) ?? allMaps()[0];
}
