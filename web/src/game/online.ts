// An online race: the server runs it and sends a snapshot 30 times a second.
//  - our own car is run ahead of the server: each tick it drives on our controls straight away
//    (RacePredictor, the same physics as the server); each snapshot says which of our inputs
//    the server has applied, and the car is replayed from the server's state with the rest.
//    It's drawn between its last two ticks, so it moves evenly on any screen (a 120 Hz display
//    draws two frames a tick), and corrections (a bump the prediction didn't know about) are
//    eased in instead of snapping.
//  - everyone else, and the items, are drawn a little in the past, between two snapshots, with
//    a delay that grows on a jumpy connection (0.1 to 0.45 s). That moment runs on its own clock,
//    which only ever speeds up or slows down a little to keep pace, so they never lurch.
import { packBytes, pack, type Controls } from '../input';
import { net } from '../net';
import type { ItemState } from '../render/items';
import type { CarFrame } from '../render/world';
import { RacePredictor, type Car, type Event, type LobbyPlayer, type MapDef, type ServerMsg, type Snapshot } from '../wasm';
import { carFrame, FixedStep, itemsBetween, lerpAngle } from './frames';
import type { RaceSource } from './session';

const REDUNDANCY = 4;
const MIN_DELAY = 0.1;
const MAX_DELAY = 0.45;
/** how quickly a correction to our own car fades (seconds) */
const CORRECTION_TIME = 0.15;
/** a correction bigger than this (a respawn, a spin) is shown at once */
const SNAP_DISTANCE = 60;
const SNAP_TURN = 1.2;
/** how much faster or slower than real time the others' clock may run to keep pace */
const CLOCK_SLEW = 0.1;
/** further than this (in ticks) from where it should be, the others' clock jumps */
const CLOCK_JUMP = 15;
/** how far past the newest snapshot the others may be carried on while one is late (ticks) */
const MAX_EXTRAPOLATE = 4;

interface Received {
  snapshot: Snapshot;
  /** our clock (seconds) when it arrived */
  at: number;
}

export class OnlineSource implements RaceSource {
  readonly online = true;
  readonly trial = false;
  readonly canRestart = false;
  private readonly predictor: RacePredictor;
  private readonly step = new FixedStep();
  private readonly buffer: Received[] = [];
  private readonly sent: [number, number][] = [];
  private readonly stop: () => void;
  private players: LobbyPlayer[];
  private events: Event[] = [];
  private seq = 0;
  private predicted: Car | null = null;
  /** our car at the tick before `predicted`, to draw between them */
  private previous: { x: number; y: number; heading: number } | null = null;
  /** what's left of the last correction to our car: drawn = predicted + offset */
  private offset = { x: 0, y: 0, heading: 0 };
  /** the server tick everyone else is drawn at (fractional) */
  private playback: number | null = null;
  /** our clock minus the server's, smoothed, and how much arrivals wobble */
  private clockOffset: number | null = null;
  private jitter = 0;

  constructor(readonly map: MapDef, readonly mySlot: number, players: LobbyPlayer[], first: ServerMsg & { Race: unknown }) {
    this.players = players;
    this.predictor = new RacePredictor(map.id);
    this.receive(first);
    this.stop = net.on((msg) => this.receive(msg));
  }

  private now(): number {
    return performance.now() / 1000;
  }

  private receive(msg: ServerMsg): void {
    if (typeof msg !== 'object') return;
    if ('Lobby' in msg) {
      this.players = msg.Lobby.players;
      return;
    }
    if (!('Race' in msg)) return;
    const { snapshot, events, ack } = msg.Race;
    const at = this.now();
    // when the server says this happened, on our clock
    const serverTime = Number(snapshot.tick) / 60;
    const offset = at - serverTime;
    if (this.clockOffset === null) this.clockOffset = offset;
    else {
      // arrivals only ever come late, so lean toward the earliest
      const err = offset - this.clockOffset;
      this.clockOffset += err < 0 ? err * 0.5 : err * 0.02;
      this.jitter += (Math.abs(err) - this.jitter) * 0.1;
    }
    this.buffer.push({ snapshot, at });
    while (this.buffer.length > 40) this.buffer.shift();
    this.events.push(...events);
    const mine = snapshot.cars.find((c) => c.slot === this.mySlot);
    if (mine) {
      const before = this.predicted;
      this.predictor.reconcile(mine.car, ack);
      this.predicted = this.predictor.car() as Car;
      if (before && this.predicted && this.previous) {
        // move the tick before by the same correction, then keep the car where it's drawn
        const dx = this.predicted.pos.x - before.pos.x, dy = this.predicted.pos.y - before.pos.y;
        const turn = angleDiff(this.predicted.heading, before.heading);
        this.previous = { x: this.previous.x + dx, y: this.previous.y + dy, heading: this.previous.heading + turn };
        const offset = { x: this.offset.x - dx, y: this.offset.y - dy, heading: this.offset.heading - turn };
        // a big jump (a respawn, a spin) snaps; small ones ease in
        const small = Math.hypot(offset.x, offset.y) < SNAP_DISTANCE && Math.abs(offset.heading) < SNAP_TURN;
        this.offset = small ? offset : { x: 0, y: 0, heading: 0 };
      } else {
        this.previous = null;
        this.offset = { x: 0, y: 0, heading: 0 };
      }
    }
  }

  name(slot: number): string {
    const p = this.players.find((p) => p.slot === slot);
    return p ? p.name : `${this.map.cpu_name} ${slot + 1}`;
  }

  carNumber(slot: number): number {
    return this.players.find((p) => p.slot === slot)?.car ?? 0;
  }

  tick(delta: number, controls: Controls, paused: boolean): void {
    // the race goes on while paused; we just let go of everything
    const c = paused ? { ...controls, gas: false, brake: false, boost: false, steer: 0, item: false } : controls;
    this.step.run(delta, () => {
      this.seq++;
      this.sent.push(packBytes(c));
      while (this.sent.length > REDUNDANCY) this.sent.shift();
      net.send({ Inputs: { last: this.seq, inputs: this.sent } });
      const before = this.predicted;
      this.predictor.predict(this.seq, pack(c));
      this.predicted = this.predictor.car() as Car;
      this.previous = before ? { x: before.pos.x, y: before.pos.y, heading: before.heading } : null;
    });
    const k = Math.exp(-delta / CORRECTION_TIME);
    this.offset = { x: this.offset.x * k, y: this.offset.y * k, heading: this.offset.heading * k };
    this.advancePlayback(delta);
  }

  /** moves the others' clock on by `delta`, nudged toward (now - delay) on the server's clock */
  private advancePlayback(delta: number): void {
    const target = (this.now() - (this.clockOffset ?? 0) - this.delay()) * 60;
    if (this.playback === null || Math.abs(target - this.playback) > CLOCK_JUMP) {
      this.playback = target;
      return;
    }
    const pace = 1 + Math.max(-CLOCK_SLEW, Math.min(CLOCK_SLEW, (target - this.playback) * CLOCK_SLEW));
    this.playback += delta * 60 * pace;
  }

  /** the two snapshots either side of the others' clock, and how far between them it is */
  private bracket(): { a: Snapshot; b: Snapshot; f: number } {
    const t = this.playback ?? Number(this.latest().tick);
    let a = this.buffer[0], b = this.buffer[this.buffer.length - 1];
    if (this.buffer.length >= 2 && Number(b.snapshot.tick) < t) a = this.buffer[this.buffer.length - 2];
    for (let i = 0; i + 1 < this.buffer.length; i++) {
      if (Number(this.buffer[i + 1].snapshot.tick) >= t) {
        a = this.buffer[i];
        b = this.buffer[i + 1];
        break;
      }
    }
    const ta = Number(a.snapshot.tick), tb = Number(b.snapshot.tick);
    // past the newest snapshot (one is late): carry on a little rather than stop dead
    const f = tb > ta ? Math.max(0, Math.min(1 + MAX_EXTRAPOLATE / (tb - ta), (t - ta) / (tb - ta))) : 1;
    return { a: a.snapshot, b: b.snapshot, f };
  }

  private delay(): number {
    return Math.max(MIN_DELAY, Math.min(MAX_DELAY, MIN_DELAY + this.jitter * 3));
  }

  cars(): CarFrame[] {
    const latest = this.latest();
    // everyone else, drawn between the two snapshots around the others' clock
    const { a, b, f } = this.bracket();
    const frames: CarFrame[] = [];
    for (const c of latest.cars) {
      if (c.slot === this.mySlot) {
        const car = this.predicted ?? c.car;
        const p = this.predicted && this.previous ? this.previous : { x: car.pos.x, y: car.pos.y, heading: car.heading };
        const t = this.step.alpha();
        frames.push({
          ...carFrame({ ...c, car }),
          x: p.x + (car.pos.x - p.x) * t + this.offset.x,
          y: p.y + (car.pos.y - p.y) * t + this.offset.y,
          heading: lerpAngle(p.heading, car.heading, t) + this.offset.heading,
        });
        continue;
      }
      const ca = a.cars.find((x) => x.slot === c.slot), cb = b.cars.find((x) => x.slot === c.slot);
      if (!ca || !cb) {
        frames.push(carFrame(c));
        continue;
      }
      frames.push({
        ...carFrame(cb),
        x: ca.car.pos.x + (cb.car.pos.x - ca.car.pos.x) * f,
        y: ca.car.pos.y + (cb.car.pos.y - ca.car.pos.y) * f,
        heading: lerpAngle(ca.car.heading, cb.car.heading, f),
      });
    }
    return frames;
  }

  items(cars: CarFrame[]): ItemState {
    const { a, b, f } = this.bracket();
    return itemsBetween(a, b, f, cars);
  }

  latest(): Snapshot {
    return this.buffer[this.buffer.length - 1].snapshot;
  }

  myCar(): Car | null {
    return this.predicted;
  }

  takeEvents(): Event[] {
    const e = this.events;
    this.events = [];
    return e;
  }

  /** leaving: out of the room (the connection stays for the menus) */
  dispose(): void {
    this.stop();
    this.predictor.free();
    net.send('Leave');
    net.inRoom = false;
  }

  /** after the race: stop drawing but stay in the room (for the results and a rematch) */
  detach(): void {
    this.stop();
    this.predictor.free();
  }
}

function angleDiff(a: number, b: number): number {
  return Math.atan2(Math.sin(a - b), Math.cos(a - b));
}
