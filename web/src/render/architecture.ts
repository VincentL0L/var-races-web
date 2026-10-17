// The buildings of each city for the first-person view, every one of them different, in the
// game's chunky 16-bit pixel style.
//
// The height map says which 12 x 12 patches are buildings; neighbouring patches of the same
// height make one building. Each building is then designed from its own random seed and its
// city's rules: its facade is drawn pixel by pixel (the material, its colour, the windows'
// size, shape, frames and panes, balconies, shutters, fire escapes, AC units, neon, and a
// shopfront with an awning on the ground floor), and its shape gets its own height, cornice or
// parapet, awnings, and roof: mansards, slate roofs and chimneys, water towers, tanks, plant,
// billboards, gardens, solar panels, domes, spires and crowns. So a map of a few hundred
// buildings has a few hundred different ones.
//
// The cities: Paris (Haussmann stone and stucco, iron balconies, zinc mansards), London (stock
// brick, red brick and white stucco terraces, sash windows, slate and chimneys), New York
// (brick and brownstone, fire escapes, water towers, Art Deco and glass towers), Chicago
// (brick, greystone, steel), Tokyo (tile and concrete, ribbon windows, neon, billboards),
// Taipei (concrete flats, caged windows, laundry, tanks), San Francisco (pastel Victorians
// with bay windows and turrets, downtown towers).
import * as THREE from 'three';
import { Builder, rgb as c, vertexColors, type Rgb } from './geom';

const CELL = 12;
/** a facade picture covers 4 bays of 16 units across and 8 storeys of 12 up */
const TILE_W = 64, TILE_H = 128, STOREY = 12;
const PARIS = 0, LONDON = 1, NYC = 2, CHICAGO = 3, TOKYO = 4, TAIPEI = 5, SF = 6, GENERIC = 7;
const BRICK = 0, STONE = 1, STUCCO = 2, SIDING = 3, TILE = 4, CONCRETE = 5;
/**
 * a facade picture: 128 x 256 pixels (sizes WebGL can repeat) covering 4 bays of 16 units
 * across and 128 units up, so one pixel is half a unit, as fine as the cars' pixels. Storeys
 * are 24 pixels; the ground floor is the bottom one, counted up from there.
 */
const PW = 128, PH = 256, BAYP = 32, STP = 24, GROUND = PH - STP;

type E = [number, number, number, number];

/** java.util.Random's numbers, so a city's buildings are the same every time */
class Random {
  private s: bigint;
  constructor(seed: number | bigint) {
    this.s = (BigInt.asIntN(64, BigInt(seed)) ^ 0x5deece66dn) & ((1n << 48n) - 1n);
  }
  private next(bits: number): number {
    this.s = (this.s * 0x5deece66dn + 0xbn) & ((1n << 48n) - 1n);
    return Number(BigInt.asIntN(32, this.s >> BigInt(48 - bits)));
  }
  nextInt(n?: number): number {
    if (n === undefined) return this.next(32);
    if ((n & -n) === n) return Number((BigInt(n) * BigInt(this.next(31))) >> 31n);
    let bits: number, val: number;
    do {
      bits = this.next(31);
      val = bits % n;
    } while (bits - val + (n - 1) < 0 || bits - val + (n - 1) > 0x7fffffff);
    return val;
  }
  nextFloat(): number {
    return this.next(24) / (1 << 24);
  }
  nextBoolean(): boolean {
    return this.next(1) !== 0;
  }
}

/** Java's String.hashCode */
export function hashCode(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return h;
}

const pick = <T>(r: Random, ...options: T[]): T => options[r.nextInt(options.length)];
const mul = (k: Rgb, f: number): Rgb => [Math.min(1, k[0] * f), Math.min(1, k[1] * f), Math.min(1, k[2] * f)];
const jitter = (k: Rgb, r: Random, amount: number) => mul(k, 1 + (r.nextFloat() - 0.5) * 2 * amount);
const mod = (a: number, n: number) => ((a % n) + n) % n;
const RAD = Math.PI / 180;

interface Building {
  cells: [number, number][];
  height: number;
  seed: number;
  tall: boolean;
  mansard: boolean;
  shop: boolean;
  deco: boolean;
  slopeRise: number;
  slopeInset: number;
  rnd: Random;
  fb: Builder;
  stone: Rgb;
  awning: Rgb;
  roofTone: Rgb;
  /** 0 deep, 1 thin, 2 parapet */
  cornice: number;
}

/** a facade picture being drawn */
class Art {
  readonly data = new Uint8ClampedArray(PW * PH * 4);
  px(x: number, y: number, k: Rgb): void {
    if (x >= 0 && y >= 0 && x < PW && y < PH) {
      const i = (y * PW + x) * 4;
      this.data[i] = k[0] * 255;
      this.data[i + 1] = k[1] * 255;
      this.data[i + 2] = k[2] * 255;
      this.data[i + 3] = 255;
    }
  }
  rect(x: number, y: number, w: number, h: number, k: Rgb): void {
    const x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(PW, x + w), y1 = Math.min(PH, y + h);
    for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) this.px(xx, yy, k);
  }
  done(): THREE.Texture {
    const canvas = document.createElement('canvas');
    canvas.width = PW;
    canvas.height = PH;
    canvas.getContext('2d')!.putImageData(new ImageData(this.data, PW, PH), 0, 0);
    const t = new THREE.CanvasTexture(canvas);
    t.wrapS = THREE.RepeatWrapping;
    t.wrapT = THREE.ClampToEdgeWrapping;
    t.magFilter = t.minFilter = THREE.NearestFilter;
    t.generateMipmaps = false;
    return t;
  }
}

function familyOf(id: string): number {
  switch (id) {
    case 'paris': return PARIS;
    case 'london': return LONDON;
    case 'nyc': case 'rushhour': return NYC;
    case 'chicago': case 'rooftop': return CHICAGO;
    case 'tokyo': return TOKYO;
    case 'taipei': return TAIPEI;
    case 'sf': return SF;
    default: return GENERIC;
  }
}

export class CityArchitecture {
  readonly group = new THREE.Group();
  private readonly family: number;
  private readonly solid = new Builder();
  private readonly owner: Int32Array;
  private readonly buildings: Building[] = [];
  private readonly facades: [THREE.Texture, Builder][] = [];

  /**
   * @param height the height grid (gw x gh, x-major), rounded here to whole storeys
   * @param kind 1 tree, 2 building
   */
  constructor(private readonly city: string, private readonly height: number[][], kind: number[][], skip: boolean[][], private readonly worldH: number) {
    const gw = height.length, gh = height[0].length;
    this.family = familyOf(city);
    this.owner = new Int32Array(gw * gh).fill(-1);
    // group neighbouring building patches of the same height into buildings
    for (let x = 0; x < gw; x++) {
      for (let y = 0; y < gh; y++) {
        if (kind[x][y] !== 2 || height[x][y] === 0 || this.owner[x * gh + y] >= 0 || skip[x][y]) continue;
        const b = { cells: [], height: height[x][y], seed: Math.imul(x, 73856093) ^ Math.imul(y, 19349663) ^ hashCode(city) } as unknown as Building;
        const q: [number, number][] = [[x, y]];
        this.owner[x * gh + y] = this.buildings.length;
        while (q.length) {
          const cell = q.shift()!;
          b.cells.push(cell);
          for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
            const nx = cell[0] + dx, ny = cell[1] + dy;
            if (nx >= 0 && ny >= 0 && nx < gw && ny < gh && this.owner[nx * gh + ny] < 0 && kind[nx][ny] === 2 && height[nx][ny] === b.height && !skip[nx][ny]) {
              this.owner[nx * gh + ny] = this.buildings.length;
              q.push([nx, ny]);
            }
          }
        }
        this.buildings.push(b);
      }
    }
    // design every building: its height (rounded to whole storeys), then its look
    for (const b of this.buildings) {
      b.rnd = new Random(b.seed);
      b.tall = b.height >= 52;
      const f = b.tall ? 0.95 + b.rnd.nextFloat() * 0.45 : 0.75 + b.rnd.nextFloat() * 0.55;
      const storeys = Math.min(7, Math.max(2, Math.round((b.height * f) / STOREY)));
      b.height = storeys * STOREY + 2;
      for (const [cx, cy] of b.cells) height[cx][cy] = b.height;
    }
    for (const b of this.buildings) this.design(b);
    this.buildings.forEach((b, i) => this.build(i, b));
    if (this.family !== GENERIC) this.outskirts(gw * CELL, worldH);
    if (!this.solid.empty) this.group.add(this.solid.mesh(vertexColors()));
    for (const [t, fb] of this.facades) if (!fb.empty) this.group.add(fb.mesh(vertexColors(t)));
  }

  private facadeBuilder(t: THREE.Texture): Builder {
    const fb = new Builder();
    this.facades.push([t, fb]);
    return fb;
  }

  // ---------------------------------------------------------------- designing a building

  private design(b: Building): void {
    const r = b.rnd, family = this.family;
    const towerCity = family === NYC || family === CHICAGO || family === TOKYO || family === TAIPEI || family === SF
      || (family === LONDON && b.cells.length >= 10) || family === GENERIC;
    const tower = b.tall && towerCity && (family as number) !== PARIS;
    b.deco = tower && (family === NYC || family === CHICAGO) && r.nextFloat() < 0.45;
    b.shop = !tower && r.nextFloat() < (family === TOKYO || family === TAIPEI || family === PARIS ? 0.8 : 0.55);
    b.mansard = !tower && (family === PARIS ? r.nextFloat() < 0.85 : family === LONDON ? r.nextFloat() < 0.55 : r.nextFloat() < 0.06);
    b.slopeRise = family === PARIS ? 8 + r.nextInt(4) : 9 + r.nextInt(5);
    b.slopeInset = family === PARIS ? 3.5 + r.nextFloat() * 1.5 : 5 + r.nextFloat() * 2;
    b.cornice = tower ? (r.nextBoolean() ? 1 : 2) : r.nextFloat() < 0.55 ? 0 : r.nextFloat() < 0.5 ? 1 : 2;
    b.awning = pick(r, c(0x8c2b2b), c(0x2b5c3a), c(0x1f3b6b), c(0x6b2b4e), c(0xc9792b), c(0x2b2b2b), c(0xd9433b), c(0x3f8f8f));
    const t = tower ? this.towerFacade(b, r) : this.facade(b, r);
    b.fb = this.facadeBuilder(t);
    b.stone ??= c(0xe8e2d4);
    b.roofTone = family === PARIS ? c(0x7f8794) : family === LONDON ? pick(r, c(0x5a606b), c(0x6b5a52), c(0x4f5560)) : c(0x6a6a70);
  }

  // ---------------------------------------------------------------- facades, pixel by pixel

  /** an ordinary building's front: material, windows and all their trimmings, and a ground floor */
  private facade(b: Building, r: Random): THREE.Texture {
    const a = new Art(), family = this.family;
    let material: number, wall: Rgb, wwMin: number, wwMax: number, whMin: number, whMax: number;
    switch (family) {
      case PARIS:
        material = r.nextFloat() < 0.6 ? STONE : STUCCO;
        wall = pick(r, c(0xeee5cf), c(0xe6dcc3), c(0xf2ead8), c(0xdcd3c2), c(0xe9dccb), c(0xd9cdb4), c(0xefe2d0), c(0xe4d6c0));
        [wwMin, wwMax, whMin, whMax] = [13, 17, 16, 19];
        break;
      case LONDON:
        material = r.nextFloat() < 0.65 ? BRICK : STUCCO;
        wall = material === BRICK ? pick(r, c(0xb89a6e), c(0xa8835a), c(0x9c5a42), c(0x8e4a38), c(0xc2a578), c(0x7f5f48))
          : pick(r, c(0xf2efe6), c(0xefe7d4), c(0xe9e4da), c(0xdfe3e6));
        [wwMin, wwMax, whMin, whMax] = [12, 15, 15, 19];
        break;
      case NYC:
        material = r.nextFloat() < 0.6 ? BRICK : r.nextFloat() < 0.5 ? STONE : STUCCO;
        wall = material === BRICK ? pick(r, c(0xa8452f), c(0x8a3a2a), c(0xb5684a), c(0x7a3326), c(0xc08a5e), c(0x9a5a3e), c(0x6e4a3a))
          : material === STONE ? pick(r, c(0x8a6a50), c(0x7a5a42), c(0xc9bba0), c(0xb7a88d)) : pick(r, c(0xd8d0c0), c(0xc9c2b5));
        [wwMin, wwMax, whMin, whMax] = [11, 15, 13, 17];
        break;
      case CHICAGO:
        material = r.nextFloat() < 0.55 ? BRICK : STONE;
        wall = material === BRICK ? pick(r, c(0x9a4a35), c(0xb3794f), c(0x7d3e2e), c(0xa88760), c(0x6f5544))
          : pick(r, c(0xa9a59b), c(0x8f8b82), c(0xc2bdb1));
        [wwMin, wwMax, whMin, whMax] = [12, 18, 13, 16];
        break;
      case TOKYO:
        material = r.nextFloat() < 0.5 ? TILE : CONCRETE;
        wall = pick(r, c(0xf0eee8), c(0xe2ded6), c(0xc9c6c0), c(0xdcd4c4), c(0xd2dde3), c(0xe8d8d6), c(0xb8b4ae), c(0xece4cf), c(0xa8aeb4));
        [wwMin, wwMax, whMin, whMax] = [16, 24, 9, 12];
        break;
      case TAIPEI:
        material = r.nextFloat() < 0.4 ? TILE : CONCRETE;
        wall = pick(r, c(0xc9c4b8), c(0xb5b1a8), c(0xd8d2c4), c(0xa9a8a2), c(0xc4b9a4), c(0xd6cfc8), c(0xb9c0c2), c(0x9ea39b));
        [wwMin, wwMax, whMin, whMax] = [13, 20, 11, 13];
        break;
      case SF:
        material = r.nextFloat() < 0.75 ? SIDING : STUCCO;
        wall = pick(r, c(0xf7b7c4), c(0xb4e3cc), c(0xd3c0f2), c(0xfbe39a), c(0xa9d1f7), c(0xf9cba0), c(0xe9f2b0), c(0xf2f2f2),
          c(0xc7e8f0), c(0xf0a8a0), c(0x9fd8c0));
        [wwMin, wwMax, whMin, whMax] = [11, 13, 15, 19];
        break;
      default:
        material = r.nextInt(6);
        wall = pick(r, c(0xd8d0c0), c(0xb8a890), c(0xa8452f), c(0xc9c6c0), c(0xe8e2d4), c(0x9a9a9a));
        [wwMin, wwMax, whMin, whMax] = [11, 17, 12, 16];
        break;
    }
    wall = jitter(wall, r, 0.05);
    const dark = mul(wall, 0.84), light = mul(wall, 1.07), shadow = mul(wall, 0.62);
    b.stone = family === SF ? c(0xfafafa) : material === BRICK ? pick(r, c(0xe6dfd0), c(0xd9cfbd), c(0xcfc6b4)) : mul(wall, r.nextBoolean() ? 1.1 : 0.86);
    const stoneDark = mul(b.stone, 0.78);
    fillMaterial(a, material, wall, dark, light, r);

    // window settings for this building
    const ribbon = family === TOKYO && r.nextFloat() < 0.35;
    const bays = ribbon ? 1 : r.nextFloat() < 0.15 ? 2 : 4;
    const bw = PW / bays;
    const ww = bays === 2 ? 26 + r.nextInt(19) : ribbon ? PW : wwMin + r.nextInt(wwMax - wwMin + 1);
    const wh = whMin + r.nextInt(whMax - whMin + 1);
    const top = Math.max(2, Math.trunc((STP - wh) / 2) - 1);
    const arch = (family === PARIS || family === LONDON || family === NYC || family === CHICAGO) && !ribbon && r.nextFloat() < 0.3;
    const paneCols = ribbon ? 16 : ww >= 20 ? 3 : ww >= 12 ? (r.nextBoolean() ? 2 : 1) : 1;
    const paneRows = wh >= 16 ? (r.nextBoolean() ? 3 : 2) : wh >= 11 ? 2 : 1;
    const lintel = r.nextFloat() < 0.75, keystone = lintel && r.nextFloat() < 0.4, sill = r.nextFloat() < 0.8;
    const shutters = (family === PARIS || family === SF || family === GENERIC) && bays === 4 && ww <= 15 && r.nextFloat() < 0.3;
    const caged = family === TAIPEI && r.nextFloat() < 0.6;
    const fireEscape = family === NYC && material !== STUCCO && bays >= 2 && r.nextFloat() < 0.55;
    const balconyAll = (family === TAIPEI || family === TOKYO) && r.nextFloat() < 0.45;
    const balconyA = family === PARIS ? 1 : -1, balconyB = family === PARIS && r.nextBoolean() ? 4 : -1;
    const flowers = (family === PARIS || family === LONDON) && r.nextFloat() < 0.3;
    const band = r.nextFloat() < 0.55, dentils = band && (family === PARIS || family === NYC || family === LONDON) && r.nextBoolean();
    const curtains = r.nextFloat() < 0.4;
    const frame = family === LONDON || family === SF ? c(0xf6f4ee)
      : pick(r, c(0x1e1a18), c(0xf6f4ee), mul(wall, 0.5), c(0x3a3330), c(0x4a5a4a), c(0x2b3a4a));
    const glass = pick(r, c(0x2f3e55), c(0x26313f), c(0x354a5e), c(0x2b3a4a), c(0x3a4a58), c(0x2e4048));
    const glassLight = mul(glass, 1.45), glint = c(0xa9c8e8), lit = c(0xffd36b), litDeep = c(0xe8a848);
    const curtain = pick(r, c(0xe8dcc0), c(0xb8463a), c(0x6a7a9a), c(0xd8d0e8), c(0x8a9a6a));
    const shutter = pick(r, c(0x3f6f5a), c(0x47607a), c(0x7a4a3a), c(0x5a6b7a), c(0xd9d0b8), c(0x6a8a5a));
    const rail = family === TAIPEI ? c(0xb8bcc0) : c(0x18181a);
    const neonBay = (family === TOKYO || family === TAIPEI) && r.nextFloat() < 0.45 ? r.nextInt(Math.max(1, bays)) : -1;
    const neon = pick(r, c(0xff3d7f), c(0x33e6ff), c(0xffd23f), c(0x7dff4a), c(0xff7a26), c(0xc46bff));

    // storey s = 0 is the first floor above the shops; the picture runs up from the ground floor
    for (let s = 0; GROUND - (s + 1) * STP >= 0; s++) {
      const y0 = GROUND - (s + 1) * STP;
      if (band) {
        a.rect(0, y0, PW, 2, b.stone);
        a.rect(0, y0 + 2, PW, 1, stoneDark);
        if (dentils) for (let x = 0; x < PW; x += 3) a.px(x, y0 + 1, stoneDark);
      }
      for (let k = 0; k < bays; k++) {
        const x0 = k * bw + Math.trunc((bw - ww) / 2);
        const wy = y0 + top + (band ? 1 : 0);
        const hh = wh;
        const on = r.nextInt(7) === 0;
        window(a, x0, wy, ww, hh, frame, glass, glassLight, glint, on ? lit : null, litDeep, paneCols, paneRows, arch,
          curtains && !on && r.nextBoolean() ? curtain : null, wall);
        if (lintel) {
          a.rect(x0 - 2, wy - 2, ww + 4, 2, b.stone);
          a.rect(x0 - 2, wy - 2, ww + 4, 1, mul(b.stone, 1.05));
          if (keystone) {
            a.rect(x0 + (ww >> 1) - 2, wy - 3, 4, 3, b.stone);
            a.rect(x0 + (ww >> 1) - 2, wy - 3, 1, 3, stoneDark);
          }
        }
        if (sill) {
          a.rect(x0 - 2, wy + hh, ww + 4, 1, b.stone);
          a.rect(x0 - 1, wy + hh + 1, ww + 2, 1, shadow);
        }
        if (shutters) {
          for (let side = 0; side < 2; side++) {
            const sx = side === 0 ? x0 - 4 : x0 + ww + 1;
            a.rect(sx, wy, 3, hh, shutter);
            for (let y = wy + 1; y < wy + hh; y += 2) a.rect(sx, y, 3, 1, mul(shutter, 0.75));
          }
        }
        if (caged) {
          for (let cx = x0 - 1; cx <= x0 + ww; cx += 3) a.rect(cx, wy - 1, 1, hh + 2, c(0x8a8f96));
          for (let cy = wy - 1; cy <= wy + hh; cy += 4) a.rect(x0 - 1, cy, ww + 2, 1, c(0x8a8f96));
        }
        if (flowers && r.nextBoolean()) {
          a.rect(x0 - 1, wy + hh + 1, ww + 2, 2, c(0x5a3a2a));
          for (let fx = x0 - 1; fx < x0 + ww + 1; fx++) {
            a.px(fx, wy + hh, fx % 2 === 0 ? c(0x3f8f3a) : pick(r, c(0xd9433b), c(0xf2c84b), c(0xf08ab0), c(0xffffff)));
          }
        }
        if ((family === TAIPEI || family === TOKYO) && r.nextInt(3) === 0) acUnit(a, x0 + ww - 6, wy + hh + 2);
        if (family === TAIPEI && r.nextInt(4) === 0) {
          // laundry on a line
          a.rect(x0 - 1, wy + hh + 2, ww + 2, 1, c(0x3a3a3a));
          for (let lx = x0; lx < x0 + ww - 2; lx += 3) {
            if (r.nextBoolean()) a.rect(lx, wy + hh + 3, 2, 3, pick(r, c(0xf2f2f2), c(0x3f6fd0), c(0xd9433b), c(0xf2c84b), c(0x6cb84b), c(0xf08ab0)));
          }
        }
      }
      if (balconyAll || s === balconyA || s === balconyB) balcony(a, y0 + STP - 1, rail, wall, family === TAIPEI || family === TOKYO);
      if (fireEscape) drawFireEscape(a, bw + (bw >> 1) - 12, y0, s);
      if (neonBay >= 0) {
        const nx = neonBay * bw + bw - 10;
        a.rect(nx, y0, 8, STP, c(0x1a1a1e));
        a.rect(nx + 1, y0, 6, STP, neon);
        for (let g = 0; g < 2; g++) glyph(a, nx + 2, y0 + 2 + g * 8, mul(neon, 0.35), r);
      }
    }
    this.groundFloor(a, b, r, wall, frame, glass, glint, lit, neon);
    return a.done();
  }

  /** the ground floor: a shop, a front door with its fanlight and steps, a garage, or an arcade */
  private groundFloor(a: Art, b: Building, r: Random, wall: Rgb, frame: Rgb, glass: Rgb, glint: Rgb, lit: Rgb, neon: Rgb): void {
    const gy = GROUND, family = this.family;
    if (b.shop) {
      // the awning: stripes with a scalloped edge
      const stripe = family === PARIS || family === LONDON ? mul(b.awning, 1.35) : pick(r, mul(b.awning, 1.35), c(0xf2f2f2));
      for (let x = 0; x < PW; x++) {
        const col = (x >> 2) % 2 === 0 ? b.awning : stripe;
        a.rect(x, gy, 1, 5, col);
        if (x % 4 !== 3) a.px(x, gy + 5, col);
      }
      a.rect(0, gy, PW, 1, mul(b.awning, 0.6));
      // the sign, with letters
      const sign = pick(r, c(0x1e1a18), mul(b.awning, 0.7), c(0xf2f0e8), c(0x2b3a6b));
      const letters = sign[0] + sign[1] + sign[2] > 2 ? c(0x1e1a18) : family === TOKYO || family === TAIPEI ? neon : c(0xf2c84b);
      a.rect(0, gy + 6, PW, 5, sign);
      for (let x = 8; x < PW - 8; x += 5) {
        if (r.nextInt(5) > 0) {
          for (let y2 = 0; y2 < 3; y2++) for (let x2 = 0; x2 < 3; x2++) if (r.nextInt(3) > 0) a.px(x + x2, gy + 7 + y2, letters);
        }
      }
      // shop windows with goods in them, and the door
      a.rect(0, gy + 11, PW, 12, frame);
      const display = r.nextBoolean() ? glass : mul(lit, 0.9);
      for (let k = 0; k < 4; k++) {
        const x0 = k * BAYP;
        if (k === 1) {
          a.rect(x0 + 10, gy + 11, 12, 13, mul(b.awning, 0.55));
          a.rect(x0 + 12, gy + 13, 8, 5, glass);
          a.px(x0 + 19, gy + 19, c(0xf2c84b));
        } else {
          a.rect(x0 + 2, gy + 12, BAYP - 4, 10, display);
          a.rect(x0 + BAYP / 2, gy + 12, 1, 10, frame);
          for (let d = 0; d < 3; d++) a.px(x0 + 3 + d, gy + 15 - d, glint);
          for (let g = x0 + 4; g < x0 + BAYP - 4; g += 3) {
            if (r.nextBoolean()) a.rect(g, gy + 19, 2, 3, pick(r, c(0xd9433b), c(0xf2c84b), c(0x3f6fd0), c(0xf2f2f2), c(0x6cb84b)));
          }
        }
      }
      a.rect(0, gy + 23, PW, 1, mul(wall, 0.5));
    } else if (family === SF && r.nextBoolean()) {
      // a garage door under the house
      a.rect(6, gy + 6, 48, 18, c(0xf2f0ea));
      for (let y = gy + 9; y < gy + 24; y += 3) a.rect(6, y, 48, 1, c(0xcfccc4));
      a.rect(8, gy + 7, 44, 2, c(0x3a4a58));
      for (let x = 8; x < 52; x += 11) a.rect(x, gy + 7, 1, 2, c(0xf2f0ea));
      door(a, 80, gy + 2, mul(wall, 0.5), c(0xfafafa), glass, false);
    } else if (family === TAIPEI) {
      // an arcade: dark, with pillars and lit shop signs
      a.rect(0, gy, PW, STP, c(0x26262a));
      for (let x = 0; x < PW; x += BAYP) {
        a.rect(x, gy, 5, STP, wall);
        a.rect(x + 4, gy, 1, STP, mul(wall, 0.7));
      }
      for (let k = 0; k < 4; k++) {
        a.rect(k * BAYP + 9, gy + 4, 16, 4, pick(r, neon, c(0xf2c84b), c(0xf2f2f2), c(0xd9433b)));
        a.px(k * BAYP + 16, gy + 1, c(0xffe8a0));
      }
    } else {
      a.rect(0, gy + STP - 1, PW, 1, mul(wall, 0.55));
      const doorColour = pick(r, c(0x1e1a18), c(0x8c2b2b), c(0x1f3b6b), c(0x2b5c3a), c(0x5a3a2a), c(0xc9792b), c(0x3f8f8f));
      door(a, 58, gy + 2, doorColour, b.stone, glass, family === LONDON || family === PARIS || family === NYC);
      for (const k of [0, 3]) window(a, k * BAYP + 8, gy + 4, 16, 15, c(0xf6f4ee), glass, mul(glass, 1.45), glint, null, null, 2, 2, false, null, wall);
    }
  }

  /** a skyscraper's skin: a glass curtain wall, or Art Deco stone piers with recessed windows */
  private towerFacade(b: Building, r: Random): THREE.Texture {
    const a = new Art();
    if (b.deco) {
      const stone = pick(r, c(0xe8e0cc), c(0xd8cfb8), c(0xcfc6b0), c(0xbfb6a0));
      const glass = pick(r, c(0x2b3a4a), c(0x33302a), c(0x26313f));
      const spandrel = pick(r, c(0x5a4a3a), c(0x3a3a3e), c(0x6b5a40));
      b.stone = mul(stone, 1.05);
      for (let y = 0; y < PH; y++) {
        for (let x = 0; x < PW; x++) {
          const cx = x % 16, cy = y % STP;
          let px: Rgb;
          if (cx < 5) px = cx === 0 ? mul(stone, 1.08) : cx === 4 ? mul(stone, 0.82) : stone; // the piers
          else if (cy >= STP - 6) px = (cx + cy) % 4 === 0 ? mul(spandrel, 1.4) : spandrel; // zigzag spandrels
          else {
            px = cy < 4 ? mul(glass, 1.4) : glass;
            if (cx === 5 || cx === 15) px = mul(stone, 0.6);
          }
          a.px(x, y, px);
        }
      }
      for (let y = GROUND - STP; y >= 0; y -= STP) for (let x = 5; x < PW; x += 16) if (r.nextInt(7) === 0) a.rect(x + 1, y + 4, 9, STP - 10, c(0xffd36b));
    } else {
      const glass = pick(r, c(0x3f6f9a), c(0x2f7f7f), c(0x4a7a5a), c(0x8a6a40), c(0x9aa4ae), c(0x2a3038), c(0x5a8ab8), c(0x6a5a8a));
      const mull = pick(r, c(0xd8dde2), c(0xf2f2f2), c(0x1e1e22), c(0xb08a50), c(0x8a9096));
      const spandrel = r.nextBoolean() ? mul(glass, 0.65) : mull;
      const every = pick(r, 4, 8, 8, 16);
      const spandrelRows = pick(r, 1, 3, 4);
      b.stone = mull;
      for (let y = 0; y < PH; y++) {
        for (let x = 0; x < PW; x++) {
          const row = mod(y - GROUND, STP);
          let px: Rgb;
          if (x % every === 0) px = mull;
          else if (row >= STP - spandrelRows) px = spandrel;
          else {
            px = mul(glass, 1.2 - (row / STP) * 0.35);
            // a sky reflection sweeping diagonally across the whole face
            if (mod(x + y, 64) < 6) px = mul(glass, 1.55);
          }
          a.px(x, y, px);
        }
      }
      for (let y = GROUND - STP; y >= 0; y -= STP) {
        for (let x = 0; x < PW; x += every) if (r.nextInt(9) === 0) a.rect(x + 1, y + 1, every - 1, STP - spandrelRows - 1, c(0xffd36b));
      }
    }
    // the lobby: tall glass, doors and a canopy
    const gy = GROUND;
    a.rect(0, gy, PW, STP, c(0x1e2228));
    for (let x = 0; x < PW; x += 16) a.rect(x, gy, 2, STP, b.stone);
    a.rect(0, gy, PW, 3, b.stone);
    a.rect(48, gy + 8, 32, 16, c(0x3a5068));
    a.rect(63, gy + 8, 2, 16, b.stone);
    a.rect(40, gy + 4, 48, 3, c(0x2a2a2e));
    return a.done();
  }

  // ---------------------------------------------------------------- building the shape

  private build(id: number, b: Building): void {
    const h = b.height, family = this.family;
    const r = new Random(BigInt(b.seed) * 31n + 7n);
    const turret = family === SF && !b.tall && r.nextFloat() < 0.4;
    let turretDone = false;
    for (const [gx, gy] of b.cells) {
      const x0 = gx * CELL, x1 = x0 + CELL, y1 = this.worldH - gy * CELL, y0 = y1 - CELL;
      const n = [[gx + 1, gy], [gx - 1, gy], [gx, gy - 1], [gx, gy + 1]];
      const ends: E[] = [[x1, y0, x1, y1], [x0, y1, x0, y0], [x1, y1, x0, y1], [x0, y0, x1, y0]];
      const normal = [[1, 0], [-1, 0], [0, 1], [0, -1]];
      const shades = [0.93, 0.72, 1, 0.64];
      for (let s = 0; s < 4; s++) {
        const low = this.lowAt(n[s][0], n[s][1]);
        if (low >= h) continue;
        const e = ends[s], [ox, oy] = normal[s], sh = shades[s];
        this.facadeWall(b.fb, e[0], e[1], e[2], e[3], low, h, [sh, sh, sh]);
        if (b.cornice === 0) {
          this.moulding(e, ox, oy, h - 2.4, h, 1.6 + (b.seed & 3) * 0.3, b.stone, sh, true);
          this.moulding(e, ox, oy, h - 3, h - 2.4, 0.9, c(0x231c18), 1, false);
        } else if (b.cornice === 1) this.moulding(e, ox, oy, h - 1, h, 0.6, b.stone, sh, true);
        else this.moulding(e, ox, oy, h, h + 2.6, 0.5, b.stone, sh, true); // a parapet above the roofline
        if (low === 0) {
          this.moulding(e, ox, oy, 0, 1.6, 0.4, mul(b.stone, 0.6), sh, true);
          if (b.shop && !b.tall) this.awning(e, ox, oy, b.awning, sh);
        }
        // iron balconies as real ledges on Paris facades (matching the drawn ones)
        if (family === PARIS && !b.tall) {
          for (let floor = 1; floor <= 4; floor += 3) {
            const z = (floor + 1) * STOREY + 0.4;
            if (z > low + 2 && z < h - 4) this.moulding(e, ox, oy, z, z + 0.5, 1.2, c(0x1c1c1e), sh, true);
          }
        }
        if (b.mansard) {
          this.slope(e, ox, oy, h, b.slopeRise, b.slopeInset, b.roofTone, sh);
          if (family === PARIS && mod(gx * 3 + gy * 5 + s, 2) === 0) this.dormer(e, ox, oy, h, b.slopeInset);
        }
        const seed = gx * 31 + gy * 17 + s;
        if (family === SF && !b.tall && mod(seed, 2) === 0 && h > 26) this.bayWindow(b.fb, e, ox, oy, low, h);
        if ((family === TOKYO || family === TAIPEI) && low === 0 && mod(seed * 7 + b.seed, 7) === 0) this.neonSign(e, ox, oy, h, seed + b.seed);
      }
      // a corner turret with a pointed cap (San Francisco)
      if (turret && !turretDone && this.lowAt(gx + 1, gy) < h && this.lowAt(gx, gy + 1) < h) {
        turretDone = true;
        const tx = x1 - 1, ty = y0 + 1;
        this.prism(tx, ty, 10, h + 4, 5, 5, 8, mul(b.stone, 0.95));
        this.prism(tx, ty, h + 4, h + 14, 5.8, 0.4, 8, b.roofTone);
      }
    }
    this.roofFeatures(id, b, b.mansard ? h + b.slopeRise : h, r);
  }

  // ---------------------------------------------------------------- the city carrying on

  /**
   * blocks of the same city all round the map, out into the haze, so it never just stops:
   * streets in a grid, a pavement round each block, and a few buildings on it (sharing a
   * dozen facades), lower near the map and taller further out
   */
  private outskirts(mapW: number, mapH: number): void {
    const r = new Random(BigInt(hashCode(this.city)) * 7n + 1n), family = this.family;
    const variants = 12;
    const fill: Builder[] = [], stones: Rgb[] = [];
    for (let i = 0; i < variants; i++) {
      const d = { seed: r.nextInt() } as Building;
      d.rnd = new Random(d.seed);
      const tower = i >= 8 && family !== PARIS;
      d.tall = tower;
      d.deco = tower && (family === NYC || family === CHICAGO) && i % 2 === 0;
      d.shop = !tower && r.nextFloat() < 0.6;
      d.awning = pick(r, c(0x8c2b2b), c(0x2b5c3a), c(0x1f3b6b), c(0x6b2b4e), c(0xc9792b));
      fill.push(this.facadeBuilder(tower ? this.towerFacade(d, d.rnd) : this.facade(d, d.rnd)));
      stones.push(d.stone);
    }
    const step = 140, street = 26, margin = 2000;
    const asphalt = c(0x45464c), pavement = c(0xa9a8a3);
    for (let bx = -margin; bx < mapW + margin; bx += step) {
      for (let by = -margin; by < mapH + margin; by += step) {
        if (bx + step > -40 && bx < mapW + 40 && by + step > -40 && by < mapH + 40) continue; // the map itself
        const dist = Math.max(Math.max(-bx - step, bx - mapW), Math.max(-by - step, by - mapH));
        // lifted a touch: these big flat pieces bow less than the hills under them
        this.quad(bx, by, 1.2, bx + step, by, 1.2, bx + step, by + step, 1.2, bx, by + step, 1.2, asphalt);
        const x0 = bx + street / 2, y0 = by + street / 2, x1 = bx + step - street / 2, y1 = by + step - street / 2;
        this.quad(x0, y0, 1.5, x1, y0, 1.5, x1, y1, 1.5, x0, y1, 1.5, pavement);
        // one building, two side by side, or four, set back a little from the kerb
        const split = dist > 1100 ? 1 : r.nextInt(3) === 0 ? 1 : r.nextBoolean() ? 2 : 4;
        const inset = 4;
        for (let k = 0; k < split; k++) {
          let ax = x0 + inset, ay = y0 + inset, cx = x1 - inset, cy = y1 - inset;
          if (split === 2) {
            const mid = (ax + cx) / 2;
            if (k === 0) cx = mid - 1;
            else ax = mid + 1;
          } else if (split === 4) {
            const mx = (ax + cx) / 2, my = (ay + cy) / 2;
            if (k % 2 === 0) cx = mx - 1;
            else ax = mx + 1;
            if (k < 2) cy = my - 1;
            else ay = my + 1;
          }
          const towerHere = family !== PARIS && r.nextFloat() < 0.12 + Math.min(0.35, dist / 4000);
          const v = towerHere ? 8 + r.nextInt(4) : r.nextInt(8);
          const storeys = towerHere ? 6 + r.nextInt(5) : 2 + r.nextInt(4);
          let h = Math.min(TILE_H - 2, storeys * STOREY + 2);
          if (towerHere && storeys > 9) h = 9 * STOREY + 2;
          this.fillBlock(fill[v], ax, ay, cx, cy, h, stones[v], r);
          if (towerHere && r.nextBoolean()) {
            // a taller part rising out of the middle
            const sx = (cx - ax) * 0.2, sy = (cy - ay) * 0.2;
            this.fillBlock(fill[v], ax + sx, ay + sy, cx - sx, cy - sy, h + 24 + r.nextInt(3) * 12, stones[v], r);
          }
        }
      }
    }
  }

  /** one outskirts building: four walls of its facade, a cornice, a roof and maybe a little plant on it */
  private fillBlock(fb: Builder, x0: number, y0: number, x1: number, y1: number, h: number, stone: Rgb, r: Random): void {
    const sh = [0.93, 0.72, 1, 0.64];
    const e: E[] = [[x1, y0, x1, y1], [x0, y1, x0, y0], [x1, y1, x0, y1], [x0, y0, x1, y0]];
    const n = [[1, 0], [-1, 0], [0, 1], [0, -1]];
    for (let s = 0; s < 4; s++) {
      this.facadeWall(fb, e[s][0], e[s][1], e[s][2], e[s][3], 0, h, [sh[s], sh[s], sh[s]]);
      this.moulding(e[s], n[s][0], n[s][1], h - 1.4, h, 1, stone, sh[s], true);
    }
    this.quad(x0, y0, h, x1, y0, h, x1, y1, h, x0, y1, h, mul(stone, 0.7));
    if (r.nextInt(3) === 0) {
      const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
      this.box(cx - 3, cy - 3, cx + 3, cy + 3, h, h + 4, c(0x9ea2a8));
    }
  }

  private lowAt(x: number, y: number): number {
    const gw = this.height.length, gh = this.height[0].length;
    return x >= 0 && y >= 0 && x < gw && y < gh ? this.height[x][y] : 0;
  }

  /** a wall of the building's own facade; storeys counted up from the street, windows aligned along it */
  private facadeWall(fb: Builder, ax: number, ay: number, bx: number, by: number, z0: number, z1: number, k: Rgb): void {
    const ua = (ax === bx ? ay : ax) / TILE_W, ub = (ax === bx ? by : bx) / TILE_W;
    // the picture's ground floor is at its bottom (v = 0 with the canvas flipped)
    const va = z0 / TILE_H, vb = Math.min(z1, TILE_H - 0.01) / TILE_H;
    fb.quad([ax, ay, z0], [bx, by, z0], [bx, by, z1], [ax, ay, z1], k, [ua, va, ub, va, ub, vb, ua, vb]);
  }

  private moulding(e: E, ox: number, oy: number, z0: number, z1: number, out: number, k: Rgb, sh: number, top: boolean): void {
    const dx = e[2] - e[0], dy = e[3] - e[1], len = Math.hypot(dx, dy);
    const tx = (dx / len) * out, ty = (dy / len) * out;
    const ax = e[0] - tx, ay = e[1] - ty, bx = e[2] + tx, by = e[3] + ty;
    const px = ox * out, py = oy * out;
    const front = mul(k, sh), under = mul(k, 0.45);
    this.quad(ax + px, ay + py, z0, bx + px, by + py, z0, bx + px, by + py, z1, ax + px, ay + py, z1, front);
    if (top) this.quad(ax, ay, z1, bx, by, z1, bx + px, by + py, z1, ax + px, ay + py, z1, k);
    this.quad(ax, ay, z0, ax + px, ay + py, z0, bx + px, by + py, z0, bx, by, z0, under);
    this.quad(ax, ay, z0, ax + px, ay + py, z0, ax + px, ay + py, z1, ax, ay, z1, under);
    this.quad(bx, by, z0, bx + px, by + py, z0, bx + px, by + py, z1, bx, by, z1, under);
  }

  /** a shop awning: a sloping canopy over the ground floor */
  private awning(e: E, ox: number, oy: number, k: Rgb, sh: number): void {
    const out = 3.2, col = mul(k, 0.8 + 0.2 * sh);
    this.quad(e[0], e[1], 10.5, e[2], e[3], 10.5, e[2] + ox * out, e[3] + oy * out, 8.6, e[0] + ox * out, e[1] + oy * out, 8.6, col);
    this.quad(e[0] + ox * out, e[1] + oy * out, 7.6, e[2] + ox * out, e[3] + oy * out, 7.6, e[2] + ox * out, e[3] + oy * out, 8.6,
      e[0] + ox * out, e[1] + oy * out, 8.6, mul(k, 0.6));
  }

  private slope(e: E, ox: number, oy: number, h: number, rise: number, inset: number, k: Rgb, sh: number): void {
    const ix = -ox * inset, iy = -oy * inset;
    this.quad(e[0], e[1], h, e[2], e[3], h, e[2] + ix, e[3] + iy, h + rise, e[0] + ix, e[1] + iy, h + rise, mul(k, 0.75 + 0.25 * sh));
  }

  private dormer(e: E, ox: number, oy: number, h: number, inset: number): void {
    const mx = (e[0] + e[2]) / 2, my = (e[1] + e[3]) / 2;
    const tx = (e[2] - e[0]) / 12, ty = (e[3] - e[1]) / 12;
    const bx = mx - ox * inset * 0.45, by = my - oy * inset * 0.45;
    const w = 2.6, g = 0.05;
    this.quad(bx - tx * w, by - ty * w, h + 2, bx + tx * w, by + ty * w, h + 2, bx + tx * w, by + ty * w, h + 7.5, bx - tx * w, by - ty * w, h + 7.5, c(0xf0e6d0));
    const w6 = w * 0.6;
    this.quad(bx - tx * w6 + ox * g, by - ty * w6 + oy * g, h + 3, bx + tx * w6 + ox * g, by + ty * w6 + oy * g, h + 3,
      bx + tx * w6 + ox * g, by + ty * w6 + oy * g, h + 6.5, bx - tx * w6 + ox * g, by - ty * w6 + oy * g, h + 6.5, c(0x2f3e55));
    this.quad(bx - tx * w * 1.2, by - ty * w * 1.2, h + 7.5, bx + tx * w * 1.2, by + ty * w * 1.2, h + 7.5,
      bx - ox * 3, by - oy * 3, h + 9.5, bx - ox * 3, by - oy * 3, h + 9.5, c(0x6f7784));
  }

  private bayWindow(fb: Builder, e: E, ox: number, oy: number, low: number, h: number): void {
    const mx = (e[0] + e[2]) / 2, my = (e[1] + e[3]) / 2;
    const tx = ((e[2] - e[0]) / 12) * 3.6, ty = ((e[3] - e[1]) / 12) * 3.6, out = 3;
    const z0 = Math.max(low, 0) + STOREY, z1 = h - 3;
    const ax = mx - tx + ox * out, ay = my - ty + oy * out, bx = mx + tx + ox * out, by = my + ty + oy * out;
    this.facadeWall(fb, ax, ay, bx, by, z0, z1, [1, 1, 1]);
    this.facadeWall(fb, mx - tx, my - ty, ax, ay, z0, z1, [0.8, 0.8, 0.8]);
    this.facadeWall(fb, bx, by, mx + tx, my + ty, z0, z1, [0.8, 0.8, 0.8]);
    const trim = c(0xfafafa);
    this.quad(mx - tx, my - ty, z1, mx + tx, my + ty, z1, bx, by, z1, ax, ay, z1, trim);
    this.quad(mx - tx, my - ty, z0, ax, ay, z0, bx, by, z0, mx + tx, my + ty, z0, mul(trim, 0.6));
  }

  private neonSign(e: E, ox: number, oy: number, h: number, seed: number): void {
    const neon = [c(0xff3d7f), c(0x33e6ff), c(0xffd23f), c(0x7dff4a), c(0xff7a26), c(0xc46bff)];
    const col = neon[mod(seed, neon.length)];
    const mx = (e[0] + e[2]) / 2, my = (e[1] + e[3]) / 2;
    const z0 = 13, z1 = Math.min(h - 3, 13 + 14 + mod(seed, 4) * 6);
    this.boxFree(mx + ox, my + oy, mx + ox * 6, my + oy * 6, 0.9, z0, z1, col);
    this.boxFree(mx + ox * 0.5, my + oy * 0.5, mx + ox * 6.4, my + oy * 6.4, 1.1, z1, z1 + 0.8, c(0x1e1a1a));
  }

  // ---------------------------------------------------------------- roofs

  private roofFeatures(id: number, b: Building, z: number, r: Random): void {
    let centre = b.cells[0], best = -1;
    for (const cell of b.cells) {
      const d = this.depth(id, cell[0], cell[1]);
      if (d > best) {
        best = d;
        centre = cell;
      }
    }
    const at = (cell: [number, number]): [number, number] => [cell[0] * CELL + CELL / 2, this.worldH - cell[1] * CELL - CELL / 2];
    const [cx, cy] = at(centre);
    if (b.tall && !b.mansard && best >= 1 && this.family !== PARIS) {
      this.crown(b, cx, cy, z, best, r);
      return;
    }
    const [ox, oy] = at(b.cells[r.nextInt(b.cells.length)]);
    const roll = r.nextFloat();
    switch (this.family) {
      case PARIS:
        for (let k = 0; k < 1 + Math.trunc(b.cells.length / 4); k++) this.chimney(...at(b.cells[r.nextInt(b.cells.length)]), z, c(0xd9cdb4), r);
        break;
      case LONDON:
        for (let k = 0; k < 1 + Math.trunc(b.cells.length / 3); k++) {
          const cell = b.cells[r.nextInt(b.cells.length)];
          this.chimney(...at(cell), z, pick(r, c(0x9c5a42), c(0xb89a6e), c(0x8e4a38)), r);
        }
        if (!b.mansard && roll < 0.3) this.plant(cx, cy, z, r);
        break;
      case NYC:
      case CHICAGO:
        if (b.cells.length >= 3 && roll < 0.6) this.waterTower(cx, cy, z, r);
        else if (roll < 0.75) this.billboard(ox, oy, z, r);
        else if (roll < 0.9) this.garden(cx, cy, z, r);
        else this.plant(cx, cy, z, r);
        if (r.nextBoolean()) this.plant(ox, oy, z, r);
        break;
      case TOKYO:
        this.plant(cx, cy, z, r);
        if (roll < 0.4) this.billboard(ox, oy, z, r);
        else if (roll < 0.6) this.waterTank(ox, oy, z);
        else if (roll < 0.75) this.solar(cx, cy, z, r);
        break;
      case TAIPEI:
        this.waterTank(cx - 3, cy, z);
        if (r.nextBoolean()) this.waterTank(ox + 3, oy + 2, z);
        if (roll < 0.4) this.shack(ox, oy, z, r);
        else if (roll < 0.6) this.garden(cx, cy, z, r);
        break;
      case SF:
        if (roll < 0.4) this.chimney(ox, oy, z, c(0x9c5a42), r);
        else if (roll < 0.6) this.solar(cx, cy, z, r);
        else if (roll < 0.75) this.garden(cx, cy, z, r);
        break;
      default:
        if (roll < 0.5) this.plant(cx, cy, z, r);
        else if (roll < 0.7) this.solar(cx, cy, z, r);
        break;
    }
  }

  private depth(id: number, x: number, y: number): number {
    let d = 0;
    while (d < 6) {
      const k = d + 1;
      const own = (px: number, py: number) => this.own(id, px, py);
      if (own(x + k, y) && own(x - k, y) && own(x, y + k) && own(x, y - k) && own(x + k, y + k) && own(x - k, y - k) && own(x + k, y - k) && own(x - k, y + k)) d = k;
      else break;
    }
    return d;
  }

  private own(id: number, x: number, y: number): boolean {
    const gw = this.height.length, gh = this.height[0].length;
    return x >= 0 && y >= 0 && x < gw && y < gh && this.owner[x * gh + y] === id;
  }

  /** a tower's top: stepped tiers and a spire, a dome, antennas, a glass lantern or a helipad */
  private crown(b: Building, cx: number, cy: number, z: number, depth: number, r: Random): void {
    let half = (depth + 0.5) * CELL * 0.85;
    const body = b.deco ? c(0xe0d8c4) : jitter(b.stone, r, 0.08);
    const kind = r.nextInt(5);
    let tier = z;
    const tiers = kind === 4 ? 0 : 1 + r.nextInt(3);
    for (let k = 0; k < tiers; k++) {
      const hh = 10 + r.nextInt(8);
      this.box(cx - half, cy - half, cx + half, cy + half, tier, tier + hh, k % 2 === 0 ? body : mul(body, 0.9));
      this.box(cx - half - 0.15, cy - half - 0.15, cx + half + 0.15, cy + half + 0.15, tier + hh * 0.3, tier + hh * 0.6, b.deco ? c(0x2b3a4a) : c(0x3a4a5e));
      this.box(cx - half - 0.8, cy - half - 0.8, cx + half + 0.8, cy + half + 0.8, tier + hh, tier + hh + 1.2, b.stone);
      tier += hh + 1.2;
      half *= 0.62 + r.nextFloat() * 0.12;
      if (half < 3) break;
    }
    switch (kind) {
      case 0:
        this.pyramid(cx, cy, tier, tier + 20 + r.nextInt(16), Math.max(2.5, half), 0.3, b.deco ? c(0xd8dce2) : b.stone);
        break;
      case 1: {
        // a dome: stacked rings shrinking to a point, copper green or gold
        const dome = r.nextBoolean() ? c(0x6fae96) : c(0xd8b04a);
        const rad = Math.max(3, half * 0.9);
        for (let k = 0; k < 5; k++) {
          const a0 = k * 18 * RAD, a1 = (k + 1) * 18 * RAD;
          this.prism(cx, cy, tier + Math.sin(a0) * rad, tier + Math.sin(a1) * rad, Math.cos(a0) * rad, Math.cos(a1) * rad, 8, dome);
        }
        this.box(cx - 0.4, cy - 0.4, cx + 0.4, cy + 0.4, tier + rad, tier + rad + 8, dome);
        break;
      }
      case 2:
        this.box(cx - 3, cy - 0.6, cx - 1.8, cy + 0.6, tier, tier + 26 + r.nextInt(10), c(0xe8e8e8));
        this.box(cx + 1.8, cy - 0.6, cx + 3, cy + 0.6, tier, tier + 18 + r.nextInt(10), c(0xe8e8e8));
        break;
      case 3: {
        // a lit glass lantern on top
        const l = Math.max(3, half * 0.7);
        this.box(cx - l, cy - l, cx + l, cy + l, tier, tier + 8, c(0xffd36b));
        this.box(cx - l - 0.6, cy - l - 0.6, cx + l + 0.6, cy + l + 0.6, tier + 8, tier + 9, b.stone);
        break;
      }
      default: {
        // a helipad: a dark slab with its white H
        this.box(cx - half, cy - half, cx + half, cy + half, tier, tier + 1, c(0x3a3a3e));
        const hs = half * 0.4;
        this.box(cx - hs, cy - hs, cx - hs * 0.6, cy + hs, tier + 1, tier + 1.1, c(0xf2f2f2));
        this.box(cx + hs * 0.6, cy - hs, cx + hs, cy + hs, tier + 1, tier + 1.1, c(0xf2f2f2));
        this.box(cx - hs, cy - hs * 0.2, cx + hs, cy + hs * 0.2, tier + 1, tier + 1.1, c(0xf2f2f2));
        break;
      }
    }
  }

  private waterTower(cx: number, cy: number, z: number, r: Random): void {
    const wood = pick(r, c(0x8c5e3c), c(0x7a5236), c(0x9a6a44), c(0x6a4a34)), iron = c(0x29231f);
    const legs = 5 + r.nextInt(3), rad = 4.2 + r.nextFloat() * 1.4, tall = 8 + r.nextInt(4);
    for (const sx of [-1, 1]) {
      for (const sy of [-1, 1]) {
        const lx = cx + sx * rad * 0.7, ly = cy + sy * rad * 0.7;
        this.box(lx - 0.5, ly - 0.5, lx + 0.5, ly + 0.5, z, z + legs, iron);
      }
    }
    this.prism(cx, cy, z + legs, z + legs + tall, rad, rad, 8, wood);
    this.prism(cx, cy, z + legs + tall * 0.25, z + legs + tall * 0.25 + 0.7, rad + 0.15, rad + 0.15, 8, iron);
    this.prism(cx, cy, z + legs + tall * 0.7, z + legs + tall * 0.7 + 0.7, rad + 0.15, rad + 0.15, 8, iron);
    this.prism(cx, cy, z + legs + tall, z + legs + tall + 4, rad + 0.6, 0.6, 8, c(0x4a3e36));
  }

  private waterTank(cx: number, cy: number, z: number): void {
    this.box(cx - 3, cy - 3, cx + 3, cy + 3, z, z + 2, c(0x4d4d52));
    this.prism(cx, cy, z + 2, z + 9, 3, 3, 8, c(0xc9ced4));
    this.prism(cx, cy, z + 9, z + 10, 3, 1.5, 8, c(0xa6abb2));
  }

  private chimney(cx: number, cy: number, z: number, brick: Rgb, r: Random): void {
    const w = 1.2 + r.nextFloat(), l = 2 + r.nextFloat() * 2, t = 4 + r.nextInt(4);
    this.box(cx - w, cy - l, cx + w, cy + l, z - 2, z + t, brick);
    this.box(cx - w - 0.4, cy - l - 0.4, cx + w + 0.4, cy + l + 0.4, z + t, z + t + 0.8, c(0xddd6c8));
    for (let k = -l + 0.9; k < l; k += 1.8) this.box(cx - 0.6, cy + k - 0.6, cx + 0.6, cy + k + 0.6, z + t + 0.8, z + t + 2.6, c(0xb8603e));
  }

  private plant(cx: number, cy: number, z: number, r: Random): void {
    const grey = pick(r, c(0x9ea2a8), c(0x8a8e94), c(0xb4b8bc));
    const w = 2 + r.nextInt(3), l = 2 + r.nextInt(3);
    this.box(cx - w, cy - l, cx + w, cy + l, z, z + 4 + r.nextInt(3), grey);
    this.box(cx + w + 1, cy - 1.5, cx + w + 4, cy + 1.5, z, z + 2.4, c(0xd8dade));
    if (r.nextBoolean()) {
      this.box(cx - 0.4, cy - 0.4, cx + 0.4, cy + 0.4, z + 4, z + 14 + r.nextInt(6), c(0x46464c));
      this.box(cx - 2.5, cy - 0.2, cx + 2.5, cy + 0.2, z + 11, z + 11.5, c(0x46464c));
    }
  }

  private billboard(cx: number, cy: number, z: number, r: Random): void {
    const panel = pick(r, c(0xd9433b), c(0xf2c84b), c(0x3f6fd0), c(0x2f9f6f), c(0xf08ab0), c(0xf2f2f2), c(0x7a4aa8));
    const alongX = r.nextBoolean();
    const w = 6 + r.nextInt(4);
    const x0 = alongX ? cx - w : cx - 0.4, x1 = alongX ? cx + w : cx + 0.4;
    const y0 = alongX ? cy - 0.4 : cy - w, y1 = alongX ? cy + 0.4 : cy + w;
    this.box(cx - 0.4, cy - 0.4, cx + 0.4, cy + 0.4, z, z + 6, c(0x2a2a2e));
    this.box(x0 - 0.3, y0 - 0.3, x1 + 0.3, y1 + 0.3, z + 5, z + 13, c(0xf2f2f2));
    this.box(x0, y0, x1, y1, z + 5.6, z + 12.4, panel);
    this.box(alongX ? cx - w * 0.5 : x0 - 0.05, alongX ? y0 - 0.05 : cy - w * 0.5, alongX ? cx + w * 0.3 : x1 + 0.05,
      alongX ? y1 + 0.05 : cy + w * 0.3, z + 8, z + 9.4, mul(panel, 0.4));
  }

  private garden(cx: number, cy: number, z: number, r: Random): void {
    this.box(cx - 5, cy - 5, cx + 5, cy + 5, z, z + 0.8, c(0x5aa83f));
    for (let k = 0; k < 2 + r.nextInt(3); k++) {
      const tx = cx - 3 + r.nextFloat() * 6, ty = cy - 3 + r.nextFloat() * 6;
      this.prism(tx, ty, z + 0.8, z + 5 + r.nextInt(3), 1.8, 0.6, 6, pick(r, c(0x2f7f2e), c(0x3f8f3a), c(0x4a9a3e)));
    }
  }

  private solar(cx: number, cy: number, z: number, r: Random): void {
    for (let k = 0; k < 2 + r.nextInt(2); k++) {
      const y = cy - 4 + k * 3.2;
      this.quad(cx - 5, y, z + 0.6, cx + 5, y, z + 0.6, cx + 5, y + 2.4, z + 2, cx - 5, y + 2.4, z + 2, c(0x24345a));
      this.quad(cx - 5, y + 2.4, z, cx + 5, y + 2.4, z, cx + 5, y + 2.4, z + 2, cx - 5, y + 2.4, z + 2, c(0x55575c));
    }
  }

  private shack(cx: number, cy: number, z: number, r: Random): void {
    this.box(cx - 3, cy - 2.5, cx + 3, cy + 2.5, z, z + 4.5, pick(r, c(0xb8b4ae), c(0x9a8a7a), c(0x8aa0a8)));
    this.quad(cx - 3.4, cy - 2.9, z + 4.5, cx + 3.4, cy - 2.9, z + 5.2, cx + 3.4, cy + 2.9, z + 5.2, cx - 3.4, cy + 2.9, z + 4.5, c(0x5a6a7a));
  }

  // ---------------------------------------------------------------- shapes

  private quad(x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, x3: number, y3: number, z3: number,
    x4: number, y4: number, z4: number, k: Rgb): void {
    this.solid.quad([x1, y1, z1], [x2, y2, z2], [x3, y3, z3], [x4, y4, z4], k);
  }

  private box(x0: number, y0: number, x1: number, y1: number, z0: number, z1: number, k: Rgb): void {
    this.solid.box(x0, y0, z0, x1, y1, z1, k);
  }

  private boxFree(ax: number, ay: number, bx: number, by: number, half: number, z0: number, z1: number, k: Rgb): void {
    const dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
    const nx = (-dy / len) * half, ny = (dx / len) * half;
    const p = [[ax + nx, ay + ny], [bx + nx, by + ny], [bx - nx, by - ny], [ax - nx, ay - ny]];
    for (let i = 0; i < 4; i++) {
      const a = p[i], b2 = p[(i + 1) % 4];
      this.quad(a[0], a[1], z0, b2[0], b2[1], z0, b2[0], b2[1], z1, a[0], a[1], z1, mul(k, 0.75 + 0.08 * i));
    }
    this.quad(p[0][0], p[0][1], z1, p[1][0], p[1][1], z1, p[2][0], p[2][1], z1, p[3][0], p[3][1], z1, k);
  }

  private prism(cx: number, cy: number, z0: number, z1: number, r0: number, r1: number, n: number, k: Rgb): void {
    const cos = (d: number) => Math.cos(d * RAD), sin = (d: number) => Math.sin(d * RAD);
    for (let i = 0; i < n; i++) {
      const a = (i * 360) / n, b2 = ((i + 1) * 360) / n;
      const f = 0.65 + 0.35 * (0.5 + 0.5 * cos((a + b2) / 2 - 40));
      this.quad(cx + cos(a) * r0, cy + sin(a) * r0, z0, cx + cos(b2) * r0, cy + sin(b2) * r0, z0,
        cx + cos(b2) * r1, cy + sin(b2) * r1, z1, cx + cos(a) * r1, cy + sin(a) * r1, z1, mul(k, f));
    }
    if (r1 > 0.8) {
      for (let i = 0; i < n; i += 2) {
        const a = (i * 360) / n, b2 = ((i + 1) * 360) / n, c2 = ((i + 2) * 360) / n;
        this.quad(cx, cy, z1, cx + cos(a) * r1, cy + sin(a) * r1, z1, cx + cos(b2) * r1, cy + sin(b2) * r1, z1, cx + cos(c2) * r1, cy + sin(c2) * r1, z1, k);
      }
    }
  }

  private pyramid(cx: number, cy: number, z0: number, z1: number, r0: number, r1: number, k: Rgb): void {
    const sides = [[1, -1, 1, 1, 0.9], [-1, 1, -1, -1, 0.7], [1, 1, -1, 1, 0.82], [-1, -1, 1, -1, 0.6]];
    for (const s of sides) {
      this.quad(cx + s[0] * r0, cy + s[1] * r0, z0, cx + s[2] * r0, cy + s[3] * r0, z0, cx + s[2] * r1, cy + s[3] * r1, z1,
        cx + s[0] * r1, cy + s[1] * r1, z1, mul(k, s[4]));
    }
  }

  // ---------------------------------------------------------------- for the scenery

  private at(gx: number, gy: number): Building | null {
    const gw = this.height.length, gh = this.height[0].length;
    if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) return null;
    const o = this.owner[gx * gh + gy];
    return o >= 0 ? this.buildings[o] : null;
  }

  builds(gx: number, gy: number): boolean {
    return this.at(gx, gy) !== null;
  }

  /** the roof's own picture sits this much higher on a mansard building (0 = flat) */
  roofLift(gx: number, gy: number): number {
    const b = this.at(gx, gy);
    return b?.mansard ? b.slopeRise : 0;
  }

  /** how far the roof picture is pulled in from a side that drops (for the mansard slope) */
  roofInset(gx: number, gy: number): number {
    const b = this.at(gx, gy);
    return b?.mansard ? b.slopeInset : 0;
  }
}

// ---------------------------------------------------------------- drawing helpers

/** fill the whole picture with a material's pattern */
function fillMaterial(a: Art, material: number, wall: Rgb, dark: Rgb, light: Rgb, r: Random): void {
  for (let y = 0; y < PH; y++) {
    for (let x = 0; x < PW; x++) {
      let px = wall;
      switch (material) {
        case BRICK: {
          const course = Math.trunc(y / 3);
          const brick = Math.trunc((x + (course % 2) * 3) / 6);
          if (y % 3 === 2 || (x + (course % 2) * 3) % 6 === 0) px = light; // mortar
          else if (mod(brick * 7 + course * 13, 5) === 0) px = dark; // a darker brick here and there
          break;
        }
        case STONE: {
          const row = Math.trunc(y / 6);
          if (y % 6 === 5 || (x + (row % 2) * 6) % 12 === 0) px = dark;
          else if (y % 6 === 0) px = light;
          break;
        }
        case SIDING:
          px = y % 3 === 2 ? dark : y % 3 === 0 ? light : wall;
          break;
        case TILE:
          px = x % 3 === 0 || y % 3 === 0 ? light : wall;
          break;
        case CONCRETE:
          px = x % 24 === 23 || y % 18 === 17 ? dark : r.nextInt(14) === 0 ? dark : wall;
          break;
        default:
          px = r.nextInt(30) === 0 ? dark : wall;
          break;
      }
      a.px(x, y, px);
    }
  }
}

/** a window: frame, panes divided by glazing bars, glass shading and a glint (or lit), arched if asked */
function window(a: Art, x0: number, y0: number, w: number, h: number, frame: Rgb, glass: Rgb, glassLight: Rgb, glint: Rgb,
  lit: Rgb | null, litDeep: Rgb | null, cols: number, rows: number, arch: boolean, curtain: Rgb | null, wall: Rgb): void {
  a.rect(x0, y0, w, h, frame);
  for (let y = y0 + 1; y < y0 + h - 1; y++) {
    const t = (y - y0) / h;
    a.rect(x0 + 1, y, w - 2, 1, lit ? (t < 0.5 ? lit : litDeep!) : t < 0.3 ? glassLight : glass);
  }
  if (!lit) for (let d = 0; d < 3; d++) a.px(x0 + 2 + d, y0 + 4 - d, glint);
  if (curtain) {
    a.rect(x0 + 1, y0 + 1, 2, h - 2, curtain);
    a.rect(x0 + w - 3, y0 + 1, 2, h - 2, curtain);
  }
  for (let i = 1; i < cols; i++) a.rect(x0 + Math.trunc((i * w) / cols), y0, 1, h, frame);
  for (let i = 1; i < rows; i++) a.rect(x0, y0 + Math.trunc((i * h) / rows), w, 1, frame);
  if (arch && w >= 6) {
    // round the top corners off into an arch, wall showing outside it
    const rad = w / 2;
    for (let y = 0; y < Math.trunc(rad); y++) {
      for (let x = 0; x < w; x++) {
        const dx = x + 0.5 - rad, dy = rad - y - 0.5;
        if (dx * dx + dy * dy > rad * rad) a.px(x0 + x, y0 + y, wall);
      }
    }
  }
}

function acUnit(a: Art, x: number, y: number): void {
  a.rect(x, y, 6, 4, c(0xe4e6e8));
  a.rect(x, y + 3, 6, 1, c(0xa8acb2));
  for (let gx = x + 1; gx < x + 5; gx += 2) a.rect(gx, y + 1, 1, 2, c(0x8a8f96));
  a.px(x + 4, y + 4, c(0x6a6e74));
  a.px(x + 4, y + 6, c(0x6a6e74));
}

/** a balcony across a storey: a slab, a top rail, balusters (or a solid parapet) */
function balcony(a: Art, y: number, rail: Rgb, wall: Rgb, solid: boolean): void {
  a.rect(0, y, PW, 1, mul(wall, 0.7));
  if (solid) {
    a.rect(0, y - 4, PW, 4, mul(wall, 0.92));
    a.rect(0, y - 4, PW, 1, mul(wall, 1.08));
    return;
  }
  a.rect(0, y - 5, PW, 1, rail);
  for (let x = 0; x < PW; x += 2) a.rect(x, y - 4, 1, 4, rail);
}

/** one storey of a New York fire escape: platform, railing, and the stair down */
function drawFireEscape(a: Art, x: number, y0: number, s: number): void {
  const iron = c(0x141414);
  const py = y0 + STP - 2;
  a.rect(x, py, 26, 2, iron);
  a.rect(x, py - 6, 26, 1, iron);
  for (let k = x; k <= x + 25; k += 4) a.rect(k, py - 6, 1, 6, iron);
  const right = s % 2 === 0;
  for (let d = 0; d < STP - 2; d++) {
    const sx = right ? x + 4 + d : x + 21 - d;
    a.px(sx, py - d, iron);
    a.px(sx, py - d - 1, iron);
    if (d % 3 === 0) a.rect(sx - 1, py - d, 3, 1, iron);
  }
}

/** a tiny made-up sign character: a few pixels in a 4 x 5 box */
function glyph(a: Art, x: number, y: number, k: Rgb, r: Random): void {
  for (let gy = 0; gy < 5; gy++) for (let gx = 0; gx < 4; gx++) if (r.nextInt(5) < 2 || gx === 0 || gy === 2) a.px(x + gx, y + gy, k);
}

/** a front door: panelled, with a fanlight over it if asked, a stone surround and steps */
function door(a: Art, x: number, gy: number, colour: Rgb, stone: Rgb, glass: Rgb, fanlight: boolean): void {
  a.rect(x - 2, gy + 2, 16, 16, stone);
  a.rect(x, gy + 5, 12, 13, colour);
  for (const [px, py] of [[2, 7], [7, 7], [2, 12], [7, 12]]) a.rect(x + px, gy + py, 3, 4, mul(colour, 0.75));
  a.px(x + 10, gy + 11, c(0xf2c84b));
  if (fanlight) {
    a.rect(x, gy + 3, 12, 2, glass);
    for (let k = x + 1; k < x + 12; k += 2) a.px(k, gy + 3, stone);
  }
  a.rect(x - 3, gy + 17, 18, 1, mul(stone, 0.8));
}
