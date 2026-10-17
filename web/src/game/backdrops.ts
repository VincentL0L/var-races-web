// What moves behind the menus:
//  - the title race: a third-person chase of an endless CPU race
// The same running stage stays behind every menu.
import type { Stage } from '../app';
import { pref } from '../prefs';
import { TITLE_CAMERA } from '../render/camera';
import { spriteFor } from '../render/cars';
import { World } from '../render/world';
import { LocalRace, maps, type MapDef, type Snapshot } from '../wasm';
import { FixedStep, NO_ITEMS, framesOf } from './frames';

export class TitleStage implements Stage {
  private readonly race = LocalRace.demo('classic', (Math.random() * 2 ** 31) >>> 0, 5);
  private readonly step = new FixedStep();
  private car = -1;
  private prev: Snapshot;
  private now: Snapshot;

  private constructor(private readonly w: World) {
    this.prev = this.now = this.race.snapshot();
    this.syncCar();
  }

  static async create(): Promise<TitleStage> {
    return new TitleStage(await World.create(mapById('classic'), TITLE_CAMERA));
  }

  private syncCar(): void {
    if (this.car === pref('car')) return;
    this.car = pref('car');
    this.w.setCars(this.now.cars.map((c) => ({ slot: c.slot, sprite: spriteFor(c.slot, c.slot === 0 ? this.car : 0, undefined, true) })));
  }

  frame(delta: number): void {
    this.syncCar();
    this.step.run(delta, () => {
      this.race.step(0, 0);
      this.prev = this.now;
      this.now = this.race.snapshot();
    });
    const cars = framesOf(this.prev, this.now, this.step.alpha());
    this.w.update({ cars, items: NO_ITEMS, follow: 0, shake: 0 }, delta);
  }

  view(): World {
    return this.w;
  }

  dispose(): void {
    this.race.free();
    this.w.dispose();
  }
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
