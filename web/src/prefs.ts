// Settings remembered in this browser. Storage can be unavailable (private windows, blocked
// site data), so every read falls back to a default and every write may silently fail.

const KEY = 'var-races';

interface Prefs {
  /** the garage car number (1..15) */
  car: number;
  /** drive in first person (true) or from overhead */
  firstPerson: boolean;
  muted: boolean;
}

const defaults: Prefs = { car: 4, firstPerson: false, muted: false };
let cache: Prefs | null = null;

function load(): Prefs {
  if (cache) return cache;
  try {
    cache = { ...defaults, ...JSON.parse(localStorage.getItem(KEY) ?? '{}') };
  } catch {
    cache = { ...defaults };
  }
  return cache!;
}

export function pref<K extends keyof Prefs>(key: K): Prefs[K] {
  return load()[key];
}

export function setPref<K extends keyof Prefs>(key: K, value: Prefs[K]): void {
  load()[key] = value;
  try {
    localStorage.setItem(KEY, JSON.stringify(cache));
  } catch {
    // not remembered this time
  }
}
