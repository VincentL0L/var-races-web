// Turning snapshots of a race into what's drawn: cars between two ticks (so motion is smooth
// at any frame rate), and the items.
import type { CarFrame } from '../render/world';
import type { ItemState } from '../render/items';
import type { CarSnapshot, Snapshot } from '../wasm';

export const NO_ITEMS: ItemState = { boxes: [], rockets: [], shots: [], slicks: [], shields: [] };

export const TICK = 1 / 60;

/** runs a fixed 60 Hz simulation off a variable frame rate */
export class FixedStep {
  private acc = 0;

  run(delta: number, step: () => void): void {
    this.acc += delta;
    let steps = 0;
    while (this.acc >= TICK && steps < 8) {
      step();
      this.acc -= TICK;
      steps++;
    }
    if (steps === 8) this.acc = 0;
  }

  /** how far between the last two ticks this frame is, 0..1 */
  alpha(): number {
    return this.acc / TICK;
  }
}

export function lerpAngle(a: number, b: number, t: number): number {
  const d = Math.atan2(Math.sin(b - a), Math.cos(b - a));
  return a + d * t;
}

export function carFrame(c: CarSnapshot, from?: CarSnapshot, t = 1): CarFrame {
  const p = from ?? c;
  return {
    slot: c.slot,
    x: p.car.pos.x + (c.car.pos.x - p.car.pos.x) * t,
    y: p.car.pos.y + (c.car.pos.y - p.car.pos.y) * t,
    heading: lerpAngle(p.car.heading, c.car.heading, t),
    look: { frozen: c.car.freeze > 0, burning: Math.max(0, c.car.burn) },
  };
}

/** every car, `t` of the way from one snapshot to the next */
export function framesOf(prev: Snapshot, now: Snapshot, t: number): CarFrame[] {
  return now.cars.map((c) => carFrame(c, prev.cars.find((p) => p.slot === c.slot), t));
}

export function itemsOf(s: Snapshot): ItemState {
  return {
    boxes: s.boxes,
    rockets: s.rockets,
    shots: s.shots,
    slicks: s.slicks,
    shields: s.cars.filter((c) => c.shield).map((c) => [c.car.pos.x, c.car.pos.y]),
  };
}
