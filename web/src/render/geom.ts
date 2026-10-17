// Builds meshes out of coloured quads and boxes, all in map coordinates (x right, y up, z up
// off the ground). Shading is baked into the vertex colours (no lights), like the original.
import * as THREE from 'three';

export type Rgb = [number, number, number];

/** a colour from 0xrrggbb */
export function rgb(hex: number): Rgb {
  return [((hex >> 16) & 0xff) / 255, ((hex >> 8) & 0xff) / 255, (hex & 0xff) / 255];
}

export function shade(c: Rgb, f: number): Rgb {
  return [c[0] * f, c[1] * f, c[2] * f];
}

export function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

type V3 = [number, number, number];

export class Builder {
  private pos: number[] = [];
  private col: number[] = [];
  private uv: number[] = [];
  private idx: number[] = [];

  get empty(): boolean {
    return this.idx.length === 0;
  }

  /** a quad a-b-c-d (any winding: materials are double sided), with texture coordinates */
  quad(a: V3, b: V3, c: V3, d: V3, color: Rgb, uv: [number, number, number, number, number, number, number, number] = [0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]): void {
    const base = this.pos.length / 3;
    for (const p of [a, b, c, d]) this.pos.push(p[0], p[1], p[2]);
    for (let i = 0; i < 4; i++) this.col.push(color[0], color[1], color[2]);
    this.uv.push(...uv);
    this.idx.push(base, base + 1, base + 2, base + 2, base + 3, base);
  }

  /**
   * an axis-aligned box from (x0, y0, z0) to (x1, y1, z1), its faces shaded: top brightest,
   * the sides by direction (east, north, west, south)
   */
  box(x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, c: Rgb, bottom = false): void {
    this.quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], c);
    this.quad([x1, y0, z0], [x1, y1, z0], [x1, y1, z1], [x1, y0, z1], shade(c, 0.9));
    this.quad([x0, y1, z0], [x0, y0, z0], [x0, y0, z1], [x0, y1, z1], shade(c, 0.68));
    this.quad([x1, y1, z0], [x0, y1, z0], [x0, y1, z1], [x1, y1, z1], shade(c, 0.8));
    this.quad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1], shade(c, 0.6));
    if (bottom) this.quad([x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], shade(c, 0.4));
  }

  /** a box by corner, width, length and height: x..x+w, y..y+l, z..z+d */
  block(x: number, y: number, z: number, w: number, l: number, d: number, c: Rgb): void {
    this.box(x, y, z, x + w, y + l, z + d, c, true);
  }

  /** a tapering square block centred on (x, y): half-widths r0 at z0 to r1 at z1 */
  frustum(x: number, y: number, z0: number, z1: number, r0: number, r1: number, c: Rgb): void {
    this.quad([x - r1, y - r1, z1], [x + r1, y - r1, z1], [x + r1, y + r1, z1], [x - r1, y + r1, z1], c);
    this.quad([x + r0, y - r0, z0], [x + r0, y + r0, z0], [x + r1, y + r1, z1], [x + r1, y - r1, z1], shade(c, 0.9));
    this.quad([x - r0, y + r0, z0], [x - r0, y - r0, z0], [x - r1, y - r1, z1], [x - r1, y + r1, z1], shade(c, 0.68));
    this.quad([x + r0, y + r0, z0], [x - r0, y + r0, z0], [x - r1, y + r1, z1], [x + r1, y + r1, z1], shade(c, 0.8));
    this.quad([x - r0, y - r0, z0], [x + r0, y - r0, z0], [x + r1, y - r1, z1], [x - r1, y - r1, z1], shade(c, 0.6));
  }

  geometry(): THREE.BufferGeometry {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute('color', new THREE.Float32BufferAttribute(this.col, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(this.uv, 2));
    g.setIndex(this.pos.length / 3 > 65535 ? new THREE.Uint32BufferAttribute(this.idx, 1) : new THREE.Uint16BufferAttribute(this.idx, 1));
    g.computeBoundingSphere();
    return g;
  }

  mesh(material: THREE.Material): THREE.Mesh {
    return new THREE.Mesh(this.geometry(), material);
  }
}

/** a flat colour material that takes its colours from the vertices (and the fog) */
export function vertexColors(map: THREE.Texture | null = null, alphaTest = 0): THREE.MeshBasicMaterial {
  return new THREE.MeshBasicMaterial({ vertexColors: true, map, alphaTest, side: THREE.DoubleSide, fog: true });
}
