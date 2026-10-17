// Paints the Monaco street circuit: a tight, twisting lap through a city by the sea, with
// barriers right at the track's edge. Start / finish on the harbour boulevard, up the hill
// through Sainte Devote and Beau Rivage, Massenet and Casino Square, down past Mirabeau to the
// Grand Hotel hairpin, Portier, the long curve of the tunnel, the Nouvelle chicane, Tabac, the
// Swimming Pool chicanes and Rascasse. The harbour full of yachts lies below the lap, and the
// Monte Carlo blocks crowd every gap.
//
// Run: node tools/monaco.mjs
//   -> assets/maps/monaco/{background,road_mask,preview,heights}.png, and prints the
//      waypoints for crates/sim/src/maps.rs
//
// Like every circuit it starts at (200, 300) heading up, with the grid on the straight below.
// Painted at 1/3 size and scaled up 3x; coordinates are world (y up). heights.png is written
// here too (the painter knows where every building and tree is): red is the height, green the
// kind (1 tree, 2 building), one pixel per 12 x 12 patch.
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import sharp from 'sharp';

const root = decodeURIComponent(new URL('..', import.meta.url).pathname);
const S = 3, W = 2880, H = 3456, AW = W / S, AH = H / S, CELL = 12;
/** half the track's width; the barrier and pavement beyond it */
const HALF = 44, BARRIER = HALF + 5, PAVE = HALF + 26;

/**
 * the circuit, in the order you drive it (a closed loop), traced from a map of the real one
 * (picture pixels, y down, 1200 x 1380) and fitted into the world. The bottom loop (Rascasse
 * and Anthony Noghes) is tucked in by hand, since the grid has to sit at (200, 300).
 */
const fit = ([ix, iy]) => [200 + (ix - 150) * 2.6, 300 + (1180 - iy) * 2.6];
const TRACED = [
  [140, 1000], [130, 925], [128, 850], [125, 780], [135, 700], [150, 660], // the start straight
  [175, 630], [230, 620], // Sainte Devote
  [330, 605], [440, 575], [530, 555], [600, 525], [700, 500], [790, 485], // up Beau Rivage
  [835, 450], [850, 400], [840, 345], [800, 300], [780, 265], [800, 220], [850, 155], [890, 95], // Massenet
  [925, 50], [952, 30], [972, 48], [972, 85], // Casino Square
  [980, 118], [1000, 150], [1035, 175], [1068, 158], [1066, 125], [1052, 98], [1040, 72], // Mirabeau and the hairpin
  [1045, 50], [1070, 38], [1110, 30], [1132, 55], // Portier
  [1125, 120], [1110, 250], [1080, 320], [1050, 390], [1020, 420], // the tunnel
  [950, 470], [850, 520], [780, 550], [700, 575], [600, 595], // out along the harbour
  [585, 615], [565, 640], [530, 640], // the Nouvelle chicane
  [450, 648], [350, 662], [265, 685], // Tabac
  [222, 718], [200, 760], [185, 800], [190, 860], [225, 900], [240, 960], [250, 1020], [240, 1060], [225, 1090], [230, 1130], // the Swimming Pool
];
const CONTROL = [
  [200, 150], [200, 450],
  ...TRACED.map(fit),
  [470, 300], [560, 200], [600, 120], [540, 70], [430, 70], // Rascasse
  [300, 80], [225, 105], // Anthony Noghes
];
const TUNNEL = [[2560, 2200], [2860, 2980]]; // the box the tunnel runs through

// ---------------------------------------------------------------- randomness

let seed = 1234567;
const rnd = () => ((seed = (Math.imul(seed, 1103515245) + 12345) >>> 0) / 4294967296);
const rint = (n) => Math.floor(rnd() * n);

// ---------------------------------------------------------------- the line

/** a closed Catmull-Rom spline through the points, sampled every `step` world units */
function spline(p, step) {
  const out = [];
  const n = p.length;
  for (let i = 0; i < n; i++) {
    const p0 = p[(i - 1 + n) % n], p1 = p[i], p2 = p[(i + 1) % n], p3 = p[(i + 2) % n];
    const k = Math.max(2, Math.floor(Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / step));
    for (let j = 0; j < k; j++) {
      const t = j / k, t2 = t * t, t3 = t2 * t;
      const f = (a, b, c, d) => 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
      out.push([f(p0[0], p1[0], p2[0], p3[0]), f(p0[1], p1[1], p2[1], p3[1]), 0]);
    }
  }
  return out;
}

const line = spline(CONTROL, 6);
// how sharply the line turns at each sample (smoothed), + for left
{
  const n = line.length, span = 6, raw = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const a = line[(i - span + n) % n], b = line[i], c = line[(i + span) % n];
    let d = Math.atan2(c[1] - b[1], c[0] - b[0]) - Math.atan2(b[1] - a[1], b[0] - a[0]);
    while (d > Math.PI) d -= 2 * Math.PI;
    while (d < -Math.PI) d += 2 * Math.PI;
    raw[i] = d / Math.max(1, Math.hypot(b[0] - a[0], b[1] - a[1]) + Math.hypot(c[0] - b[0], c[1] - b[1]));
  }
  for (let i = 0; i < n; i++) {
    let s = 0;
    for (let k = -8; k <= 8; k++) s += raw[(i + k + n) % n];
    line[i][2] = s / 17;
  }
}

// line segments bucketed on a coarse grid, so each lookup only checks nearby ones
const BUCKET = 100, bw = Math.ceil(W / BUCKET) + 1, bh = Math.ceil(H / BUCKET) + 1;
const buckets = Array.from({ length: bw * bh }, () => []);
line.forEach((a, i) => {
  const cx = Math.floor(a[0] / BUCKET), cy = Math.floor(a[1] / BUCKET);
  if (cx >= 0 && cy >= 0 && cx < bw && cy < bh) buckets[cy * bw + cx].push(i);
});
/** the last lookup: sample index, which side (+1 left of travel), distance along the line */
const nearest = [0, 1, 0];
function distance(x, y) {
  const cx = Math.floor(x / BUCKET), cy = Math.floor(y / BUCKET), n = line.length;
  let best = Infinity, bi = 0, side = 1;
  for (let gy = cy - 2; gy <= cy + 2; gy++) {
    for (let gx = cx - 2; gx <= cx + 2; gx++) {
      if (gx < 0 || gy < 0 || gx >= bw || gy >= bh) continue;
      for (const i of buckets[gy * bw + gx]) {
        const a = line[i], b = line[(i + 1) % n];
        const dx = b[0] - a[0], dy = b[1] - a[1], l2 = dx * dx + dy * dy;
        const t = l2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / l2));
        const px = a[0] + dx * t - x, py = a[1] + dy * t - y, d = px * px + py * py;
        if (d < best) {
          best = d;
          bi = i;
          side = Math.sign(dx * (y - a[1]) - dy * (x - a[0]));
        }
      }
    }
  }
  if (best === Infinity) return 9999;
  nearest[0] = bi;
  nearest[1] = side;
  nearest[2] = bi * 6;
  return Math.sqrt(best);
}

// ---------------------------------------------------------------- painting

const art = new Uint32Array(AW * AH);
/** which building (or -1 none, -2 a tree) each art pixel belongs to */
const owner = new Int32Array(AW * AH).fill(-1);
const heights = []; // per building id
const wx = (ax) => ax * S + S / 2, wy = (ay) => H - ay * S - S / 2;
function set(x, y, c, who) {
  const ax = Math.floor(x / S), ay = Math.floor((H - y) / S);
  if (ax < 0 || ay < 0 || ax >= AW || ay >= AH) return;
  art[ay * AW + ax] = c;
  if (who !== undefined) owner[ay * AW + ax] = who;
}
/** distance from each art pixel to the track, worked out once */
const dist = new Float32Array(AW * AH), near = new Int32Array(AW * AH), sideOf = new Int8Array(AW * AH);
for (let ay = 0; ay < AH; ay++) {
  for (let ax = 0; ax < AW; ax++) {
    const i = ay * AW + ax;
    dist[i] = distance(wx(ax), wy(ay));
    near[i] = nearest[0];
    sideOf[i] = nearest[1];
  }
}
const distAt = (x, y) => {
  const ax = Math.floor(x / S), ay = Math.floor((H - y) / S);
  return ax < 0 || ay < 0 || ax >= AW || ay >= AH ? 9999 : dist[ay * AW + ax];
};

/** the harbour: a basin inside the lap, with a stone quay round it */
function inHarbour(x, y) {
  const e = ((x - 1500) / 900) ** 2 + ((y - 760) / 520) ** 2;
  return e < 1 || (x > 700 && x < 2400 && y > 200 && y < 1300);
}
function harbourEdge(x, y) {
  for (const [dx, dy] of [[14, 0], [-14, 0], [0, 14], [0, -14]]) if (!inHarbour(x + dx, y + dy)) return true;
  return false;
}

const STREET = 0xb8b3a8, STREET_LINE = 0xa8a398, OUTLINE = 0x2a211c;
const ROOFS = [0xc8643c, 0xd47a4a, 0xb85a36, 0xe8d8b8, 0xf0e2c8, 0xe0c890, 0xd8a878, 0xf2d0c0, 0xc8c0b0, 0x9aa0a8,
  0xe8b8a8, 0xd0d8c8, 0xb07858, 0xf4ead8];
const shadeOf = (c, f) => {
  const r = Math.min(255, Math.round(((c >> 16) & 255) * f)), g = Math.min(255, Math.round(((c >> 8) & 255) * f)), b = Math.min(255, Math.round((c & 255) * f));
  return (r << 16) | (g << 8) | b;
};

// 1. the town's streets and pavements everywhere, the sea along the bottom right
for (let ay = 0; ay < AH; ay++) {
  for (let ax = 0; ax < AW; ax++) {
    const x = wx(ax), y = wy(ay);
    let c = STREET;
    if ((Math.floor(x / 140) + Math.floor(y / 140)) % 2 === 0 && rint(40) === 0) c = STREET_LINE;
    art[ay * AW + ax] = c;
  }
}

// 2. city blocks: a grid of blocks, each split into a few buildings with their own roofs
for (let bx = 0; bx < W; bx += 140) {
  for (let by = 0; by < H; by += 140) {
    const x0 = bx + 12, y0 = by + 12, x1 = bx + 128, y1 = by + 128;
    const parts = 1 + rint(4);
    const cuts = [];
    if (parts === 1) cuts.push([x0, y0, x1, y1]);
    else if (parts === 2) {
      const m = x0 + 40 + rnd() * 36;
      cuts.push([x0, y0, m - 2, y1], [m + 2, y0, x1, y1]);
    } else {
      const mx = x0 + 40 + rnd() * 36, my = y0 + 40 + rnd() * 36;
      cuts.push([x0, y0, mx - 2, my - 2], [mx + 2, y0, x1, my - 2], [x0, my + 2, mx - 2, y1], [mx + 2, my + 2, x1, y1]);
    }
    for (const [a, b, c2, d] of cuts) {
      const id = heights.length;
      heights.push(26 + rint(6) * 9);
      const roof = shadeOf(ROOFS[rint(ROOFS.length)], 0.86 + rnd() * 0.22);
      const tiles = roof === roof && rnd() < 0.5; // a ridged tile roof, or a flat one with a parapet
      for (let x = a; x < c2; x += S) {
        for (let y = b; y < d; y += S) {
          if (distAt(x, y) < PAVE + 6 || inHarbour(x, y)) continue;
          const edge = x < a + S || x >= c2 - S || y < b + S || y >= d - S;
          let col = edge ? OUTLINE : roof;
          if (!edge && tiles && Math.floor((y - b) / S) % 3 === 0) col = shadeOf(roof, 0.85);
          if (!edge && !tiles && (x < a + 2 * S || y >= d - 2 * S)) col = shadeOf(roof, 1.12);
          set(x, y, col, id);
        }
      }
      // a roof terrace, a skylight or a little plant room now and then
      if (rnd() < 0.4) {
        const sx = a + 10 + rnd() * Math.max(1, c2 - a - 30), sy = b + 10 + rnd() * Math.max(1, d - b - 30);
        for (let x = sx; x < sx + 15; x += S) for (let y = sy; y < sy + 12; y += S) if (owner[Math.floor((H - y) / S) * AW + Math.floor(x / S)] === id) set(x, y, rnd() < 0.5 ? 0x8a9aa8 : 0x5c8a4a, id);
      }
    }
  }
}

// 3. the harbour: deep blue water, a stone quay, piers and rows of moored yachts
for (let ay = 0; ay < AH; ay++) {
  for (let ax = 0; ax < AW; ax++) {
    const x = wx(ax), y = wy(ay), i = ay * AW + ax;
    if (!inHarbour(x, y) || dist[i] < PAVE + 6) continue;
    owner[i] = -1;
    art[i] = harbourEdge(x, y) ? 0xd8d0bc : rint(50) === 0 ? 0x5aa8d8 : 0x2f78b8;
  }
}
for (let px = 900; px <= 2200; px += 260) {
  // a pier running out into the basin, yachts moored along both sides
  for (let x = px - 5; x < px + 5; x += S) for (let y = 260; y < 1200; y += S) if (inHarbour(x, y) && distAt(x, y) > PAVE + 6) set(x, y, 0xc8bca0, -1);
  for (let y = 280; y < 1180; y += 34) {
    for (const side of [-1, 1]) {
      if (rnd() < 0.2) continue;
      const len = 40 + rint(4) * 10, x0 = side < 0 ? px - 8 - len : px + 8;
      yacht(x0, y, len, side);
    }
  }
}
function yacht(x0, y0, len, side) {
  const deck = [0xf6f3ea, 0xe8e8ec, 0x1b2a40, 0xf6f3ea][rint(4)];
  for (let x = x0; x < x0 + len; x += S) {
    for (let y = y0; y < y0 + 18; y += S) {
      const t = (x - x0) / len, bow = side < 0 ? t < 0.2 : t > 0.8; // pointed at the open-water end
      const taper = bow ? Math.abs((y - y0 - 9) / 9) > (side < 0 ? t / 0.2 : (1 - t) / 0.2) : false;
      if (taper || !inHarbour(x, y) || distAt(x, y) < PAVE + 6) continue;
      const edge = y < y0 + S || y >= y0 + 18 - S;
      let c = edge ? 0x2a2a30 : deck;
      if (!edge && t > 0.35 && t < 0.7 && y > y0 + 5 && y < y0 + 13) c = 0x7aa8c8; // the cabin's windows
      set(x, y, c, -1);
    }
  }
}

// 4. the Casino and its gardens above the hairpin, a few parks, palms along the quay
function park(x0, y0, x1, y1, count) {
  for (let x = x0; x < x1; x += S) for (let y = y0; y < y1; y += S) if (distAt(x, y) > PAVE + 6) set(x, y, (Math.floor(x / 9) + Math.floor(y / 9)) % 2 ? 0x6cb444 : 0x74bc4a, -1);
  for (let k = 0; k < count; k++) tree(x0 + 12 + rnd() * (x1 - x0 - 24), y0 + 12 + rnd() * (y1 - y0 - 24), 9 + rnd() * 6);
}
function tree(x, y, r) {
  if (distAt(x, y) < PAVE + r + 8 || inHarbour(x, y)) return;
  for (let dx = -r; dx <= r; dx += S) {
    for (let dy = -r; dy <= r; dy += S) {
      const d2 = dx * dx + dy * dy;
      if (d2 < r * r) set(x + dx, y + dy, d2 > (r - 3) * (r - 3) ? 0x1f4f22 : dx < 0 && dy > 0 ? 0x3f8f3a : 0x2f6f2e, -2);
    }
  }
}
park(2150, 2300, 2420, 2560, 20); // the Casino gardens
park(1100, 1980, 1500, 2260, 14);
park(2620, 1500, 2820, 1900, 12);
for (let x = 760; x < 2350; x += 60) tree(x, 1340, 8); // palms along the quay
// the Casino itself: a cream Belle Epoque palace with a green copper roof and two towers
const casino = heights.length;
heights.push(62);
for (let x = 2120; x < 2380; x += S) {
  for (let y = 2840; y < 2960; y += S) {
    if (distAt(x, y) < PAVE + 6) continue;
    const edge = x < 2120 + S || x >= 2380 - S || y < 2840 + S || y >= 2960 - S;
    const tower = (x < 2170 || x > 2330) && y > 2880;
    set(x, y, edge ? OUTLINE : tower ? 0x5f9e88 : (Math.floor((x - 2120) / 12) % 2 ? 0xf2e6c8 : 0xe8d8b0), casino);
  }
}

// 5. the track: barriers right at the edge, kerbs on the corners, the tunnel darker
for (let ay = 0; ay < AH; ay++) {
  for (let ax = 0; ax < AW; ax++) {
    const i = ay * AW + ax, d = dist[i];
    if (d > PAVE + 6) continue;
    const x = wx(ax), y = wy(ay), k = line[near[i]][2];
    const tunnel = x > TUNNEL[0][0] && x < TUNNEL[1][0] && y > TUNNEL[0][1] && y < TUNNEL[1][1];
    const corner = Math.abs(k) > 0.0012;
    let c;
    if (d <= HALF) {
      const n = rint(12);
      c = tunnel ? (n === 0 ? 0x2c2c32 : 0x34343a) : n === 0 ? 0x4a4a50 : n === 1 ? 0x3c3c42 : 0x434349;
      if (d > HALF - 4 && d <= HALF - 1) c = 0xf6f3ea; // the white edge line
      if (corner && d > HALF - 10 && d <= HALF - 4 && Math.sign(k) === sideOf[i]) c = Math.floor(near[i] * 6 / 12) % 2 ? 0xd9433b : 0xf6f3ea; // kerbs, inside
      if (tunnel && d < 3 && Math.floor(near[i] / 4) % 2 === 0) c = 0xf2c84b; // the lights down the middle
    } else if (d <= BARRIER) {
      // the barrier: armco with red and white advertising boards along it
      const block = Math.floor((near[i] * 6) / 30) % 4;
      c = d < HALF + 2 ? 0x9aa0a8 : block === 0 ? 0xd9433b : block === 2 ? 0x1f3b6b : 0xf6f3ea;
    } else {
      c = (Math.floor(x / 9) + Math.floor(y / 9)) % 2 ? 0xd8d4cc : 0xcfcbc2; // the pavement
    }
    art[i] = c;
    owner[i] = -1;
  }
}

// 6. start / finish, the grid boxes, the pit garages and grandstands by the straight
for (let ay = 0; ay < AH; ay++) {
  for (let ax = 0; ax < AW; ax++) {
    const x = wx(ax), y = wy(ay);
    if (Math.abs(x - 200) < HALF - 4 && y >= 300 && y < 318) {
      art[ay * AW + ax] = (Math.floor((x - 136) / 9) + Math.floor((y - 300) / 9)) % 2 === 0 ? 0xf6f3ea : 0x1b1b1f;
    }
  }
}
for (let slot = 0; slot < 12; slot++) {
  const row = Math.floor(slot / 3), col = slot % 3;
  const gx = 165 + col * 36, gy = 276 - row * 30 - (col === 1 ? 12 : 0);
  for (let x = gx - 2; x <= gx + 12; x++) set(x, gy + 22, 0xf6f3ea);
  for (let y = gy + 14; y <= gy + 22; y++) {
    set(gx - 2, y, 0xf6f3ea);
    set(gx + 12, y, 0xf6f3ea);
  }
}
const teams = [0xd9433b, 0x3f6fd0, 0x2f9f8f, 0xf08a2e, 0xe8e8ec, 0x1b1b1f];
const pits = heights.length;
heights.push(26);
for (let x = 262; x < 345; x += S) {
  for (let y = 360; y < 700; y += S) {
    if (distAt(x, y) < BARRIER + 4) continue;
    const edge = x < 262 + S || x >= 345 - S || y < 360 + S || y >= 700 - S;
    let c = edge ? OUTLINE : 0xb7bcc6;
    if (!edge && x < 262 + 14) c = (y - 360) % 40 < 30 ? teams[Math.floor((y - 360) / 40) % teams.length] : 0x8a8f99;
    set(x, y, c, pits);
  }
}
const stand = heights.length;
heights.push(35);
for (let x = 700; x < 1500; x += S) {
  for (let y = 1360; y < 1440; y += S) {
    if (distAt(x, y) < BARRIER + 4 || inHarbour(x, y)) continue;
    const t = (y - 1360) / 80;
    const crowd = [0xd9433b, 0xf2c84b, 0x3f6fd0, 0xf6f3ea, 0x2a2a2e, 0x6cb84b];
    set(x, y, t > 0.86 ? 0xe8e8ec : Math.floor(t * 14) % 2 === 0 ? crowd[rint(crowd.length)] : 0x8a8f99, stand);
  }
}

// ---------------------------------------------------------------- output

const dir = join(root, 'assets/maps/monaco');
await mkdir(dir, { recursive: true });
const rgba = (w, h, f) => {
  const buf = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    const c = f(i);
    buf[i * 4] = (c >> 16) & 255;
    buf[i * 4 + 1] = (c >> 8) & 255;
    buf[i * 4 + 2] = c & 255;
    buf[i * 4 + 3] = 255;
  }
  return sharp(buf, { raw: { width: w, height: h, channels: 4 } });
};
await rgba(AW, AH, (i) => art[i]).png().toFile(join(dir, 'background.png'));
await rgba(AW, AH, (i) => (dist[i] <= HALF + 1 ? 0xffffff : 0)).png().toFile(join(dir, 'road_mask.png'));
await rgba(AW, AH, (i) => art[i]).resize(384, 216, { fit: 'contain', background: '#1c1714', kernel: 'nearest' }).png().toFile(join(dir, 'preview.png'));

// heights: a patch is a building if most of it is one; trees where the leaves are
const gw = Math.ceil(W / CELL), gh = Math.ceil(H / CELL);
const hbuf = Buffer.alloc(gw * gh * 4);
let raised = 0;
for (let gx = 0; gx < gw; gx++) {
  for (let gy = 0; gy < gh; gy++) {
    const count = new Map();
    let n = 0, roadNear = false;
    for (let x = gx * CELL; x < gx * CELL + CELL; x += S) {
      for (let y = gy * CELL; y < gy * CELL + CELL; y += S) {
        // the picture's rows run top down, like the heights' rows
        const ax = Math.floor(x / S), ay = Math.floor(y / S);
        if (ax >= AW || ay >= AH) continue;
        const i = ay * AW + ax;
        if (dist[i] < PAVE) roadNear = true;
        count.set(owner[i], (count.get(owner[i]) ?? 0) + 1);
        n++;
      }
    }
    let best = -1, most = 0;
    for (const [k, v] of count) if (v > most) [best, most] = [k, v];
    let h = 0, kind = 0;
    if (!roadNear && most > n * 0.5) {
      if (best >= 0) [h, kind] = [heights[best], 2];
      else if (best === -2) [h, kind] = [16 + ((gx * 92821) ^ (gy * 68917)) % 9, 1];
    }
    if (h) raised++;
    const o = (gy * gw + gx) * 4;
    hbuf[o] = h;
    hbuf[o + 1] = kind;
    hbuf[o + 3] = 255;
  }
}
await sharp(hbuf, { raw: { width: gw, height: gh, channels: 4 } }).png().toFile(join(dir, 'heights.png'));
console.log(`monaco ${W}x${H}, ${heights.length} buildings, ${raised} raised patches`);

// the waypoints the CPUs follow: the centre line, closer together through the corners,
// starting just past the line and ending on it at (200, 300)
{
  const n = line.length;
  let start = 0, best = Infinity;
  line.forEach((p, i) => {
    const d = Math.hypot(p[0] - 200, p[1] - 300);
    if (d < best) [best, start] = [d, i];
  });
  const out = [];
  let acc = 0;
  for (let k = 1; k <= n; k++) {
    const i = (start + k) % n, p = (start + k - 1) % n;
    acc += Math.hypot(line[i][0] - line[p][0], line[i][1] - line[p][1]);
    const spacing = Math.abs(line[i][2]) > 0.0009 ? 40 : 80;
    if (acc >= spacing && k < n - 4) {
      out.push(`(${Math.round(line[i][0])}.0, ${Math.round(line[i][1])}.0)`);
      acc = 0;
    }
  }
  out.push('(200.0, 300.0)');
  console.log(`waypoints (${out.length}):`);
  console.log(out.join(', '));
}

// different stretches of the lap must stay far enough apart for a wall between them (the
// road's edge, plus the sim's 12 of run-off on each side, plus a wall)
{
  const n = line.length, need = 2 * (HALF + 1 + 12) + 14;
  const close = [];
  for (let i = 0; i < n; i += 2) {
    for (let j = i + 2; j < n; j += 2) {
      const along = Math.min(j - i, n - (j - i)) * 6;
      if (along < need * 2.5) continue;
      const d = Math.hypot(line[i][0] - line[j][0], line[i][1] - line[j][1]);
      if (d < need) {
        const key = `${Math.round(line[i][0] / 150) * 150},${Math.round(line[i][1] / 150) * 150}`;
        const best = close.find((c) => c.key === key);
        if (!best) close.push({ key, d, at: [line[i], line[j]] });
        else if (d < best.d) Object.assign(best, { d, at: [line[i], line[j]] });
      }
    }
  }
  const where = (p) => `(${Math.round(p[0])}, ${Math.round(p[1])})`;
  console.log(close.length ? `too close (need ${need}):\n${close.map((c) => `${Math.round(c.d)} between ${where(c.at[0])} and ${where(c.at[1])}`).join('\n')}`
    : 'every stretch is walled off');
}
