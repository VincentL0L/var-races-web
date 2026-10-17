// What the third-person camera can't see through: every triangle of the still scenery (barriers,
// buildings, trees), filed into a grid on the ground. The camera's boom is short, so it tests only
// the few triangles in the cells it crosses, not the 100,000 or more of a whole city each frame.
import * as THREE from 'three';

/** grid cell size, in world units (about half the camera's boom) */
const CELL = 32;
/** a triangle covering more cells than this (a big ground sheet) is tested every time instead */
const MAX_CELLS = 256;

export class Occluders {
  /** world-space corners, 9 numbers a triangle */
  private corners = new Float32Array(0);
  /** 1 when a triangle can be hit from behind as well (a double-sided material) */
  private twoSided = new Uint8Array(0);
  private readonly cells = new Map<number, number[]>();
  private big: number[] = [];
  /** the query each triangle was last tested in, so one crossing several cells is tested once */
  private tested = new Uint32Array(0);
  private query = 0;
  /** how many children the root had when the grid was built (scenery is added after loading) */
  private built = -1;
  private readonly a = new THREE.Vector3();
  private readonly b = new THREE.Vector3();
  private readonly c = new THREE.Vector3();
  private readonly hit = new THREE.Vector3();

  constructor(private readonly root: THREE.Object3D) {}

  /** the distance along the ray to the nearest triangle within `far`, or null */
  nearest(ray: THREE.Ray, far: number): number | null {
    if (this.built !== this.root.children.length) this.build();
    if (++this.query === 0xffffffff) {
      this.tested.fill(0);
      this.query = 1;
    }
    const { origin, direction } = ray;
    const ex = origin.x + direction.x * far, ey = origin.y + direction.y * far;
    const cx0 = cell(Math.min(origin.x, ex)), cx1 = cell(Math.max(origin.x, ex));
    const cy0 = cell(Math.min(origin.y, ey)), cy1 = cell(Math.max(origin.y, ey));
    let best = far;
    let found = false;
    const test = (t: number) => {
      if (this.tested[t] === this.query) return;
      this.tested[t] = this.query;
      const p = this.corners, i = t * 9;
      this.a.set(p[i], p[i + 1], p[i + 2]);
      this.b.set(p[i + 3], p[i + 4], p[i + 5]);
      this.c.set(p[i + 6], p[i + 7], p[i + 8]);
      if (!ray.intersectTriangle(this.a, this.b, this.c, this.twoSided[t] === 0, this.hit)) return;
      const d = this.hit.distanceTo(origin);
      if (d <= best) {
        best = d;
        found = true;
      }
    };
    for (let x = cx0; x <= cx1; x++) {
      for (let y = cy0; y <= cy1; y++) this.cells.get(key(x, y))?.forEach(test);
    }
    this.big.forEach(test);
    return found ? best : null;
  }

  private build(): void {
    this.built = this.root.children.length;
    this.root.updateMatrixWorld(true);
    const corners: number[] = [], twoSided: number[] = [];
    const v = new THREE.Vector3();
    this.root.traverse((o) => {
      if (!(o instanceof THREE.Mesh)) return;
      const position = o.geometry.getAttribute('position') as THREE.BufferAttribute | undefined;
      if (!position) return;
      const index = o.geometry.getIndex();
      const materials: THREE.Material[] = Array.isArray(o.material) ? o.material : [o.material];
      const sides = materials.some((m) => m.side !== THREE.FrontSide) ? 1 : 0;
      const count = index ? index.count : position.count;
      for (let i = 0; i + 2 < count; i += 3) {
        for (let k = 0; k < 3; k++) {
          v.fromBufferAttribute(position, index ? index.getX(i + k) : i + k).applyMatrix4(o.matrixWorld);
          corners.push(v.x, v.y, v.z);
        }
        twoSided.push(sides);
      }
    });
    this.corners = new Float32Array(corners);
    this.twoSided = new Uint8Array(twoSided);
    this.tested = new Uint32Array(twoSided.length);
    this.query = 0;
    this.cells.clear();
    this.big = [];
    const p = this.corners;
    for (let t = 0; t < twoSided.length; t++) {
      const i = t * 9;
      const cx0 = cell(Math.min(p[i], p[i + 3], p[i + 6])), cx1 = cell(Math.max(p[i], p[i + 3], p[i + 6]));
      const cy0 = cell(Math.min(p[i + 1], p[i + 4], p[i + 7])), cy1 = cell(Math.max(p[i + 1], p[i + 4], p[i + 7]));
      if ((cx1 - cx0 + 1) * (cy1 - cy0 + 1) > MAX_CELLS) {
        this.big.push(t);
        continue;
      }
      for (let x = cx0; x <= cx1; x++) {
        for (let y = cy0; y <= cy1; y++) {
          const k = key(x, y);
          const list = this.cells.get(k);
          if (list) list.push(t);
          else this.cells.set(k, [t]);
        }
      }
    }
  }
}

function cell(v: number): number {
  return Math.floor(v / CELL);
}

function key(x: number, y: number): number {
  return (x + 32768) * 65536 + (y + 32768);
}
