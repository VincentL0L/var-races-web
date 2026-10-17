// Each city's famous landmarks, built in 3D for the first-person view, standing where the map
// pictures paint them (map coordinates, y up). Plain coloured boxes and tapering blocks,
// shaded by which way each face points.
//
// The map pictures draw the tall ones as a side view rising up the picture from their base, so
// the 3D model stands on that base and the scenery is told to leave the area clear.
import * as THREE from 'three';
import { Builder, mix, rgb, shade, vertexColors, type Rgb } from './geom';

/** a map area (x0, y0, x1, y1) where the picture of a landmark is */
export type Area = [number, number, number, number];

const cos = (deg: number) => Math.cos((deg * Math.PI) / 180);
const sin = (deg: number) => Math.sin((deg * Math.PI) / 180);

class Landmarks {
  readonly b = new Builder();
  readonly clear: Area[] = [];

  clearAbove(x: number, y: number, half: number, up: number): void {
    this.clear.push([x - half, y - 6, x + half, y + up]);
  }

  box(x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, c: Rgb): void {
    this.b.box(x0, y0, z0, x1, y1, z1, c);
  }

  frustum(x: number, y: number, z0: number, z1: number, r0: number, r1: number, c: Rgb): void {
    this.b.frustum(x, y, z0, z1, r0, r1, c);
  }

  /** a square tower block, with window bands if asked */
  tower(x: number, y: number, hx: number, hy: number, z0: number, z1: number, c: Rgb, windows: boolean): void {
    this.tower2(x - hx, y - hy, x + hx, y + hy, z0, z1, c, windows);
  }

  tower2(x0: number, y0: number, x1: number, y1: number, z0: number, z1: number, c: Rgb, windows: boolean): void {
    this.box(x0, y0, z0, x1, y1, z1, c);
    if (windows) {
      const glass = rgb(0x46546a);
      for (let z = z0 + 6; z + 3 < z1; z += 9) this.box(x0 - 0.2, y0 - 0.2, z, x1 + 0.2, y1 + 0.2, z + 3, glass);
    }
  }

  /** the four corner legs of an open lattice tower, between z0 and z1 */
  legsFrustum(x: number, y: number, z0: number, z1: number, r0: number, r1: number, leg: number, c: Rgb): void {
    if (leg >= r0 * 0.9) {
      this.frustum(x, y, z0, z1, r0, r1, c);
      return;
    }
    const l1 = (leg * r1) / Math.max(0.01, r0);
    for (const sx of [-1, 1]) {
      for (const sy of [-1, 1]) {
        const cx0 = x + sx * (r0 - leg / 2), cy0 = y + sy * (r0 - leg / 2);
        const cx1 = x + sx * (r1 - l1 / 2), cy1 = y + sy * (r1 - l1 / 2);
        this.slantedPost(cx0, cy0, cx1, cy1, z0, z1, leg / 2, l1 / 2, c);
      }
    }
    // a cross-brace band round the tower at the top of the section
    const t = Math.max(1, (z1 - z0) * 0.12);
    this.frame(x, y, z1 - t, z1, r1, Math.max(1.2, l1 * 0.5), c);
  }

  /** a square post leaning from one centre to another */
  slantedPost(x0: number, y0: number, x1: number, y1: number, z0: number, z1: number, h0: number, h1: number, c: Rgb): void {
    const b = this.b;
    b.quad([x0 + h0, y0 - h0, z0], [x0 + h0, y0 + h0, z0], [x1 + h1, y1 + h1, z1], [x1 + h1, y1 - h1, z1], shade(c, 0.9));
    b.quad([x0 - h0, y0 + h0, z0], [x0 - h0, y0 - h0, z0], [x1 - h1, y1 - h1, z1], [x1 - h1, y1 + h1, z1], shade(c, 0.68));
    b.quad([x0 + h0, y0 + h0, z0], [x0 - h0, y0 + h0, z0], [x1 - h1, y1 + h1, z1], [x1 + h1, y1 + h1, z1], shade(c, 0.8));
    b.quad([x0 - h0, y0 - h0, z0], [x0 + h0, y0 - h0, z0], [x1 + h1, y1 - h1, z1], [x1 - h1, y1 - h1, z1], shade(c, 0.6));
  }

  /** a square ring of beams round (x, y) at half-width r */
  frame(x: number, y: number, z0: number, z1: number, r: number, t: number, c: Rgb): void {
    this.box(x - r, y - r, z0, x + r, y - r + t, z1, c);
    this.box(x - r, y + r - t, z0, x + r, y + r, z1, c);
    this.box(x - r, y - r, z0, x - r + t, y + r, z1, c);
    this.box(x + r - t, y - r, z0, x + r, y + r, z1, c);
  }

  /** an observation deck: a dark glass band with a light roof */
  deck(x: number, y: number, z: number, r: number, h: number, glass: Rgb, roof: Rgb): void {
    this.box(x - r, y - r, z, x + r, y + r, z + h * 0.75, glass);
    this.box(x - r - 1, y - r - 1, z + h * 0.75, x + r + 1, y + r + 1, z + h, roof);
  }

  /** a thin beam in the upright plane of a wheel, from (u0, v0) to (u1, v1) */
  plank(x: number, y: number, u0: number, v0: number, u1: number, v1: number, t: number, alongX: boolean, c: Rgb): void {
    const du = u1 - u0, dv = v1 - v0, len = Math.hypot(du, dv);
    if (len < 0.01) return;
    const nu = ((-dv / len) * t) / 2, nv = ((du / len) * t) / 2;
    const d = t / 2;
    const pts: [number, number][] = [[u0 + nu, v0 + nv], [u1 + nu, v1 + nv], [u1 - nu, v1 - nv], [u0 - nu, v0 - nv]];
    const at = (p: [number, number], side: number): [number, number, number] =>
      alongX ? [x + p[0], y + side * d, p[1]] : [x + side * d, y + p[0], p[1]];
    for (const side of [-1, 1]) {
      this.b.quad(at(pts[0], side), at(pts[1], side), at(pts[2], side), at(pts[3], side), side < 0 ? shade(c, 0.75) : c);
    }
    // the beam's edges
    for (let i = 0; i < 4; i++) {
      const p = pts[i], n = pts[(i + 1) % 4];
      this.b.quad(at(p, -1), at(n, -1), at(n, 1), at(p, 1), shade(c, 0.85));
    }
  }

  /** a beam along x from (x0, z0) to (x1, z1) at y = cy */
  plankX(x0: number, z0: number, x1: number, z1: number, cy: number, t: number, c: Rgb): void {
    const h = t / 2, b = this.b;
    b.quad([x0, cy - h, z0 + h], [x1, cy - h, z1 + h], [x1, cy + h, z1 + h], [x0, cy + h, z0 + h], c);
    b.quad([x0, cy - h, z0 - h], [x1, cy - h, z1 - h], [x1, cy - h, z1 + h], [x0, cy - h, z0 + h], shade(c, 0.7));
    b.quad([x1, cy + h, z1 - h], [x0, cy + h, z0 - h], [x0, cy + h, z0 + h], [x1, cy + h, z1 + h], shade(c, 0.85));
  }

  // ---------------------------------------------------------------- the landmarks

  /** Tokyo Tower: an orange and white lattice tower with two observation decks */
  tokyoTower(x: number, y: number): void {
    this.clearAbove(x, y, 60, 240);
    const h = 230, bands = 10;
    for (let i = 0; i < bands; i++) {
      const z0 = (h * i) / bands, z1 = (h * (i + 1)) / bands;
      const r0 = halfAt(z0 / h, 42), r1 = halfAt(z1 / h, 42);
      this.legsFrustum(x, y, z0, z1, r0, r1, Math.max(2, r0 * 0.22), i % 2 === 0 ? rgb(0xf05a28) : rgb(0xf2efe8));
    }
    this.deck(x, y, 0.38 * h, halfAt(0.38, 42) + 6, 9, rgb(0x3a4a5e), rgb(0xf2efe8));
    this.deck(x, y, 0.72 * h, halfAt(0.72, 42) + 3, 6, rgb(0x3a4a5e), rgb(0xf2efe8));
    this.box(x - 1.5, y - 1.5, h, x + 1.5, y + 1.5, h + 25, rgb(0xf2efe8));
  }

  /** the Empire State Building: stepped limestone tiers and its mast */
  empireState(x: number, y: number): void {
    this.clearAbove(x, y, 45, 240);
    for (const [r, z0, z1] of [[39, 0, 120], [27, 120, 175], [18, 175, 215], [9, 215, 235]]) {
      this.tower(x, y, r, r * 0.7, z0, z1, rgb(0xcfc8b8), true);
    }
    this.box(x - 4, y - 4, 235, x + 4, y + 4, 252, rgb(0xb7b0a2));
    this.box(x - 1, y - 1, 252, x + 1, y + 1, 290, rgb(0xe8e2d4));
  }

  /** the Flatiron: a wedge of a building on its triangular block */
  flatiron(x: number, y: number): void {
    this.clear.push([x - 4, y - 70, x + 46, y + 4]);
    for (let i = 0; i < 11; i++) {
      const y0 = y - i * 6, y1 = y - (i + 1) * 6;
      const w = 6 + (i + 1) * 3.4;
      this.tower2(x, y1, x + w, y0, 0, 72, rgb(0xdccaa6), i === 0);
    }
  }

  /** the Statue of Liberty: green, torch held high, on her pedestal */
  liberty(x: number, y: number): void {
    const stone = rgb(0xc9bfa9), green = rgb(0x6fb8a0);
    this.frustum(x, y, 0, 6, 18, 16, stone);
    this.box(x - 8, y - 8, 6, x + 8, y + 8, 30, stone);
    this.frustum(x, y, 30, 34, 9, 7, stone);
    this.frustum(x, y, 34, 58, 5, 3.2, green);
    this.box(x - 2, y - 2, 58, x + 2, y + 2, 63, green);
    for (let k = 0; k < 7; k++) {
      const a = k * 51;
      const cx = x + cos(a) * 2.6, cy = y + sin(a) * 2.6;
      this.box(cx - 0.5, cy - 0.5, 62, cx + 0.5, cy + 0.5, 65, green);
    }
    this.box(x + 3, y - 1, 52, x + 5, y + 1, 70, green);
    this.box(x + 2.5, y - 1.5, 70, x + 5.5, y + 1.5, 73, rgb(0xffd84a));
  }

  /** Willis Tower: nine black bundled tubes stepping up, two white antennas */
  willis(x: number, y: number): void {
    this.clearAbove(x, y, 42, 220);
    const s = 13;
    const tubes = [[-1, -1, 150], [0, -1, 210], [1, -1, 170], [-1, 0, 190], [0, 0, 245], [1, 0, 210], [-1, 1, 170], [0, 1, 230], [1, 1, 150]];
    for (const [tx, ty, th] of tubes) this.tower(x + tx * s, y + 20 + ty * s, s / 2, s / 2, 0, th, rgb(0x30333b), true);
    this.box(x - 4, y + 18, 245, x - 2.5, y + 19.5, 300, rgb(0xf2f2f2));
    this.box(x + 2.5, y + 18, 245, x + 4, y + 19.5, 290, rgb(0xf2f2f2));
  }

  /** Cloud Gate (the Bean): a squashed, shiny steel blob with an arch under it */
  bean(x: number, y: number): void {
    const steel = rgb(0xc3cbd6);
    for (let i = 0; i < 6; i++) {
      const z0 = 4 + i * 3, z1 = z0 + 3;
      const t = (z0 + 1.5 - 12) / 10;
      const k = Math.sqrt(Math.max(0.05, 1 - t * t));
      this.box(x - 27 * k, y - 15 * k, z0, x + 27 * k, y + 15 * k, z1, mix(steel, rgb(0x7fc0ff), i / 10));
    }
    this.box(x - 27, y - 9, 0, x - 16, y + 9, 4, steel);
    this.box(x + 16, y - 9, 0, x + 27, y + 9, 4, steel);
  }

  /** a Ferris wheel standing on its legs (Navy Pier's, or the London Eye) */
  ferrisWheel(x: number, y: number, r: number, alongX: boolean, rim: Rgb): void {
    const hub = r + 6, spokes = 18;
    for (let k = 0; k < 60; k++) {
      const a = k * 6, a2 = a + 6;
      this.plank(x, y, cos(a) * r, sin(a) * r + hub, cos(a2) * r, sin(a2) * r + hub, 1.6, alongX, rim);
    }
    for (let k = 0; k < spokes; k++) {
      const a = (k * 360) / spokes;
      this.plank(x, y, 0, hub, cos(a) * r, sin(a) * r + hub, 0.5, alongX, rgb(0xb7bec8));
      const pu = cos(a) * (r + 2), pv = sin(a) * (r + 2) + hub;
      const px = alongX ? x + pu : x, py = alongX ? y : y + pu;
      this.box(px - 2, py - 2, pv - 2, px + 2, py + 2, pv + 2, rgb(0x9fd3ef));
    }
    this.plank(x, y, 0, hub, -r * 0.45, 0, 2.4, alongX, rgb(0x8a8f99));
    this.plank(x, y, 0, hub, r * 0.45, 0, 2.4, alongX, rgb(0x8a8f99));
  }

  /** the Palace of Westminster: a long gothic block with pinnacles */
  parliament(x: number, y: number): void {
    this.clear.push([x, y - 4, x + 510, y + 70]);
    const stone = rgb(0xd8c48e);
    this.box(x, y, 0, x + 510, y + 42, 48, stone);
    for (let px = x + 6; px < x + 505; px += 36) this.box(px, y - 1, 48, px + 6, y + 5, 60, rgb(0xb79f6c));
    this.tower(x + 495, y + 21, 18, 18, 0, 110, stone, true);
  }

  /** Big Ben: clock faces near the top, and its spire */
  bigBen(x: number, y: number): void {
    this.clearAbove(x, y, 20, 215);
    const stone = rgb(0xd2bd86), face = rgb(0xf6f1df);
    this.tower(x, y, 12, 12, 0, 135, stone, false);
    this.box(x - 13, y - 13, 135, x + 13, y + 13, 160, rgb(0xe9d9a6));
    this.box(x - 8, y - 13.4, 139, x + 8, y - 13, 155, face);
    this.box(x - 8, y + 13, 139, x + 8, y + 13.4, 155, face);
    this.box(x - 13.4, y - 8, 139, x - 13, y + 8, 155, face);
    this.box(x + 13, y - 8, 139, x + 13.4, y + 8, 155, face);
    this.frustum(x, y, 160, 205, 12, 1, rgb(0x5f6e5a));
  }

  /** Tower Bridge: two stone towers over the road with blue walkways across the top */
  towerBridge(x: number, y0: number, y1: number): void {
    const stone = rgb(0xd6c6a3), blue = rgb(0x4f7fbf);
    const inner = 68, outer = 104;
    for (const ty of [y0, y1]) {
      for (const side of [-1, 1]) {
        const a = x + side * inner, bb = x + side * outer;
        this.box(Math.min(a, bb), ty - 20, 0, Math.max(a, bb), ty + 20, 105, stone);
        this.frustum((a + bb) / 2, ty, 105, 125, 12, 2, rgb(0x6d7b88));
      }
      this.box(x - inner, ty - 20, 60, x + inner, ty + 20, 105, stone);
    }
    this.box(x - outer, y0 + 20, 92, x - outer + 14, y1 - 20, 100, blue);
    this.box(x + outer - 14, y0 + 20, 92, x + outer, y1 - 20, 100, blue);
  }

  /** the Eiffel Tower: four splayed iron legs meeting at the first level, a needle above */
  eiffel(x: number, y: number): void {
    this.clearAbove(x, y, 66, 225);
    const iron = rgb(0x7a6550), light = rgb(0xa58b6e);
    const h = 260, bands = 14;
    for (let i = 0; i < bands; i++) {
      const z0 = (h * i) / bands, z1 = (h * (i + 1)) / bands;
      const r0 = halfAt(z0 / h, 62, 2.2), r1 = halfAt(z1 / h, 62, 2.2);
      const leg = z0 < h * 0.22 ? Math.max(4, r0 * 0.3) : r0;
      this.legsFrustum(x, y, z0, z1, r0, r1, leg, i % 2 === 0 ? iron : light);
    }
    this.deck(x, y, h * 0.22, halfAt(0.22, 62, 2.2) + 4, 6, rgb(0x5a4a3c), light);
    this.deck(x, y, h * 0.48, halfAt(0.48, 62, 2.2) + 2, 5, rgb(0x5a4a3c), light);
    this.box(x - 1.5, y - 1.5, h, x + 1.5, y + 1.5, h + 20, iron);
  }

  /** the Arc de Triomphe in the middle of its round plaza */
  arc(x: number, y: number): void {
    this.clear.push([x - 40, y - 30, x + 40, y + 30]);
    const stone = rgb(0xe3d6b8);
    this.box(x - 25, y - 12, 0, x - 9, y + 12, 40, stone);
    this.box(x + 9, y - 12, 0, x + 25, y + 12, 40, stone);
    this.box(x - 25, y - 12, 28, x + 25, y + 12, 52, stone);
    this.box(x - 26, y - 13, 52, x + 26, y + 13, 55, rgb(0xcbbd9e));
  }

  /** Taipei 101: eight flared green-glass sections stacked like bamboo, and its spire */
  taipei101(x: number, y: number): void {
    this.clearAbove(x, y, 32, 250);
    const glass = rgb(0x5f9a8e), light = rgb(0x9fc7bd);
    this.tower(x, y, 27, 27, 0, 48, glass, true);
    this.frustum(x, y, 48, 54, 27, 18, light);
    let z = 54;
    for (let seg = 0; seg < 8; seg++) {
      this.frustum(x, y, z, z + 22, 15, 21, seg % 2 === 0 ? glass : rgb(0x6aa89a));
      this.frustum(x, y, z + 22, z + 24, 21, 15, light);
      z += 24;
    }
    this.frustum(x, y, z, z + 14, 12, 8, glass);
    this.box(x - 2, y - 2, z + 14, x + 2, y + 2, z + 50, rgb(0xd9e4e0));
  }

  /** the Golden Gate: two tall orange towers, main cables and suspender ropes */
  goldenGate(): void {
    const orange = rgb(0xc0362c), dark = rgb(0x8f2a22);
    const deckY = 880, half = 84, legY = half + 14, top = 220;
    for (const tx of [640, 1160]) {
      for (const side of [-1, 1]) {
        const ly = deckY + side * legY;
        this.tower2(tx - 10, ly - 9, tx + 10, ly + 9, 0, top, orange, false);
      }
      for (const z of [40, 110, 170, 212]) this.box(tx - 7, deckY - legY, z, tx + 7, deckY + legY, z + 10, dark);
    }
    // the main cables sag between the towers and down to each shore; ropes hang to the deck
    const xs = [400, 640, 1160, 1420];
    for (const side of [-1, 1]) {
      const cy = deckY + side * (half + 4);
      for (let span = 0; span < 3; span++) {
        const a = xs[span], bx = xs[span + 1];
        const steps = Math.floor((bx - a) / 12);
        for (let k = 0; k < steps; k++) {
          const x0 = a + ((bx - a) * k) / steps, x1 = a + ((bx - a) * (k + 1)) / steps;
          const z0 = cable(span, (x0 - a) / (bx - a), top), z1 = cable(span, (x1 - a) / (bx - a), top);
          this.plankX(x0, z0, x1, z1, cy, 1.4, orange);
          if (k % 2 === 0) this.box(x0 - 0.3, cy - 0.3, 6, x0 + 0.3, cy + 0.3, z0, orange);
        }
      }
    }
  }

  /** the Transamerica Pyramid: a slender white pyramid with two shoulders */
  transamerica(x: number, y: number): void {
    this.clear.push([x - 40, y - 40, x + 40, y + 40]);
    const white = rgb(0xf1eee6);
    this.frustum(x, y, 0, 200, 30, 3, white);
    this.box(x - 22, y - 6, 120, x - 14, y + 6, 140, rgb(0xdcd8cc));
    this.box(x + 14, y - 6, 120, x + 22, y + 6, 140, rgb(0xdcd8cc));
    this.frustum(x, y, 200, 230, 3, 0.5, white);
  }

  /** Coit Tower on Telegraph Hill: a white fluted column */
  coit(x: number, y: number): void {
    this.clear.push([x - 22, y - 22, x + 22, y + 22]);
    this.frustum(x, y, 0, 75, 14, 13, rgb(0xf0eadb));
    this.frustum(x, y, 75, 82, 15, 15, rgb(0xd9d2c0));
  }

  /** the Ferry Building on the Embarcadero, and its clock tower */
  ferryBuilding(): void {
    this.clear.push([1825, 375, 1895, 645]);
    this.box(1830, 380, 0, 1890, 640, 34, rgb(0xdccdb0));
    this.box(1832, 382, 34, 1888, 638, 40, rgb(0x9a8a70));
    const cx = 1860, cy = 510;
    this.tower(cx, cy, 12, 12, 40, 105, rgb(0xc7b08a), false);
    this.box(cx - 12.5, cy - 6, 85, cx + 12.5, cy + 6, 97, rgb(0xf6f1df));
    this.frustum(cx, cy, 105, 125, 9, 1, rgb(0x2f6f62));
  }

  /** Alcatraz: the cell house on the rock, and its lighthouse */
  alcatraz(x: number, y: number): void {
    this.box(x - 27, y - 9, 0, x + 21, y + 6, 22, rgb(0xece8de));
    this.box(x - 27, y - 9, 22, x + 21, y + 6, 25, rgb(0xb9b2a4));
    this.tower(x + 36, y + 12, 3, 3, 0, 42, rgb(0xf2f2f2), false);
    this.box(x + 33.5, y + 9.5, 42, x + 38.5, y + 14.5, 47, rgb(0xffe08a));
  }
}

/** a tower's half-width at a share of its height: wide feet, narrowing fast */
function halfAt(t: number, base: number, power = 1.6): number {
  return base * Math.pow(1 - t, power) + 3;
}

/** the cable's height across a span: a sag from tower top to tower top, or down to a shore */
function cable(span: number, t: number, top: number): number {
  if (span === 1) return 18 + (top - 18) * Math.pow(2 * t - 1, 2);
  return span === 0 ? 10 + (top - 10) * t * t : 10 + (top - 10) * (1 - t) * (1 - t);
}

/** a map's landmarks as one mesh (null if it has none), and the areas they stand on */
export function buildLandmarks(mapId: string): { mesh: THREE.Mesh | null; clear: Area[] } {
  const l = new Landmarks();
  switch (mapId) {
    case 'tokyo':
      l.tokyoTower(1495, 300);
      break;
    case 'nyc':
      l.empireState(560, 420);
      l.flatiron(1355, 320);
      l.liberty(330, 60);
      break;
    case 'chicago':
      l.willis(420, 320);
      l.bean(1300, 440);
      l.ferrisWheel(1790, 850, 33, true, rgb(0xd9433b));
      break;
    case 'london':
      l.parliament(360, 520);
      l.bigBen(330, 515);
      l.ferrisWheel(1380, 830, 66, true, rgb(0xd9dde3));
      l.towerBridge(1760, 652, 733);
      break;
    case 'paris':
      l.eiffel(1100, 650);
      l.arc(1430, 370);
      break;
    case 'taipei':
      l.taipei101(1540, 760);
      break;
    case 'sf':
      l.goldenGate();
      l.transamerica(1440, 370);
      l.coit(1588, 555);
      l.ferryBuilding();
      l.alcatraz(1000, 1015);
      break;
  }
  return { mesh: l.b.empty ? null : l.b.mesh(vertexColors()), clear: l.clear };
}
