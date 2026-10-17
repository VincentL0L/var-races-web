// The player's controls: keyboard, or the touch controls (game/touch.ts) on phones.
// Packed into two bytes for the simulation and the server (see sim::car::Input::pack).

export interface Controls {
  gas: boolean;
  brake: boolean;
  boost: boolean;
  /** -1 full right .. 1 full left */
  steer: number;
  item: boolean;
  /** touch: an automatic gearbox */
  automatic: boolean;
  /** touch: the joystick pulled back, so gas reverses */
  backwards: boolean;
}

export const NO_CONTROLS: Controls = {
  gas: false, brake: false, boost: false, steer: 0, item: false, automatic: false, backwards: false,
};

export function pack(c: Controls): number {
  const flags = +c.gas | (+c.brake << 1) | (+c.boost << 2) | (+c.item << 3) | (+c.automatic << 4) | (+c.backwards << 5);
  const steer = Math.round(Math.max(-1, Math.min(1, c.steer)) * 127) & 0xff;
  return flags | (steer << 8);
}

/** as a pair of bytes, for an Inputs message */
export function packBytes(c: Controls): [number, number] {
  const p = pack(c);
  return [p & 0xff, p >> 8];
}

const held = new Set<string>();
const pressListeners = new Set<(code: string) => void>();

addEventListener('keydown', (e) => {
  if (e.target instanceof HTMLInputElement) return;
  if (!e.repeat) pressListeners.forEach((l) => l(e.code));
  held.add(e.code);
  if (e.code.startsWith('Arrow') || e.code === 'Space') e.preventDefault();
});
addEventListener('keyup', (e) => held.delete(e.code));
addEventListener('blur', () => held.clear());

export function down(...codes: string[]): boolean {
  return codes.some((c) => held.has(c));
}

/** calls back on each key press (not repeats); returns a function that stops it */
export function onKey(listener: (code: string) => void): () => void {
  pressListeners.add(listener);
  return () => pressListeners.delete(listener);
}

/** race controls from the keyboard: WASD or arrows, Space boost, E item */
export function raceKeys(): Controls {
  return {
    gas: down('KeyW', 'ArrowUp'),
    brake: down('KeyS', 'ArrowDown'),
    boost: down('Space'),
    steer: (down('KeyA', 'ArrowLeft') ? 1 : 0) - (down('KeyD', 'ArrowRight') ? 1 : 0),
    item: down('KeyE'),
    automatic: false,
    backwards: false,
  };
}

/** true on phones and tablets: show the touch controls */
export const touchScreen = matchMedia('(pointer: coarse)').matches || navigator.maxTouchPoints > 0 && !matchMedia('(pointer: fine)').matches;
