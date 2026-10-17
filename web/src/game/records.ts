// Browser-local time trials. Playback is presentation only and never enters the simulation.
import { lerpAngle } from './frames';
import type { CarFrame } from '../render/world';

const REVISION = 1; // bump when course geometry, timing, or car physics changes
const STORE = 'bests';
export interface PersonalBest {
  key: string;
  time: number;
  car: number;
  /** Interleaved simulation seconds, x, y, heading. */
  samples: Float32Array;
}
export interface TrialResult {
  time: number;
  previous: number | null;
  best: number;
  improved: boolean;
  saved: boolean;
}
const memory = new Map<string, PersonalBest>();
let database: Promise<IDBDatabase> | undefined;
export function recordKey(map: string, laps: number, car: number): string {
  return `${REVISION}:${map}:${laps}:${Math.floor((car - 1) / 3)}`;
}
function db(): Promise<IDBDatabase> {
  return database ??= new Promise((resolve, reject) => {
    const request = indexedDB.open('var-races-records', 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: 'key' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Records are unavailable'));
  });
}
function valid(r: PersonalBest | undefined, key: string): r is PersonalBest {
  return !!r && r.key === key && Number.isFinite(r.time) && r.time > 0 && r.car >= 1 && r.car <= 15
    && Number.isInteger(r.car) && r.samples instanceof Float32Array && r.samples.length % 4 === 0
    && r.samples.length <= 96008 && r.samples.every((v, i, a) => Number.isFinite(v)
      && (i % 4 !== 0 || (v >= 0 && v <= r.time + 0.001 && (i === 0 || v > a[i - 4]))));
}
export async function readBest(key: string): Promise<PersonalBest | null> {
  try {
    const database = await db();
    const record = await new Promise<PersonalBest | undefined>((resolve, reject) => {
      const request = database.transaction(STORE).objectStore(STORE).get(key);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    if (valid(record, key) && (!memory.has(key) || record.time < memory.get(key)!.time)) memory.set(key, record);
  } catch { /* blocked storage still supports records for this session */ }
  return memory.get(key) ?? null;
}
export async function saveBest(record: PersonalBest): Promise<TrialResult> {
  let previous = (await readBest(record.key))?.time ?? null;
  let improved = previous === null || record.time < previous;
  let saved = true;
  try {
    const database = await db();
    await new Promise<void>((resolve, reject) => {
      const tx = database.transaction(STORE, 'readwrite');
      const store = tx.objectStore(STORE);
      const get = store.get(record.key);
      get.onsuccess = () => {
        const current = get.result as PersonalBest | undefined;
        if (valid(current, record.key) && (previous === null || current.time < previous)) previous = current.time;
        improved = previous === null || record.time < previous;
        if (improved) store.put(record);
      };
      tx.oncomplete = () => resolve();
      tx.onerror = tx.onabort = () => reject(tx.error);
    });
  } catch { saved = false; }
  if (improved) memory.set(record.key, record);
  return { time: record.time, previous, best: Math.min(previous ?? Infinity, record.time), improved, saved };
}

/** Fixed-tick recording, bounded to twenty minutes. */
export class TrialRecording {
  private samples: number[] = [];
  private next = 0;
  private expired = false;
  sample(time: number, x: number, y: number, heading: number, final = false): void {
    if (time > 1200) { this.expired = true; this.samples = []; }
    if (this.expired || (!final && time + 0.0001 < this.next)) return;
    if (this.samples.length && time <= this.samples[this.samples.length - 4]) return;
    this.samples.push(time, x, y, heading);
    this.next = time + 0.05;
  }
  finish(key: string, car: number, time: number): PersonalBest {
    return { key, car, time, samples: new Float32Array(this.samples) };
  }
}
export function ghostFrame(best: PersonalBest, time: number): CarFrame | null {
  const s = best.samples;
  if (s.length < 8 || time < 0 || time > best.time) return null;
  let lo = 0, hi = s.length / 4 - 1;
  while (lo + 1 < hi) {
    const mid = (lo + hi) >>> 1;
    if (s[mid * 4] <= time) lo = mid; else hi = mid;
  }
  const a = lo * 4, b = hi * 4;
  const t = Math.max(0, Math.min(1, (time - s[a]) / Math.max(0.00001, s[b] - s[a])));
  return { slot: -1, x: s[a + 1] + (s[b + 1] - s[a + 1]) * t,
    y: s[a + 2] + (s[b + 2] - s[a + 2]) * t, heading: lerpAngle(s[a + 3], s[b + 3], t),
    look: { frozen: false, burning: 0 } };
}
