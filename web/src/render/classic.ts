// The Classic map's countryside, stood up for the 3D views: everything its picture paints on the
// grass besides the trees.
//  - the two log cabins in the forest, the rock piles, the two tall rock spires and the fallen
//    logs, built as chunky models
//  - the animals (and their tree stumps), as little block models like the cars, each in the
//    colours the picture paints it
// Picture pixels are map units here (the Classic map is its picture at full size), y flipped.
import * as THREE from 'three';
import { CRITTERS, ROCKS } from './classicProps';
import { Builder, rgb, shade, vertexColors, type Rgb } from './geom';
import { critter } from './props';
import type { Area } from './landmarks';

const H = 1080;

let seed = 7;
const rnd = () => ((seed = (Math.imul(seed, 1103515245) + 12345) >>> 0) / 4294967296);

export function buildClassic(picture: ImageData): { group: THREE.Group; clear: Area[] } {
  const group = new THREE.Group();
  const b = new Builder(), c = new Builder();
  const clear: Area[] = [];
  seed = 7;

  // the rock piles: a few grey boulders heaped together, lit on top
  for (const [px, py, w, h] of ROCKS) {
    const x0 = px, x1 = px + w, y1 = H - py, y0 = H - py - h;
    clear.push([x0 - 4, y0 - 4, x1 + 4, y1 + 4]);
    const count = 3 + Math.floor(rnd() * 3);
    for (let k = 0; k < count; k++) {
      const r = Math.min(w, h) * (0.22 + rnd() * 0.16);
      const cx = x0 + r + rnd() * Math.max(1, w - 2 * r), cy = y0 + r + rnd() * Math.max(1, h - 2 * r);
      boulder(b, cx, cy, r, r * (0.9 + rnd() * 0.7), [0.62 + rnd() * 0.1, 0.6 + rnd() * 0.08, 0.55 + rnd() * 0.06]);
    }
  }
  // the two tall sandstone spires among the trees
  for (const [px, py] of [[1232, 200], [1622, 385]]) {
    const x = px, y = H - py;
    clear.push([x - 22, y - 22, x + 22, y + 22]);
    const stone = rgb(0xc9ad7a);
    b.frustum(x, y, 0, 20, 14, 10, stone);
    b.frustum(x + 2, y - 1, 20, 42, 10, 7, shade(stone, 0.92));
    b.frustum(x + 1, y, 42, 58, 7, 3.5, shade(stone, 1.05));
    b.frustum(x - 9, y + 6, 0, 14, 7, 4, shade(stone, 0.85));
  }
  // the log cabins
  cabin(b, 626, H - 252, 50, 34, 16);
  cabin(b, 660, H - 296, 54, 36, 18);
  clear.push([590, H - 330, 700, H - 215]);
  // fallen logs
  for (const [px, py, len, turn] of [[40, 728, 60, -15], [620, 742, 70, 18], [748, 1040, 70, -12], [1412, 988, 64, 10]]) log(b, px, H - py, len, turn);
  if (!b.empty) group.add(b.mesh(vertexColors()));

  // the animals: little block models in their own colours, sat on a stump where the picture
  // has one under them
  for (const [px, py, w, h] of CRITTERS) {
    const { coat, stump } = look(picture, px, py, w, h);
    const x = px + w / 2, y = H - py - h / 2;
    critter(c, x, y, Math.max(w, h) * 0.55, coat, px * 7919 + py * 104729, rnd() * Math.PI * 2, stump);
    clear.push([px, H - py - h, px + w, H - py]);
  }
  if (!c.empty) group.add(c.mesh(vertexColors()));
  return { group, clear };
}

/** a lumpy boulder: a squat block, a narrower one on top, a lit cap */
function boulder(b: Builder, x: number, y: number, r: number, tall: number, c: Rgb): void {
  b.frustum(x, y, 0, tall * 0.55, r, r * 0.85, shade(c, 0.85));
  b.frustum(x + r * 0.08, y - r * 0.05, tall * 0.55, tall * 0.9, r * 0.85, r * 0.5, c);
  b.frustum(x + r * 0.1, y - r * 0.08, tall * 0.9, tall, r * 0.5, r * 0.25, shade(c, 1.15));
}

/** a log cabin facing south: log walls, a door and windows, a gabled roof and a stone chimney */
function cabin(b: Builder, x: number, y: number, w: number, d: number, wall: number): void {
  const x0 = x - w / 2, x1 = x + w / 2, y0 = y - d / 2, y1 = y + d / 2;
  const logs = rgb(0xa8683a), dark = rgb(0x6e3f22), roof = rgb(0x5a3826);
  // the walls, a log at a time, alternating shades
  for (let z = 0, k = 0; z < wall; z += 2.4, k++) b.box(x0, y0, z, x1, y1, Math.min(wall, z + 2.4), k % 2 ? logs : shade(logs, 0.88));
  // corner posts
  for (const [cx, cy] of [[x0, y0], [x1, y0], [x0, y1], [x1, y1]]) b.box(cx - 1.4, cy - 1.4, 0, cx + 1.4, cy + 1.4, wall + 0.5, dark);
  // the door and two windows on the front (south, y0)
  b.box(x - 4, y0 - 0.4, 0, x + 4, y0, 11, dark);
  b.box(x - 1.5, y0 - 0.6, 5, x - 0.5, y0 - 0.4, 6, rgb(0xf2c84b));
  for (const wx of [x0 + w * 0.2, x1 - w * 0.2]) {
    b.box(wx - 4, y0 - 0.4, 6, wx + 4, y0, 12, rgb(0xf6f3ea));
    b.box(wx - 3, y0 - 0.6, 7, wx + 3, y0 - 0.4, 11, rgb(0x3a5068));
  }
  // the gabled roof: two slopes meeting along the ridge (running east-west), overhanging
  const o = 3, peak = wall + d * 0.45;
  b.quad([x0 - o, y0 - o, wall - 1], [x1 + o, y0 - o, wall - 1], [x1 + o, y, peak], [x0 - o, y, peak], roof);
  b.quad([x0 - o, y1 + o, wall - 1], [x1 + o, y1 + o, wall - 1], [x1 + o, y, peak], [x0 - o, y, peak], shade(roof, 0.75));
  // the gable ends
  b.quad([x0, y0, wall], [x0, y1, wall], [x0, y, peak - 0.5], [x0, y, peak - 0.5], shade(logs, 0.8));
  b.quad([x1, y0, wall], [x1, y1, wall], [x1, y, peak - 0.5], [x1, y, peak - 0.5], shade(logs, 0.7));
  // a stone chimney on the east end
  b.box(x1 - 9, y + 2, wall, x1 - 4, y + 7, peak + 5, rgb(0x8a8a8e));
  b.box(x1 - 9.6, y + 1.4, peak + 5, x1 - 3.4, y + 7.6, peak + 6, rgb(0x6a6a6e));
}

/** a fallen tree trunk lying on the grass, with its root end and a broken branch */
function log(b: Builder, x: number, y: number, len: number, turn: number): void {
  const bark = rgb(0x7a4a2a), a = (turn * Math.PI) / 180;
  const ux = Math.cos(a), uy = Math.sin(a), n = 6;
  for (let k = 0; k < n; k++) {
    const t0 = -len / 2 + (k * len) / n, cx = x + ux * (t0 + len / n / 2), cy = y + uy * (t0 + len / n / 2);
    const r = 3.4 - k * 0.25;
    b.box(cx - r, cy - r, 0, cx + r, cy + r, r * 1.8, k % 2 ? bark : shade(bark, 0.88));
  }
  const ex = x - ux * len / 2, ey = y - uy * len / 2;
  b.box(ex - 4.5, ey - 4.5, 0, ex + 4.5, ey + 4.5, 8, shade(bark, 0.75)); // the root end
  b.box(ex - 2, ey - 2, 1, ex + 2, ey + 2, 7.4, rgb(0xd8b48a)); // its cut face
}

/** an animal's coat (its pixels that aren't grass, leaves or bark) and whether it sits on a stump */
function look(p: ImageData, x0: number, y0: number, w: number, h: number): { coat: Rgb; stump: boolean } {
  let r = 0, g = 0, bl = 0, n = 0, bark = 0, low = 0;
  for (let y = y0; y < y0 + h; y++) {
    for (let x = x0; x < x0 + w; x++) {
      const i = (Math.min(p.height - 1, y) * p.width + Math.min(p.width - 1, x)) * 4;
      const cr = p.data[i], cg = p.data[i + 1], cb = p.data[i + 2];
      const green = cg > cr + 6 && cg > cb + 6, brown = cr > cg && cg > cb && cr - cb > 40 && cr < 160;
      if (y > y0 + h / 2) {
        low++;
        if (brown) bark++;
      }
      if (green || brown || cr + cg + cb < 90) continue; // and the dark outline
      r += cr;
      g += cg;
      bl += cb;
      n++;
    }
  }
  const coat: Rgb = n ? [r / n / 255, g / n / 255, bl / n / 255] : [0.6, 0.6, 0.6];
  return { coat, stump: bark > low * 0.2 };
}

/**
 * the map picture for the ground, with grass painted over the animals and rocks (they stand
 * up as models now): each spot gets the grass colour from just outside it
 */
export function groundWithoutProps(picture: THREE.Texture): THREE.Texture {
  const img = picture.image as CanvasImageSource & { width: number; height: number };
  const canvas = document.createElement('canvas');
  canvas.width = img.width;
  canvas.height = img.height;
  const g = canvas.getContext('2d')!;
  g.drawImage(img, 0, 0);
  const data = g.getImageData(0, 0, canvas.width, canvas.height);
  for (const [x0, y0, w, h] of [...CRITTERS, ...ROCKS]) {
    for (let y = y0 - 1; y <= y0 + h; y++) {
      for (let x = x0 - 1; x <= x0 + w; x++) {
        // the nearest grass pixel along the row, left or right of the spot
        const i = (y * canvas.width + x) * 4;
        const fromX = x - x0 < w / 2 ? x0 - 3 : x0 + w + 2;
        const j = (y * canvas.width + Math.min(canvas.width - 1, Math.max(0, fromX))) * 4;
        for (let k = 0; k < 3; k++) data.data[i + k] = data.data[j + k];
      }
    }
  }
  g.putImageData(data, 0, 0);
  const t = new THREE.CanvasTexture(canvas);
  t.colorSpace = picture.colorSpace;
  t.magFilter = picture.magFilter;
  t.minFilter = picture.minFilter;
  t.generateMipmaps = false;
  return t;
}
