// The items on the track (the pixel art in assets/ui/items): item boxes that bob and glint,
// oil slicks on the road, rockets with a spark trail, blaster shots and shield bubbles.
// Boxes are spinning cubes, rockets are models, and glowing effects face the active camera.
import * as THREE from 'three';
import { texture } from '../assets';
import { Builder, vertexColors } from './geom';

export interface ItemState {
  /** which boxes are there */
  boxes: boolean[];
  rockets: [number, number, number][];
  shots: [number, number, number, boolean][];
  slicks: [number, number][];
  /** car centres with a bubble shield */
  shields: [number, number][];
}

/** a pool of objects of one kind, shown as many as needed each frame */
class Pool<T extends THREE.Object3D> {
  private items: T[] = [];
  private used = 0;

  constructor(private readonly parent: THREE.Object3D, private readonly make: () => T) {}

  next(): T {
    if (this.used === this.items.length) {
      const o = this.make();
      this.items.push(o);
      this.parent.add(o);
    }
    const o = this.items[this.used++];
    o.visible = true;
    return o;
  }

  /** hides whatever wasn't used this frame */
  finish(): void {
    for (let i = this.used; i < this.items.length; i++) this.items[i].visible = false;
    this.used = 0;
  }
}

/** gives a material its item picture once it's loaded */
function withArt<M extends THREE.MeshBasicMaterial | THREE.SpriteMaterial>(m: M, name: string): M {
  void texture(`ui/items/${name}.png`).then((t) => {
    m.map = t;
    m.needsUpdate = true;
  });
  return m;
}

export class ItemsView {
  readonly root = new THREE.Group();
  private pools: Pool<THREE.Object3D>[] = [];
  private readonly flats = new Map<string, Pool<THREE.Mesh>>();
  private readonly sprites = new Map<string, Pool<THREE.Sprite>>();
  private readonly cube: Pool<THREE.Mesh>;
  private readonly rocketModel: Pool<THREE.Mesh>;
  private clock = 0;

  constructor(private readonly boxes: [number, number][]) {
    const cubeGeometry = new THREE.BoxGeometry(8, 8, 8);
    const positions = cubeGeometry.getAttribute('position');
    const normals = cubeGeometry.getAttribute('normal');
    const uv = cubeGeometry.getAttribute('uv');
    for (let i = 0; i < positions.count; i++) {
      const x = positions.getX(i) / 8, y = positions.getY(i) / 8, z = positions.getZ(i) / 8;
      const nx = normals.getX(i), ny = normals.getY(i), nz = normals.getZ(i);
      // Side face right-vector = world-up cross outward normal.
      uv.setXY(i, 0.5 + (nz ? x : -ny * x + nx * y), 0.5 + (nz ? nz * y : z));
    }
    this.cube = this.pool(() => new THREE.Mesh(cubeGeometry, withArt(new THREE.MeshBasicMaterial({ fog: true }), 'box_face')));
    const rocket = rocketGeometry();
    const rocketMaterial = vertexColors();
    this.rocketModel = this.pool(() => new THREE.Mesh(rocket, rocketMaterial));
  }

  private pool<T extends THREE.Object3D>(make: () => T): Pool<T> {
    const p = new Pool<T>(this.root, make);
    this.pools.push(p as unknown as Pool<THREE.Object3D>);
    return p;
  }

  /** a flat picture lying on the ground (each one has its own material, for its tint) */
  private flat(name: string, size: number): THREE.Mesh {
    const key = `${name}${size}`;
    let p = this.flats.get(key);
    if (!p) {
      const geometry = new THREE.PlaneGeometry(size, size);
      p = this.pool(() => {
        const m = new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, side: THREE.DoubleSide, fog: true });
        return new THREE.Mesh(geometry, withArt(m, name));
      });
      this.flats.set(key, p);
    }
    return p.next();
  }

  /** a picture facing the camera, tinted and turned */
  private glow(name: string, x: number, y: number, z: number, size: number, r: number, g: number, b: number, a: number, rotation = 0): void {
    let p = this.sprites.get(name);
    if (!p) {
      p = this.pool(() => new THREE.Sprite(withArt(new THREE.SpriteMaterial({ transparent: true, depthWrite: false, fog: true }), name)));
      this.sprites.set(name, p);
    }
    const s = p.next();
    s.material.color.setRGB(r, g, b);
    s.material.opacity = a;
    s.material.rotation = rotation;
    s.position.set(x, y, z);
    s.scale.set(size, size, 1);
  }

  update(state: ItemState, eye: THREE.Vector3, delta: number): void {
    this.clock += delta;
    const t = this.clock;
    // oil slicks, flat on the road
    for (const [x, y] of state.slicks) {
      this.flat('oil', 28).position.set(x, y, 0.08);
    }
    // item boxes: each bobs a little out of step with its neighbours, and glints now and then
    state.boxes.forEach((on, i) => {
      if (!on || i >= this.boxes.length) return;
      const [bx, by] = this.boxes[i];
      const glint = Math.sin(t * 5 + i) > 0.92 ? 1.3 : 1;
      if ((bx - eye.x) ** 2 + (by - eye.y) ** 2 > 1800 * 1800) return;
      this.glow('shield', bx, by, 0.1, 10, 0, 0, 0, 0.3);
      const cube = this.cube.next();
      cube.position.set(bx, by, 7 + Math.sin(t * 3 + i * 1.7) * 1.2);
      cube.rotation.set(0, 0, ((t * 70 + i * 40) * Math.PI) / 180);
      (cube.material as THREE.MeshBasicMaterial).color.setScalar(glint);
    });
    // rockets with sparks trailing behind
    for (const [x, y, h] of state.rockets) {
      const fx = Math.cos(h), fy = Math.sin(h);
      const r = this.rocketModel.next();
      r.position.set(x, y, 0);
      r.rotation.set(0, 0, h - Math.PI / 2);
      for (let k = 1; k <= 6; k++) {
        const d = 4 + k * 3.5;
        this.glow('shield', x - fx * d, y - fy * d, 4.4 + Math.sin(t * 30 + k) * 0.4, 3.2 - k * 0.3, 1, 0.9 - k * 0.1, 0.35, 0.9 - k * 0.13);
      }
    }
    // blaster shots: an ice shard or a fireball with a short glowing trail
    for (const [x, y, , fire] of state.shots) {
      const z = 4.5;
      for (let k = 3; k >= 0; k--) {
        const a = 0.85 - k * 0.2, size = 10 - k * 1.5;
        if (fire) this.glow('shield', x, y, z, size, 1, 0.55 + k * 0.08, 0.15, a);
        else this.glow('shield', x, y, z, size, 0.6, 0.9, 1, a);
      }
      this.glow(fire ? 'fire' : 'frost', x, y, z + 0.1, 7, 1, 1, 1, 1);
    }
    // shields: a shimmering bubble round the car
    for (const [x, y] of state.shields) {
      if ((x - eye.x) ** 2 + (y - eye.y) ** 2 < 64) continue;
      const pulse = 1 + Math.sin(t * 6) * 0.05;
      this.glow('shield', x, y, 4, 26 * pulse, 1, 1, 1, 0.85);
    }
    for (const p of this.pools) p.finish();
  }
}

/** a little red rocket model, nose toward +y */
function rocketGeometry(): THREE.BufferGeometry {
  const b = new Builder();
  b.block(-1.3, -5, 3.2, 2.6, 8, 2.6, [0.85, 0.2, 0.18]);
  b.block(-0.9, 3, 3.6, 1.8, 2, 1.8, [0.97, 0.97, 0.95]);
  b.block(-0.9, -1, 3.1, 1.8, 1, 2.8, [0.97, 0.97, 0.95]);
  b.block(-2.6, -5, 3.9, 5.2, 2, 0.4, [0.55, 0.12, 0.1]);
  b.block(-0.2, -5, 2, 0.4, 2, 4.6, [0.55, 0.12, 0.1]);
  return b.geometry();
}
