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

/** how fast rockets and shots fly (as in crates/sim/src/items.rs), to follow one between snapshots */
const ROCKET_SPEED = 720;
const SHOT_SPEED = 620;

/**
 * The items `t` of the way from snapshot `a` to `b`, so they move as smoothly as the cars and in
 * step with them. `cars` are the cars as drawn this frame (shields ride along with them).
 */
export function itemsBetween(a: Snapshot, b: Snapshot, t: number, cars: CarFrame[]): ItemState {
  const seconds = Math.max(1, Number(b.tick) - Number(a.tick)) * TICK;
  const shielded = new Set(b.cars.filter((c) => c.shield).map((c) => c.slot));
  return {
    boxes: t < 0.5 ? a.boxes : b.boxes,
    rockets: flying(a.rockets, b.rockets, t, ROCKET_SPEED * seconds),
    shots: flying(a.shots, b.shots, t, SHOT_SPEED * seconds),
    slicks: b.slicks,
    shields: cars.filter((c) => shielded.has(c.slot)).map((c) => [c.x, c.y]),
  };
}

/**
 * Projectiles `t` of the way from `from` to `to`. Snapshots don't say which is which, so each one
 * in `to` is matched to whichever in `from` is nearest to where it was `step` units back along
 * its heading. One just fired is drawn coming out of the car at the same speed.
 */
function flying<P extends [number, number, number, ...unknown[]]>(from: P[], to: P[], t: number, step: number): P[] {
  const used = new Set<number>();
  return to.map((p) => {
    const bx = p[0] - Math.cos(p[2]) * step, by = p[1] - Math.sin(p[2]) * step;
    let best = -1, bestDistance = 24 + step * 0.5;
    from.forEach((q, i) => {
      const d = Math.hypot(q[0] - bx, q[1] - by);
      if (!used.has(i) && d < bestDistance) {
        best = i;
        bestDistance = d;
      }
    });
    if (best < 0) return [bx + (p[0] - bx) * t, by + (p[1] - by) * t, ...p.slice(2)] as P;
    used.add(best);
    const q = from[best];
    return [q[0] + (p[0] - q[0]) * t, q[1] + (p[1] - q[1]) * t, lerpAngle(q[2], p[2], t), ...p.slice(3)] as P;
  });
}
