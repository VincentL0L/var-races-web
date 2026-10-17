// The trees and buildings of a map, stood up in 3D for both perspective views. heights.png (made
// by tools from the map picture) says how tall things are on a grid of CELL x CELL patches.
//  - buildings: each designed in its city's architecture (architecture.ts), with that bit of the
//    map picture for a roof
//  - trees: two crossed upright pictures of a tree, tinted the colour of the leaves seen from
//    above, so cherry blossom stays pink
import * as THREE from 'three';
import type { MapDef } from '../wasm';
import { CityArchitecture } from './architecture';
import { Builder, vertexColors, type Rgb } from './geom';
import { tree } from './textures';
import type { Area } from './landmarks';

/** map pixels per height cell (must match the height map maker) */
const CELL = 12;

export function buildScenery(
  map: MapDef,
  heights: ImageData,
  picture: ImageData,
  roofTexture: THREE.Texture,
  clear: Area[],
): THREE.Group {
  const group = new THREE.Group();
  const gw = heights.width, gh = heights.height;
  const worldW = map.width, worldH = map.height, scale = map.scale;
  const inside = (x: number, y: number) => clear.some((a) => x >= a[0] && x <= a[2] && y >= a[1] && y <= a[3]);
  // the grids, x-major; skip: a building patch a landmark stands on
  const height: number[][] = [], kind: number[][] = [], skip: boolean[][] = [];
  for (let gx = 0; gx < gw; gx++) {
    height.push([]);
    kind.push([]);
    skip.push([]);
    for (let gy = 0; gy < gh; gy++) {
      const i = (gy * gw + gx) * 4;
      height[gx].push(heights.data[i]);
      kind[gx].push(heights.data[i + 1]);
      skip[gx].push(inside(gx * CELL + CELL / 2, worldH - gy * CELL - CELL / 2));
    }
  }
  const at = (x: number, y: number) => (x < 0 || y < 0 || x >= gw || y >= gh ? 0 : height[x][y]);
  // the buildings themselves, each in its city's architecture (this rounds their heights)
  const architecture = new CityArchitecture(map.id, height, kind, skip, worldH);
  group.add(architecture.group);
  const roofs = new Builder(), trees = new Builder();

  for (let gx = 0; gx < gw; gx++) {
    for (let gy = 0; gy < gh; gy++) {
      const h = height[gx][gy];
      if (h === 0) continue;
      // the picture's rows run top down; the map's y runs up
      const x0 = gx * CELL, x1 = Math.min(worldW, x0 + CELL);
      const y1 = worldH - gy * CELL, y0 = Math.max(0, y1 - CELL);
      const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
      if (inside(cx, cy)) continue; // a landmark stands here
      if (kind[gx][gy] === 1) {
        // a tree in most tree cells, nudged about so they don't line up
        const seed = Math.imul(gx, 73856093) ^ Math.imul(gy, 19349663);
        if (mod(seed, 10) < 3) continue;
        const avg = average(picture, Math.floor((gx * CELL) / scale), Math.floor((gy * CELL) / scale), Math.floor(CELL / scale), true);
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
      if (!architecture.builds(gx, gy)) continue;
      // the roof: the map's own picture of it, on top of the mansard where there is one
      const lift = architecture.roofLift(gx, gy), inset = architecture.roofInset(gx, gy);
      const rx0 = x0 + (at(gx - 1, gy) < h ? inset : 0), rx1 = x1 - (at(gx + 1, gy) < h ? inset : 0);
      const ry0 = y0 + (at(gx, gy + 1) < h ? inset : 0), ry1 = y1 - (at(gx, gy - 1) < h ? inset : 0);
      const u0 = rx0 / worldW, u1 = rx1 / worldW, v0 = ry0 / worldH, v1 = ry1 / worldH;
      const rz = h + lift;
      roofs.quad([rx0, ry0, rz], [rx1, ry0, rz], [rx1, ry1, rz], [rx0, ry1, rz], [1, 1, 1], [u0, v0, u1, v0, u1, v1, u0, v1]);
    }
  }
  if (!roofs.empty) group.add(roofs.mesh(vertexColors(roofTexture)));
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
