// The trees and buildings of a map, stood up in 3D for both perspective views. heights.png (made
// by tools from the map picture) says how tall things are on a grid of CELL x CELL patches.
//  - buildings: blocks whose roofs are that bit of the map picture and whose walls are rows of
//    windows, painted in the roof's colour
//  - trees: two crossed upright pictures of a tree, tinted the colour of the leaves seen from
//    above, so cherry blossom stays pink
import * as THREE from 'three';
import type { MapDef } from '../wasm';
import { Builder, mix, vertexColors, type Rgb } from './geom';
import { facade, tree } from './textures';
import type { Area } from './landmarks';

/** map pixels per height cell (must match the height map maker) */
const CELL = 12;
/** one window bay is this wide, one storey this tall (map pixels) */
const BAY = 16;
const STOREY = 12;

export function buildScenery(
  map: MapDef,
  heights: ImageData,
  picture: ImageData,
  roofTexture: THREE.Texture,
  clear: Area[],
): THREE.Group {
  const group = new THREE.Group();
  const gw = heights.width, gh = heights.height;
  const height = (x: number, y: number) => (x < 0 || y < 0 || x >= gw || y >= gh ? 0 : heights.data[(y * gw + x) * 4]);
  const kind = (x: number, y: number) => heights.data[(y * gw + x) * 4 + 1];
  const worldW = map.width, worldH = map.height, scale = map.scale;
  const roofs = new Builder(), walls = new Builder(), trees = new Builder();

  for (let gx = 0; gx < gw; gx++) {
    for (let gy = 0; gy < gh; gy++) {
      const h = height(gx, gy);
      if (h === 0) continue;
      // the picture's rows run top down; the map's y runs up
      const x0 = gx * CELL, x1 = Math.min(worldW, x0 + CELL);
      const y1 = worldH - gy * CELL, y0 = Math.max(0, y1 - CELL);
      const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
      if (clear.some((a) => cx >= a[0] && cx <= a[2] && cy >= a[1] && cy <= a[3])) continue; // a landmark stands here
      const isTree = kind(gx, gy) === 1;
      const avg = average(picture, Math.floor((gx * CELL) / scale), Math.floor((gy * CELL) / scale), Math.floor(CELL / scale), isTree);
      const seed = Math.imul(gx, 73856093) ^ Math.imul(gy, 19349663);
      if (isTree) {
        // a tree in most tree cells, nudged about so they don't line up
        if (mod(seed, 10) < 3) continue;
        const tx = cx + (mod(seed >> 4, 9) - 4), ty = cy + (mod(seed >> 8, 9) - 4);
        const size = 15 + mod(seed >> 12, 9);
        const tall = h * 1.35;
        const leaf: Rgb = [Math.min(1, avg[0] * 1.45), Math.min(1, avg[1] * 1.45), Math.min(1, avg[2] * 1.45)];
        const turn = mod(seed >> 16, 90);
        for (let k = 0; k < 2; k++) {
          const a = ((turn + k * 90) * Math.PI) / 180;
          const dx = (Math.cos(a) * size) / 2, dy = (Math.sin(a) * size) / 2;
          trees.quad([tx - dx, ty - dy, 0], [tx + dx, ty + dy, 0], [tx + dx, ty + dy, tall], [tx - dx, ty - dy, tall], leaf, [0, 0, 1, 0, 1, 1, 0, 1]);
        }
        continue;
      }
      // building: the roof is the map picture
      const u0 = x0 / worldW, u1 = x1 / worldW, v0 = y0 / worldH, v1 = y1 / worldH;
      roofs.quad([x0, y0, h], [x1, y0, h], [x1, y1, h], [x0, y1, h], [1, 1, 1], [u0, v0, u1, v0, u1, v1, u0, v1]);
      // walls of windows, painted like the roof (lighter), shaded by which way they face
      const paint = mix(avg, [1, 1, 1], 0.35);
      const wall = (nx: number, ny: number, ax: number, ay: number, bx: number, by: number, s: number) => {
        const low = height(nx, ny);
        if (low >= h) return;
        // windows line up across the whole map: u from where along the street, v from the height
        const ua = (ax === bx ? ay : ax) / BAY / 4, ub = (ax === bx ? by : bx) / BAY / 4;
        const va = low / STOREY / 4, vb = h / STOREY / 4;
        walls.quad([ax, ay, low], [bx, by, low], [bx, by, h], [ax, ay, h], [paint[0] * s, paint[1] * s, paint[2] * s], [ua, va, ub, va, ub, vb, ua, vb]);
      };
      wall(gx + 1, gy, x1, y0, x1, y1, 0.92);
      wall(gx - 1, gy, x0, y1, x0, y0, 0.68);
      wall(gx, gy - 1, x1, y1, x0, y1, 1);
      wall(gx, gy + 1, x0, y0, x1, y0, 0.6);
    }
  }
  if (!roofs.empty) group.add(roofs.mesh(vertexColors(roofTexture)));
  if (!walls.empty) group.add(walls.mesh(vertexColors(facade())));
  if (!trees.empty) group.add(trees.mesh(vertexColors(tree(), 0.5)));
  return group;
}

function mod(a: number, n: number): number {
  return ((a % n) + n) % n;
}

/** the colour of a patch of the map picture (just the leafy pixels for a tree) */
function average(p: ImageData, px: number, py: number, size: number, leaves: boolean): Rgb {
  let r = 0, g = 0, b = 0, n = 0;
  size = Math.max(1, size);
  for (let x = px; x < Math.min(p.width, px + size); x++) {
    for (let y = py; y < Math.min(p.height, py + size); y++) {
      const i = (y * p.width + x) * 4;
      const cr = p.data[i] / 255, cg = p.data[i + 1] / 255, cb = p.data[i + 2] / 255;
      if (leaves && cr + cg + cb > 2.2) continue;
      r += cr;
      g += cg;
      b += cb;
      n++;
    }
  }
  return n === 0 ? [0.3, 0.5, 0.25] : [r / n, g / n, b / n];
}
