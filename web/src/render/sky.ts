// The perspective sky: a gradient from the haze at the horizon up to the sky colour, a sun
// (the Earth, from the moon), flat-bottomed clouds, and stars in space. It's a dome that
// travels with the camera, so it's always infinitely far away.
import * as THREE from 'three';
import type { Rgb } from './geom';
import { seeded } from './textures';

const RADIUS = 2400;

export interface SkyColors {
  top: Rgb;
  low: Rgb;
  space: boolean;
}

export function skyColors(mapId: string): SkyColors {
  if (mapId === 'moon') return { top: [0.01, 0.01, 0.04], low: [0.07, 0.07, 0.13], space: true };
  if (mapId === 'rooftop') return { top: [0.23, 0.25, 0.55], low: [0.98, 0.66, 0.5], space: false }; // dusk
  return { top: [0.27, 0.52, 0.9], low: [0.74, 0.86, 0.97], space: false };
}

/** a direction on the dome: azimuth (degrees, 0 = +x, counter-clockwise) and elevation */
function onDome(az: number, el: number, r = RADIUS): THREE.Vector3 {
  const a = (az * Math.PI) / 180, e = (el * Math.PI) / 180;
  return new THREE.Vector3(Math.cos(a) * Math.cos(e) * r, Math.sin(a) * Math.cos(e) * r, Math.sin(e) * r);
}

/** a flat square facing the dome's centre, `size` degrees across */
function disc(az: number, el: number, size: number, material: THREE.Material, aspect = 1): THREE.Mesh {
  const span = (size * Math.PI) / 180 * RADIUS * 0.98;
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(span, span * aspect), material);
  mesh.position.copy(onDome(az, el, RADIUS * 0.98));
  mesh.lookAt(0, 0, 0);
  mesh.up.set(0, 0, 1);
  // keep the picture upright: look at the centre with z as up
  const m = new THREE.Matrix4().lookAt(mesh.position, new THREE.Vector3(), new THREE.Vector3(0, 0, 1));
  mesh.quaternion.setFromRotationMatrix(m);
  return mesh;
}

function canvas(w: number, h: number, draw: (g: CanvasRenderingContext2D) => void): THREE.CanvasTexture {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  draw(c.getContext('2d')!);
  const texture = new THREE.CanvasTexture(c);
  texture.userData.sceneOwned = true;
  return texture;
}

const css = (c: Rgb, a = 1) => `rgba(${Math.round(c[0] * 255)},${Math.round(c[1] * 255)},${Math.round(c[2] * 255)},${a})`;

export function buildSky(mapId: string, colors: SkyColors, battle = false): THREE.Group {
  const sky = new THREE.Group();
  sky.renderOrder = -100;
  // the dome: haze at the horizon fading up into the sky colour, haze below
  const dome = new THREE.SphereGeometry(RADIUS, 48, 24);
  const pos = dome.getAttribute('position');
  const cols = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    // the sphere is built with y up: turn it so z is up
    const y = pos.getY(i), z = pos.getZ(i);
    pos.setY(i, -z);
    pos.setZ(i, y);
    const el = Math.asin(Math.max(-1, Math.min(1, y / RADIUS))) * (180 / Math.PI);
    const t = Math.max(0, Math.min(1, el / 25));
    for (let k = 0; k < 3; k++) cols[i * 3 + k] = colors.low[k] + (colors.top[k] - colors.low[k]) * t;
  }
  dome.setAttribute('color', new THREE.BufferAttribute(cols, 3));
  const domeMesh = new THREE.Mesh(dome, new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.BackSide, fog: false, depthWrite: false }));
  domeMesh.renderOrder = -100;
  sky.add(domeMesh);

  const flat = (map: THREE.Texture, opacity = 1) =>
    new THREE.MeshBasicMaterial({ map, transparent: true, opacity, fog: false, depthWrite: false, side: THREE.DoubleSide });
  const add = (m: THREE.Mesh) => {
    m.renderOrder = -99;
    sky.add(m);
  };
  // the sun, or the Earth from the moon
  if (colors.space) {
    const earth = canvas(64, 64, (g) => {
      g.fillStyle = css([0.2, 0.45, 0.95]);
      g.beginPath();
      g.arc(32, 32, 31, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = css([0.25, 0.65, 0.3]);
      g.beginPath();
      g.arc(22, 26, 12, 0, Math.PI * 2);
      g.arc(43, 42, 9, 0, Math.PI * 2);
      g.fill();
    });
    add(disc(35, 22, 6, flat(earth)));
  } else {
    const glow = canvas(64, 64, (g) => {
      g.fillStyle = 'rgba(255,247,204,0.25)';
      g.beginPath();
      g.arc(32, 32, 31, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = 'rgb(255,250,224)';
      g.beginPath();
      g.arc(32, 32, 14, 0, Math.PI * 2);
      g.fill();
    });
    add(disc(35, 30, 8, flat(glow)));
  }
  // clouds (or stars), the same every time for a map
  let h = 0;
  for (const ch of mapId) h = (Math.imul(31, h) + ch.charCodeAt(0)) | 0;
  const rnd = seeded(h);
  const city = !battle && !['classic', 'gp', 'summit', 'moon'].includes(mapId);
  sky.add(skyline(colors, colors.space ? 2 : city ? 0 : 1, seeded(h ^ 0x5bd1e995)));
  if (colors.space) {
    const stars = new Float32Array(160 * 3);
    for (let i = 0; i < 160; i++) onDome(rnd() * 360, rnd() * 50 + 1, RADIUS * 0.97).toArray(stars, i * 3);
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(stars, 3));
    const points = new THREE.Points(g, new THREE.PointsMaterial({ color: 0xffffff, size: 2, sizeAttenuation: false, fog: false, transparent: true, opacity: 0.8 }));
    points.renderOrder = -99;
    sky.add(points);
  } else {
    const cloud = canvas(128, 40, (g) => {
      // a flat-bottomed cloud: shaded underside, then the bright puffs
      g.fillStyle = css([colors.low[0] * 0.92, colors.low[1] * 0.93, colors.low[2] * 0.97], 0.95);
      g.beginPath();
      g.ellipse(64, 30, 63, 8, 0, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = 'rgba(255,255,255,0.95)';
      for (const [cx, cy, rx, ry] of [[40, 26, 29, 13], [64, 20, 29, 17], [84, 26, 24, 12]]) {
        g.beginPath();
        g.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
        g.fill();
      }
    });
    for (let i = 0; i < 16; i++) {
      const az = rnd() * 360, el = 3 + rnd() * 16, size = 0.6 + rnd() * 0.9;
      add(disc(az, el, size * 11, flat(cloud), 40 / 128));
    }
  }
  return sky;
}

/** the skyline band covers this many degrees of elevation, from a degree below the horizon */
const BAND = 16, BAND_W = 4096, BAND_H = 192;

/**
 * the far-off city (or hills, or moon peaks) all round the horizon, hazy with distance: a back
 * row paler, a front row a little darker, the cities with a few lit windows. Drawn once round a
 * band inside the dome.
 * @param kind 0 city, 1 hills, 2 moon peaks
 */
function skyline(colors: SkyColors, kind: number, rnd: () => number): THREE.Mesh {
  const parts: number[][] = [];
  for (let az = 0; az < 360;) {
    const w = kind === 0 ? 0.6 + rnd() * 2.2 : 4 + rnd() * 10;
    const h = kind === 0 ? 0.6 + rnd() * rnd() * 6 : 0.6 + rnd() * 2.5;
    parts.push([az, w, h, rnd()]);
    az += w * (kind === 0 ? 0.85 : 0.6);
  }
  const lerp = (a: Rgb, b: Rgb, t: number): Rgb => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
  let far = lerp(lerp(colors.low, colors.top, 0.15), [0.42, 0.47, 0.58], 0.25);
  let near = lerp(colors.low, [0.36, 0.41, 0.52], 0.45);
  if (kind === 1) {
    far = lerp(colors.low, [0.42, 0.55, 0.48], 0.35);
    near = lerp(colors.low, [0.32, 0.48, 0.36], 0.55);
  } else if (kind === 2) {
    far = [0.25, 0.25, 0.29];
    near = [0.38, 0.38, 0.42];
  }
  const px = BAND_W / 360, py = BAND_H / BAND;
  const base = BAND_H - py; // the horizon, a degree up from the bottom
  const texture = canvas(BAND_W, BAND_H, (g) => {
    for (let layer = 0; layer < 2; layer++) {
      const col = layer === 0 ? far : near;
      for (let i = layer; i < parts.length; i += 2) {
        const [az, w, hh0, tone] = parts[i];
        const hh = hh0 * (layer === 0 ? 1 : 0.7) * py, bw = w * px;
        // drawn twice, so a building across 0 degrees wraps round
        for (const shift of [0, -BAND_W]) {
          const x0 = az * px + shift - bw / 2;
          if (kind === 0) {
            g.fillStyle = css([col[0] * (0.92 + tone * 0.12), col[1] * (0.92 + tone * 0.12), col[2] * (0.92 + tone * 0.12)]);
            g.fillRect(x0, base - hh, bw, hh + py);
            if (tone > 0.7 && hh0 > 3) g.fillRect(x0 + bw * 0.45, base - hh * 1.25, bw * 0.1, hh * 0.25); // a spire
            if (layer === 1) {
              // a few lit windows
              g.fillStyle = 'rgba(255,217,128,0.35)';
              for (let wy = base - 3; wy > base - hh + 3; wy -= 6) {
                for (let wx = x0 + 2; wx < x0 + bw - 2; wx += 5) if (Math.trunc(wx * 7 + wy * 13 + i) % 9 === 0) g.fillRect(wx, wy, 2, 2);
              }
            }
          } else {
            // a rounded hill or a jagged peak
            g.fillStyle = css([col[0] * (0.95 + tone * 0.08), col[1] * (0.95 + tone * 0.08), col[2] * (0.95 + tone * 0.08)]);
            const peak = x0 + bw * (0.3 + tone * 0.4);
            g.beginPath();
            g.moveTo(x0 - bw * 0.3, base + py);
            g.lineTo(peak, base - hh);
            g.lineTo(x0 + bw * 1.3, base + py);
            g.fill();
            if (kind === 1) {
              g.beginPath();
              g.ellipse(x0 + bw / 2, base + py, bw / 2, hh * 1.4 * 0.5 + py, 0, Math.PI, 0);
              g.fill();
            }
          }
        }
      }
    }
  });
  texture.magFilter = THREE.NearestFilter;
  texture.wrapS = THREE.RepeatWrapping;
  // a band round the inside of the dome: u runs with the azimuth, v with the elevation
  const r = RADIUS * 0.95, segments = 180;
  const pos: number[] = [], uv: number[] = [], idx: number[] = [];
  for (let i = 0; i <= segments; i++) {
    const az = (i / segments) * 360, a = (az * Math.PI) / 180;
    for (const [el, v] of [[-1, 0], [BAND - 1, 1]]) {
      const e = (el * Math.PI) / 180;
      pos.push(Math.cos(a) * r, Math.sin(a) * r, Math.tan(e) * r);
      uv.push(az / 360, v);
    }
    if (i < segments) {
      const k = i * 2;
      idx.push(k, k + 2, k + 3, k, k + 3, k + 1);
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  geo.setIndex(idx);
  const mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ map: texture, transparent: true, fog: false, depthWrite: false, side: THREE.DoubleSide }));
  mesh.renderOrder = -98;
  return mesh;
}
