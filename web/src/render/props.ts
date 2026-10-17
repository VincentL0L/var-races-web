// Little models for the countryside, built like the cars: solid blocks with their faces shaded
// by which way they point, and a dark outline colour on the edges that matter.
//  - trees: a trunk and a crown of stacked, staggered leaf blocks, each tree a little different
//  - critters: a small animal (body, head, ears, legs, tail, eyes, nose), sometimes on a stump
import { Builder, mix, shade, type Rgb } from './geom';

const OUTLINE: Rgb = [0.13, 0.1, 0.09];

/** a little seeded random number generator for one model */
function rand(seed: number): () => number {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return (s >>> 0) / 4294967296;
  };
}

/**
 * a tree `tall` high and about `size` across, its leaves `leaf` (the colour the map shows from
 * above): a dark trunk, then three or four leaf blocks, widest low down, each nudged off centre
 * and lit a little lighter towards the top
 */
export function tree(b: Builder, x: number, y: number, tall: number, size: number, leaf: Rgb, seed: number): void {
  const r = rand(seed);
  const trunk: Rgb = [0.42, 0.27, 0.16], tw = Math.max(1.4, size * 0.09);
  b.box(x - tw, y - tw, 0, x + tw, y + tw, tall * 0.42, trunk);
  const conifer = r() < 0.3;
  const layers = conifer ? 4 : 3 + Math.floor(r() * 2);
  let z = tall * (conifer ? 0.22 : 0.3);
  for (let k = 0; k < layers; k++) {
    const t = k / Math.max(1, layers - 1);
    // a conifer narrows to a point; a broadleaf stays round, smaller at the top
    const half = size * 0.5 * (conifer ? 1 - t * 0.75 : 1 - t * 0.35) * (0.9 + r() * 0.2);
    const step = ((tall - z) / (layers - k)) * (conifer ? 1.05 : 1.15);
    const ox = (r() - 0.5) * size * 0.12, oy = (r() - 0.5) * size * 0.12;
    const tone = shade(leaf, 0.82 + t * 0.3 + (r() - 0.5) * 0.08);
    b.box(x + ox - half, y + oy - half, z, x + ox + half, y + oy + half, z + step, tone);
    // a darker band under each block, like the outline round the cars' sprites
    b.box(x + ox - half - 0.15, y + oy - half - 0.15, z, x + ox + half + 0.15, y + oy + half + 0.15, z + 0.6, shade(leaf, 0.5));
    z += step * (conifer ? 0.8 : 0.72);
  }
}

/**
 * a critter about `len` long, painted `coat`, facing `turn` (radians, 0 = +x; rounded to the
 * nearest of the four directions, so its blocks line up like the cars'), standing on a
 * stump if asked. Long ears and a puff tail for some, pointed ears and a long tail for others.
 */
export function critter(b: Builder, x: number, y: number, len: number, coat: Rgb, seed: number, turn: number, stump: boolean): void {
  const r = rand(seed);
  let z0 = 0;
  if (stump) {
    const bark: Rgb = [0.45, 0.29, 0.17];
    const s = len * 0.55;
    b.frustum(x, y, 0, len * 0.5, s, s * 0.85, bark);
    b.box(x - s * 0.8, y - s * 0.8, len * 0.5, x + s * 0.8, y + s * 0.8, len * 0.52, [0.85, 0.68, 0.45]); // the cut top
    z0 = len * 0.52;
  }
  const quarter = Math.round(turn / (Math.PI / 2)) * (Math.PI / 2);
  const c = Math.round(Math.cos(quarter)), s = Math.round(Math.sin(quarter));
  // a box in the critter's own frame (u forward, v left), turned into the map
  const part = (u: number, v: number, z: number, lu: number, lv: number, h: number, col: Rgb) => {
    const cx = x + u * c - v * s, cy = y + u * s + v * c;
    const ax = c !== 0;
    const hx = (ax ? lu : lv) / 2, hy = (ax ? lv : lu) / 2;
    b.box(cx - hx, cy - hy, z0 + z, cx + hx, cy + hy, z0 + z + h, col);
  };
  const long = r() < 0.4; // bunny-ish: long ears, a puff tail
  const belly = mix(coat, [1, 1, 1], 0.45), dark = shade(coat, 0.6);
  const L = len, W = len * 0.55, legH = len * 0.22;
  // legs, the body with a pale belly, the head forward and up
  for (const [lu, lv] of [[0.28, 0.3], [0.28, -0.3], [-0.28, 0.3], [-0.28, -0.3]]) part(lu * L, lv * W, 0, L * 0.14, W * 0.22, legH, dark);
  part(0, 0, legH, L * 0.75, W, len * 0.36, coat);
  part(0, 0, legH - 0.01, L * 0.6, W * 0.7, len * 0.08, belly);
  const head = L * 0.5;
  part(L * 0.42, 0, legH + len * 0.22, head * 0.9, W * 0.85, head * 0.8, coat);
  part(L * 0.42 + head * 0.47, 0, legH + len * 0.3, head * 0.12, W * 0.35, head * 0.25, belly); // the snout
  part(L * 0.42 + head * 0.55, 0, legH + len * 0.42, head * 0.08, W * 0.14, head * 0.12, OUTLINE); // nose
  for (const v of [0.22, -0.22]) part(L * 0.42 + head * 0.46, v * W, legH + len * 0.5, head * 0.06, W * 0.12, head * 0.14, OUTLINE); // eyes
  // ears
  for (const v of [0.24, -0.24]) {
    if (long) part(L * 0.32, v * W, legH + len * 0.22 + head * 0.8, head * 0.18, W * 0.16, head * 0.9, coat);
    else part(L * 0.4, v * W, legH + len * 0.22 + head * 0.8, head * 0.22, W * 0.22, head * 0.3, dark);
  }
  // the tail: a white puff, or a long one held out behind
  if (long) part(-L * 0.42, 0, legH + len * 0.2, L * 0.14, W * 0.3, len * 0.18, [0.95, 0.95, 0.93]);
  else part(-L * 0.55, 0, legH + len * 0.28, L * 0.35, W * 0.18, len * 0.1, dark);
}
