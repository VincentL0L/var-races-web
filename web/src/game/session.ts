// Where a race comes from. Single player runs the race right here (the same simulation the
// server runs); online, the server runs it and we draw its snapshots, our own car run ahead.
import type { Controls } from '../input';
import { pack } from '../input';
import type { CarFrame } from '../render/world';
import type { Car, Event, MapDef, Snapshot } from '../wasm';
import { LocalRace } from '../wasm';
import { FixedStep, framesOf } from './frames';

export interface RaceSource {
  readonly map: MapDef;
  readonly online: boolean;
  readonly mySlot: number;
  name(slot: number): string;
  /** the garage car number (0 for CPUs) */
  carNumber(slot: number): number;
  /** moves the race on; `controls` drive our car */
  tick(delta: number, controls: Controls, paused: boolean): void;
  /** every car where it should be drawn this frame */
  cars(): CarFrame[];
  /** the newest state of the race */
  latest(): Snapshot;
  /** our own car, as we see it (predicted online) */
  myCar(): Car | null;
  takeEvents(): Event[];
  /** single player only: the same race again from the lobby */
  readonly canRestart: boolean;
  /** stops following the race (staying in the room) */
  detach?(): void;
  /** stops following the race, and online leaves the room */
  dispose(): void;
}

/** single player: the race runs in the browser, frozen while paused */
export class LocalSource implements RaceSource {
  readonly online = false;
  readonly canRestart = true;
  readonly mySlot: number;
  private readonly race: LocalRace;
  private readonly step = new FixedStep();
  private readonly names: (string | undefined)[];
  private prev: Snapshot;
  private now: Snapshot;
  private events: Event[] = [];

  constructor(readonly map: MapDef, private readonly car: number, laps: number, difficulty: number) {
    this.race = new LocalRace(map.id, (Math.random() * 2 ** 31) >>> 0);
    this.mySlot = this.race.addPlayer('Player 1', car)!;
    this.race.setSettings(laps, difficulty);
    this.race.start();
    this.names = this.race.names() as (string | undefined)[];
    this.prev = this.now = this.race.snapshot();
    this.events.push(...(this.race.takeEvents() as Event[]));
  }

  name(slot: number): string {
    return this.names[slot] ?? '';
  }

  carNumber(slot: number): number {
    return slot === this.mySlot ? this.car : 0;
  }

  tick(delta: number, controls: Controls, paused: boolean): void {
    if (paused) return;
    const packed = pack(controls);
    this.step.run(delta, () => {
      this.race.step(this.mySlot, packed);
      this.prev = this.now;
      this.now = this.race.snapshot();
      this.events.push(...(this.race.takeEvents() as Event[]));
    });
  }

  cars(): CarFrame[] {
    return framesOf(this.prev, this.now, this.step.alpha());
  }

  latest(): Snapshot {
    return this.now;
  }

  myCar(): Car | null {
    return this.now.cars.find((c) => c.slot === this.mySlot)?.car ?? null;
  }

  takeEvents(): Event[] {
    const e = this.events;
    this.events = [];
    return e;
  }

  dispose(): void {
    this.race.free();
  }
}
