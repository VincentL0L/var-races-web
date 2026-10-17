// Pictures drawn in code: the window facade and tree for the scenery, the kerb-striped
// barrier and the chevron boards for the track walls, and a soft round glow.
import * as THREE from 'three';

function canvasTexture(w: number, h: number, draw: (g: CanvasRenderingContext2D) => void, pixelArt = true): THREE.CanvasTexture {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const g = canvas.getContext('2d')!;
  draw(g);
  const t = new THREE.CanvasTexture(canvas);
  t.userData.sceneOwned = true;
  if (pixelArt) {
    t.magFilter = THREE.NearestFilter;
    t.minFilter = THREE.NearestFilter;
    t.generateMipmaps = false;
  }
  return t;
}

const css = (r: number, g: number, b: number, a = 1) => `rgba(${Math.round(r * 255)},${Math.round(g * 255)},${Math.round(b * 255)},${a})`;

/** a small seeded random number generator, so the pictures are the same every time */
export function seeded(seed: number): () => number {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return (s >>> 0) / 4294967296;
  };
}

/** 4 x 4 window bays: white wall, glass with a sky reflection, some windows lit warm */
export function facade(): THREE.Texture {
  const t = canvasTexture(64, 64, (g) => {
    g.fillStyle = css(0.93, 0.93, 0.93);
    g.fillRect(0, 0, 64, 64);
    const rnd = seeded(7);
    for (let bx = 0; bx < 4; bx++) {
      for (let by = 0; by < 4; by++) {
        const x = bx * 16 + 3, y = by * 16 + 3;
        const lit = rnd() < 0.2;
        for (let yy = 0; yy < 9; yy++) {
          const t = yy / 9;
          g.fillStyle = lit
            ? css(1, 0.86 - t * 0.1, 0.55 - t * 0.1)
            : css(0.32 + (1 - t) * 0.25, 0.42 + (1 - t) * 0.25, 0.55 + (1 - t) * 0.22);
          g.fillRect(x, y + yy, 10, 1);
        }
        g.strokeStyle = css(0.45, 0.45, 0.47);
        g.lineWidth = 1;
        g.strokeRect(x - 0.5, y - 0.5, 11, 10);
        g.fillStyle = css(0.45, 0.45, 0.47);
        g.fillRect(x + 5, y, 1, 9);
        g.fillStyle = css(0.75, 0.75, 0.77);
        g.fillRect(x - 2, y + 11, 14, 1);
        g.fillStyle = css(0.8, 0.8, 0.8);
        g.fillRect(bx * 16, by * 16 + 15, 16, 1);
      }
    }
  });
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  return t;
}

/** a pixel-art tree, light and shade only (it's tinted by the leaves' colour) */
export function tree(): THREE.Texture {
  return canvasTexture(32, 48, (g) => {
    for (let y = 30; y < 48; y++) {
      for (let x = 14; x < 18; x++) {
        const s = x < 16 ? 0.42 : 0.3;
        g.fillStyle = css(s * 1.1, s * 0.75, s * 0.5);
        g.fillRect(x, y, 1, 1);
      }
    }
    const blobs = [[16, 18, 13], [9, 22, 8], [23, 22, 8], [16, 10, 9], [11, 13, 7], [21, 13, 7], [16, 27, 8]];
    const rnd = seeded(3);
    for (let y = 0; y < 36; y++) {
      for (let x = 0; x < 32; x++) {
        let best = 99;
        for (const [bx, by, br] of blobs) best = Math.min(best, Math.hypot(x + 0.5 - bx, y + 0.5 - by) / br);
        if (best > 1) continue;
        let light = 0.95 - (y / 36) * 0.45 - (x / 32) * 0.15 + (rnd() - 0.5) * 0.18;
        if (best > 0.85) light *= 0.7;
        light = Math.max(0.25, Math.min(1, light));
        g.fillStyle = css(light, light, light);
        g.fillRect(x, y, 1, 1);
      }
    }
  });
}

/** a concrete barrier: red and white kerb blocks along the top, pale concrete below */
export function barrier(): THREE.Texture {
  const t = canvasTexture(128, 64, (g) => {
    g.fillStyle = css(0.78, 0.78, 0.76);
    g.fillRect(0, 0, 128, 64);
    for (let y = 40; y < 64; y++) {
      const f = 0.78 - (y - 40) * 0.012;
      g.fillStyle = css(f, f, f * 0.98);
      g.fillRect(0, y, 128, 1);
    }
    g.fillStyle = css(0.6, 0.6, 0.59);
    g.fillRect(63, 14, 1, 50);
    g.fillRect(127, 14, 1, 50);
    for (let k = 0; k < 4; k++) {
      g.fillStyle = k % 2 === 0 ? css(0.85, 0.2, 0.17) : css(0.96, 0.96, 0.94);
      g.fillRect(k * 32, 0, 32, 13);
    }
    g.fillStyle = css(0.45, 0.45, 0.45);
    g.fillRect(0, 13, 128, 1);
  });
  t.wrapS = THREE.RepeatWrapping;
  return t;
}

/** a black board with three yellow chevrons pointing the way the road turns */
export function chevrons(right: boolean): THREE.Texture {
  return canvasTexture(256, 96, (g) => {
    g.fillStyle = css(1, 0.8, 0.1);
    g.fillRect(0, 0, 256, 96);
    g.fillStyle = css(0.025, 0.035, 0.055);
    g.fillRect(5, 5, 246, 86);
    g.fillStyle = css(1, 0.83, 0.12);
    for (let n = 0; n < 3; n++) {
      let a = 22 + n * 80, b = a + 17, c = a + 48, d = a + 31;
      if (!right) [a, b, c, d] = [255 - a, 255 - b, 255 - c, 255 - d];
      g.beginPath();
      g.moveTo(a, 14);
      g.lineTo(b, 14);
      g.lineTo(c, 48);
      g.lineTo(b, 82);
      g.lineTo(a, 82);
      g.lineTo(d, 48);
      g.closePath();
      g.fill();
    }
  }, false);
}
