// An online race: the server runs it and sends a snapshot 30 times a second.
//  - our own car is run ahead of the server: each tick it drives on our controls straight away
//    (RacePredictor, the same physics as the server); each snapshot says which of our inputs
//    the server has applied, and the car is replayed from the server's state with the rest.
//    Small corrections are eased in over a tenth of a second instead of snapping.
//  - everyone else is drawn a little in the past, between two snapshots, with a delay that
//    grows on a jumpy connection (0.1 to 0.45 s)
import { packBytes, pack, type Controls } from '../input';
import { net } from '../net';
import type { CarFrame } from '../render/world';
import { RacePredictor, type Car, type Event, type LobbyPlayer, type MapDef, type ServerMsg, type Snapshot } from '../wasm';
import { carFrame, FixedStep, lerpAngle } from './frames';
import type { RaceSource } from './session';

const REDUNDANCY = 4;
const MIN_DELAY = 0.1;
const MAX_DELAY = 0.45;
/** how quickly a correction to our own car fades (seconds) */
const CORRECTION_TIME = 0.1;

interface Received {
  snapshot: Snapshot;
  /** our clock (seconds) when it arrived */
  at: number;
}

export class OnlineSource implements RaceSource {
  readonly online = true;
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
  private offset = { x: 0, y: 0 };
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
      const before = this.predicted?.pos;
      this.predictor.reconcile(mine.car, ack);
      this.predicted = this.predictor.car() as Car;
      if (before && this.predicted) {
        const dx = before.x + this.offset.x - this.predicted.pos.x, dy = before.y + this.offset.y - this.predicted.pos.y;
        // a big jump (a respawn) snaps; small ones ease in
        if (Math.hypot(dx, dy) < 60) this.offset = { x: dx, y: dy };
        else this.offset = { x: 0, y: 0 };
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
      this.predictor.predict(this.seq, pack(c));
      this.predicted = this.predictor.car() as Car;
    });
    const k = Math.exp(-delta / CORRECTION_TIME);
    this.offset = { x: this.offset.x * k, y: this.offset.y * k };
  }

  private delay(): number {
    return Math.max(MIN_DELAY, Math.min(MAX_DELAY, MIN_DELAY + this.jitter * 3));
  }

  cars(): CarFrame[] {
    const latest = this.latest();
    // everyone else, drawn between the two snapshots around (now - delay)
    const t = (this.now() - (this.clockOffset ?? 0) - this.delay()) * 60;
    let a = this.buffer[0], b = this.buffer[this.buffer.length - 1];
    for (let i = 0; i + 1 < this.buffer.length; i++) {
      if (Number(this.buffer[i + 1].snapshot.tick) >= t) {
        a = this.buffer[i];
        b = this.buffer[i + 1];
        break;
      }
    }
    const ta = Number(a.snapshot.tick), tb = Number(b.snapshot.tick);
    const f = tb > ta ? Math.max(0, Math.min(1, (t - ta) / (tb - ta))) : 1;
    const frames: CarFrame[] = [];
    for (const c of latest.cars) {
      if (c.slot === this.mySlot) {
        const car = this.predicted ?? c.car;
        frames.push({
          ...carFrame({ ...c, car }),
          x: car.pos.x + this.offset.x,
          y: car.pos.y + this.offset.y,
        });
        continue;
      }
      const ca = a.snapshot.cars.find((x) => x.slot === c.slot), cb = b.snapshot.cars.find((x) => x.slot === c.slot);
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
