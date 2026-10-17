// Finds what's painted on the Classic map's grass besides trees, so the first-person view can
// stand it up: the rock piles and the little animals. Grass and leaves are green and tree
// trunks brown; anything else off the road is something, and its size says what.
//
// Run: node tools/classic-props.mjs  -> web/src/render/classicProps.ts
// (picture pixels: x right, y down; the map is the picture at full size)
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import sharp from 'sharp';

const root = decodeURIComponent(new URL('..', import.meta.url).pathname);
const dir = join(root, 'assets/maps/classic');
const img = await sharp(join(dir, 'background.png')).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
const { width: W, height: H } = img.info, d = img.data;
const road = await sharp(join(dir, 'road_mask.png')).greyscale().resize(W, H, { kernel: 'nearest' }).raw().toBuffer();

// near the road (the kerbs and their paint) doesn't count
const NEAR = 16;
const nearRoad = new Uint8Array(W * H);
for (let y = 0; y < H; y++) {
  for (let x = 0; x < W; x++) {
    if (road[y * W + x] <= 128) continue;
    for (let dy = -NEAR; dy <= NEAR; dy += 4) for (let dx = -NEAR; dx <= NEAR; dx += 4) {
      const nx = x + dx, ny = y + dy;
      if (nx >= 0 && ny >= 0 && nx < W && ny < H) nearRoad[ny * W + nx] = 1;
    }
  }
}
const something = new Uint8Array(W * H);
for (let i = 0; i < W * H; i++) {
  const r = d[i * 4], g = d[i * 4 + 1], b = d[i * 4 + 2];
  const green = g > r + 6 && g > b + 6, brown = r > g && g > b && r - b > 40 && r < 160;
  something[i] = !nearRoad[i] && !green && !brown && r + g + b > 120 ? 1 : 0;
}
const seen = new Uint8Array(W * H), rocks = [], critters = [];
for (let s = 0; s < W * H; s++) {
  if (!something[s] || seen[s]) continue;
  const q = [s];
  seen[s] = 1;
  let n = 0, x0 = W, y0 = H, x1 = 0, y1 = 0, sr = 0, sg = 0, sb = 0;
  while (q.length) {
    const i = q.pop(), x = i % W, y = (i / W) | 0;
    n++;
    x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
    sr += d[i * 4]; sg += d[i * 4 + 1]; sb += d[i * 4 + 2];
    for (const j of [i - 1, i + 1, i - W, i + W, i - W - 1, i - W + 1, i + W - 1, i + W + 1]) {
      if (j >= 0 && j < W * H && something[j] && !seen[j]) {
        seen[j] = 1;
        q.push(j);
      }
    }
  }
  const [r, g, b] = [sr / n, sg / n, sb / n];
  if (n < 100 || b > r) continue; // flowers and mushrooms; the pond and shadows
  const box = [x0, y0, x1 - x0 + 1, y1 - y0 + 1];
  // the rock piles: big and grey-beige; everything smaller is a critter (often on a stump)
  if (n >= 1500 && Math.abs(r - g) < 25) rocks.push(box);
  else if (n < 1500 && box[2] < 60 && box[3] < 60) critters.push(box);
}
const list = (a) => a.map((b) => `[${b.join(', ')}]`).join(', ');
await writeFile(join(root, 'web/src/render/classicProps.ts'), `// Made by tools/classic-props.mjs from the Classic map picture: where its rock piles and
// animals are painted, as [x, y, width, height] boxes in picture pixels (y down).
export const ROCKS: [number, number, number, number][] = [${list(rocks)}];
export const CRITTERS: [number, number, number, number][] = [${list(critters)}];
`);
console.log(`${rocks.length} rock piles, ${critters.length} critters`);
