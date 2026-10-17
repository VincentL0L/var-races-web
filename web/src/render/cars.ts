// The cars. Overhead, a car is its sprite. In first person it's a little model built to its own
// shape (the garage's Sprinter, Roadster, GT, Muscle and Hauler, and each city's CPU cars: Waymo,
// kei van, taxi, police car, double-decker bus, 2CV, scooter, moon rover and F1 car), with the sprite
// laid over the top as the paint job.
//
// Models are built in the car's own frame: x across, y toward the nose, z up, with the sprite
// w = 10 wide and h = 20 long.
import * as THREE from 'three';
import { carSprite, cpuSprite, pixels, texture } from '../assets';
import { Builder, shade, vertexColors, type Rgb } from './geom';

export const CAR_W = 10;
export const CAR_L = 20;

interface Shape {
  /** body width as a share of the sprite's, and where the body starts off the ground */
  width: number;
  low: number;
  /** the top of the bonnet and boot (where the sprite is laid) */
  deck: number;
  /** the cabin, as shares of the length from the tail (0) to the nose (1), and its roof height */
  cabinFrom: number;
  cabinTo: number;
  cabinTop: number;
  /** wheel size, and how far in from each end the axles are (share of length) */
  wheel: number;
  axle: number;
  extra: string;
}

function shape(p: Partial<Shape>): Shape {
  return { width: 1, low: 0.9, deck: 3.6, cabinFrom: 0.26, cabinTo: 0.7, cabinTop: 5.8, wheel: 2.6, axle: 0.21, extra: '', ...p };
}

/** the shape of a car from its sprite's name (a plain coupe for anything unknown) */
function shapeOf(sprite: string): Shape {
  const name = sprite.split('/').pop()!.replace('.png', '').split('_')[0];
  switch (name) {
    case 'sprinter': // little hatchback: the cabin runs nearly to the tail
      return shape({ width: 0.9, cabinFrom: 0.12, cabinTo: 0.66, cabinTop: 6, extra: 'SPOILER_SMALL' });
    case 'roadster': // open two-seater: windscreen, seats, roll hoops
      return shape({ cabinFrom: 0.3, cabinTo: 0.62, cabinTop: 5.2, extra: 'OPEN' });
    case 'gt': // long and low, cabin set back, big wing
      return shape({ deck: 3.1, cabinFrom: 0.24, cabinTo: 0.6, cabinTop: 5, extra: 'SPOILER' });
    case 'muscle': // wide, bulging bonnet scoop, ducktail
      return shape({ deck: 3.8, cabinFrom: 0.22, cabinTo: 0.6, cabinTop: 5.9, extra: 'SCOOP SPOILER_SMALL' });
    case 'hauler': // pickup: tall cab up front, open bed behind
      return shape({ deck: 4.6, cabinFrom: 0.42, cabinTo: 0.72, cabinTop: 8.2, wheel: 3.4, axle: 0.2, extra: 'BED' });
    case 'waymo': // tall electric SUV with the sensor dome on the roof
      return shape({ deck: 4.4, cabinFrom: 0.18, cabinTo: 0.72, cabinTop: 7.6, wheel: 3, axle: 0.2, extra: 'SENSOR' });
    case 'kei': // boxy kei van: almost all cabin
      return shape({ width: 0.92, deck: 3.6, cabinFrom: 0.08, cabinTo: 0.86, cabinTop: 8.8, wheel: 2.4, axle: 0.16 });
    case 'cab':
      return shape({ cabinFrom: 0.26, cabinTo: 0.68, cabinTop: 6.2, extra: 'TAXI' });
    case 'cop':
      return shape({ cabinFrom: 0.26, cabinTo: 0.68, cabinTop: 6.2, extra: 'SIREN' });
    case 'bus': // red double-decker
      return shape({ deck: 19, cabinFrom: 0, cabinTo: 0, cabinTop: 19, wheel: 3.6, axle: 0.17, extra: 'BUS' });
    case '2cv': // round little 2CV: tall cabin, narrow body
      return shape({ width: 0.88, deck: 3.4, cabinFrom: 0.2, cabinTo: 0.66, cabinTop: 7, wheel: 3, axle: 0.2 });
    case 'moto': // scooter and rider
      return shape({ width: 0.35, deck: 3.2, cabinFrom: 0, cabinTo: 0, cabinTop: 0, wheel: 3, axle: 0.12, extra: 'BIKE' });
    case 'f1': // open-wheel racer: low nose, wings front and back, cockpit and halo
      return shape({ width: 0.6, deck: 2.4, cabinFrom: 0, cabinTo: 0, cabinTop: 0, wheel: 3.2, axle: 0.16, extra: 'F1' });
    case 'rover': // moon buggy: open frame, six big wheels, gold dish
      return shape({ deck: 4.2, cabinFrom: 0, cabinTo: 0, cabinTop: 0, wheel: 3.6, axle: 0.14, extra: 'ROVER' });
    default: // the classic racers: a sports coupe
      return shape({ cabinFrom: 0.26, cabinTo: 0.64, cabinTop: 5.6, extra: 'SPOILER_SMALL' });
  }
}

const has = (s: Shape, e: string) => s.extra.includes(e);

/** the eye height in a car of this shape (just over its roof, or over the rider's head) */
export function eyeHeight(sprite: string): number {
  const s = shapeOf(sprite);
  if (has(s, 'BUS')) return 15; // the top deck's front seats
  if (has(s, 'BIKE')) return 8.5;
  if (has(s, 'ROVER') || has(s, 'OPEN')) return s.deck + 3;
  return s.cabinTop + 0.5;
}

/** a model: its static body, plus the parts that animate (siren, sensor) */
interface Model {
  group: THREE.Group;
  animate(clock: number): void;
}

function buildModel(s: Shape, body: Rgb, paint: THREE.Texture, inside: boolean): Model {
  const w = CAR_W, h = CAR_L;
  const bw = w * s.width, tail = -h / 2;
  const b = new Builder();
  const group = new THREE.Group();
  const anims: ((clock: number) => void)[] = [];
  const box = (x: number, y: number, z: number, bxw: number, l: number, d: number, c: Rgb) => b.block(x, y, z, bxw, l, d, c);

  if (!inside) {
    const shadow = new THREE.Mesh(
      new THREE.PlaneGeometry(bw + 2, h + 2),
      new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.35, depthWrite: false }),
    );
    shadow.position.z = 0.05;
    group.add(shadow);
    wheels(s, bw, h, box);
  }
  const bike = has(s, 'BIKE');
  if (bike) {
    box(-1.2, -h * 0.32, 1.2, 2.4, h * 0.64, 2.2, body);
    box(-1.1, -h * 0.2, 3.4, 2.2, h * 0.3, 0.6, [0.12, 0.1, 0.1]);
    if (!inside) {
      // handlebars, the rider's legs, body and helmet
      box(-2.6, h * 0.25, 4.6, 5.2, 0.5, 0.5, [0.2, 0.2, 0.22]);
      box(-1.8, -h * 0.12, 3.4, 3.6, 3.6, 2.4, [0.2, 0.35, 0.8]);
      box(-1.7, -h * 0.1, 5.8, 3.4, 2.6, 3.4, [0.95, 0.8, 0.2]);
      box(-1.2, -h * 0.08, 9.2, 2.4, 2.4, 2.2, [0.85, 0.2, 0.18]);
      lights(3, h * 0.7, 2.4, box);
    }
  } else if (has(s, 'F1')) {
    // slim body, sidepods, a wide front wing; outside also the rear wing, airbox, cockpit,
    // the driver's helmet and the halo
    box(-bw / 2, -h / 2 + 3, 0.6, bw, h - 5, 1.8, body);
    box(-bw / 2 - 1.2, -h * 0.25, 0.6, bw + 2.4, h * 0.35, 1.6, shade(body, 0.8));
    box(-w / 2, h / 2 - 2, 0.3, w, 1.6, 0.5, [0.1, 0.1, 0.11]);
    if (!inside) {
      box(-w / 2 + 0.5, -h / 2, 3.2, w - 1, 1.8, 0.6, [0.1, 0.1, 0.11]);
      box(-w / 2 + 0.8, -h / 2, 0.6, 0.4, 1.8, 3.2, [0.1, 0.1, 0.11]);
      box(w / 2 - 1.2, -h / 2, 0.6, 0.4, 1.8, 3.2, [0.1, 0.1, 0.11]);
      box(-1, -h * 0.2, 2.4, 2, 4, 1.6, body);
      box(-1.2, -h * 0.02, 2.4, 2.4, 2.6, 0.4, [0.08, 0.08, 0.09]);
      box(-0.8, 0.2, 2.5, 1.6, 1.6, 1.2, [0.95, 0.8, 0.2]);
      box(-1.3, h * 0.08, 3.6, 2.6, 0.4, 0.4, [0.2, 0.2, 0.22]);
    }
  } else if (!(inside && has(s, 'BUS'))) {
    // lower body: dark sill, then the paint
    box(-bw / 2, tail, s.low, bw, h, Math.min(1.2, s.deck - s.low), shade(body, 0.45));
    const paintBody = shade(body, 0.85);
    box(-bw / 2, tail, s.low + 1.2, bw, h, s.deck - s.low - 1.2, paintBody);
    if (has(s, 'BUS')) {
      // windows on both decks, all the way round, and the destination board
      box(-bw / 2 - 0.05, tail - 0.05, 5, bw + 0.1, h + 0.1, 3.4, [0.14, 0.18, 0.25]);
      box(-bw / 2 - 0.05, tail - 0.05, 12.5, bw + 0.1, h + 0.1, 3.6, [0.14, 0.18, 0.25]);
      box(-bw / 2 + 1.5, h / 2 - 0.05, 9.6, bw - 3, 0.2, 1.3, [0.95, 0.95, 0.9]);
      if (!inside) lights(bw, h, s.low + 1.5, box);
    } else if (has(s, 'ROVER')) {
      box(-bw / 2 + 1.2, -h * 0.1, s.deck, bw / 2 - 1.6, 3.4, 2, [0.25, 0.25, 0.28]);
      box(0.4, -h * 0.1, s.deck, bw / 2 - 1.6, 3.4, 2, [0.25, 0.25, 0.28]);
      box(-0.25, -h * 0.35, s.deck, 0.5, 0.5, 5, [0.6, 0.6, 0.62]);
      box(-2, -h * 0.35 - 0.3, s.deck + 5, 4, 1.2, 3.4, [0.85, 0.7, 0.25]);
    } else {
      const cb = tail + h * s.cabinFrom, cl = h * (s.cabinTo - s.cabinFrom);
      if (!inside && cl > 0) {
        if (has(s, 'OPEN')) {
          // windscreen, two seats and a pair of roll hoops
          box(-bw / 2 + 1, cb + cl - 0.6, s.deck, bw - 2, 0.5, s.cabinTop - s.deck, [0.55, 0.7, 0.8]);
          box(-bw / 2 + 1.2, cb + 0.5, s.deck - 0.6, bw / 2 - 1.6, cl - 2, 1.6, [0.12, 0.1, 0.09]);
          box(0.4, cb + 0.5, s.deck - 0.6, bw / 2 - 1.6, cl - 2, 1.6, [0.12, 0.1, 0.09]);
          box(-bw / 2 + 1.5, cb + 0.2, s.deck, 0.5, 0.5, 2.2, [0.7, 0.7, 0.72]);
          box(bw / 2 - 2, cb + 0.2, s.deck, 0.5, 0.5, 2.2, [0.7, 0.7, 0.72]);
        } else {
          // glass all round, then a roof in the body colour
          box(-bw / 2 + 1.1, cb, s.deck, bw - 2.2, cl, s.cabinTop - s.deck - 0.45, [0.13, 0.17, 0.24]);
          box(-bw / 2 + 1.4, cb + 0.9, s.cabinTop - 0.45, bw - 2.8, cl - 1.8, 0.5, body);
        }
      }
      if (has(s, 'SCOOP')) box(-1.4, h * 0.18, s.deck, 2.8, 3.2, 0.9, shade(body, 0.25));
      if (!inside) {
        if (has(s, 'SPOILER_SMALL')) {
          box(-bw / 2 + 0.5, tail + 0.3, s.deck, bw - 1, 1.2, 0.7, shade(body, 0.6));
        } else if (has(s, 'SPOILER')) {
          box(-bw / 2 + 1.5, tail + 1, s.deck, 0.5, 0.6, 2, [0.1, 0.1, 0.11]);
          box(bw / 2 - 2, tail + 1, s.deck, 0.5, 0.6, 2, [0.1, 0.1, 0.11]);
          box(-bw / 2 - 0.3, tail + 0.2, s.deck + 2, bw + 0.6, 2.4, 0.45, shade(body, 0.7));
        }
        if (has(s, 'BED')) {
          const wall = shade(body, 0.6);
          box(-bw / 2, tail, s.deck, 0.7, cb - tail, 1.6, wall);
          box(bw / 2 - 0.7, tail, s.deck, 0.7, cb - tail, 1.6, wall);
          box(-bw / 2, tail, s.deck, bw, 0.7, 1.6, wall);
        }
        if (has(s, 'SENSOR')) {
          // the lidar dome, spinning, and little cameras on the corners
          box(-1.6, cb + cl * 0.45, s.cabinTop + 0.05, 3.2, 3.2, 1.3, [0.15, 0.15, 0.17]);
          box(-bw / 2 - 0.4, h / 2 - 4, s.deck - 0.6, 0.6, 1.2, 1.2, [0.2, 0.2, 0.22]);
          box(bw / 2 - 0.2, h / 2 - 4, s.deck - 0.6, 0.6, 1.2, 1.2, [0.2, 0.2, 0.22]);
          const dome = part(group, -1.2, cb + cl * 0.45 + 0.4, s.cabinTop + 1.35, 2.4, 2.4, 0.7);
          anims.push((clock) => dome.color.setRGB(0.25 + ((clock * 4) % 1) * 0.3, 0.85, 0.8));
        }
        if (has(s, 'TAXI')) box(-1.8, cb + cl / 2 - 0.8, s.cabinTop + 0.05, 3.6, 1.6, 1.1, [1, 0.95, 0.75]);
        if (has(s, 'SIREN')) {
          // the light bar flashes red, then blue
          const left = part(group, -bw / 2 + 1.6, cb + cl / 2 - 0.8, s.cabinTop + 0.05, bw / 2 - 1.6, 1.6, 0.9);
          const right = part(group, 0, cb + cl / 2 - 0.8, s.cabinTop + 0.05, bw / 2 - 1.6, 1.6, 0.9);
          anims.push((clock) => {
            const red = Math.floor(clock * 6) % 2 === 0;
            left.color.setRGB(red ? 1 : 0.35, 0.12, red ? 0.12 : 0.25);
            right.color.setRGB(0.15, red ? 0.25 : 0.45, red ? 0.4 : 1);
          });
        }
        lights(bw, h, s.low + 1.3, box);
      }
    }
  }
  group.add(b.mesh(vertexColors()));
  if (!bike && !(inside && has(s, 'BUS'))) {
    // the top-down paint job across the bonnet and boot (the cabin hides its middle)
    const deck = new THREE.Mesh(
      new THREE.PlaneGeometry(w, h),
      new THREE.MeshBasicMaterial({ map: paint, transparent: true, alphaTest: 0.1, side: THREE.DoubleSide, fog: true }),
    );
    deck.position.z = s.deck + 0.02;
    group.add(deck);
  }
  return { group, animate: (clock) => anims.forEach((a) => a(clock)) };
}

/** a box that changes colour (siren, sensor) */
function part(group: THREE.Group, x: number, y: number, z: number, w: number, l: number, d: number): THREE.MeshBasicMaterial {
  const material = new THREE.MeshBasicMaterial({ fog: true });
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, l, d), material);
  mesh.position.set(x + w / 2, y + l / 2, z + d / 2);
  group.add(mesh);
  return material;
}

type Box = (x: number, y: number, z: number, w: number, l: number, d: number, c: Rgb) => void;

function wheels(s: Shape, bw: number, h: number, box: Box): void {
  const axles = has(s, 'ROVER') ? 3 : 2;
  for (let a = 0; a < axles; a++) {
    const wy = axles === 3 ? (a - 1) * (h / 2 - h * s.axle) : (a === 0 ? -1 : 1) * (h / 2 - h * s.axle);
    if (has(s, 'BIKE')) {
      box(-0.6, wy - s.wheel / 2, 0, 1.2, s.wheel, s.wheel, [0.07, 0.07, 0.08]);
      continue;
    }
    for (const side of [-1, 1]) {
      const wx = side * (bw / 2 - 0.6);
      box(wx - 1.1, wy - s.wheel * 0.85, 0, 2.2, s.wheel * 1.7, s.wheel, [0.07, 0.07, 0.08]);
      box(wx + side * 1.15 - 0.05, wy - s.wheel * 0.3, s.wheel * 0.2, 0.1, s.wheel * 0.6, s.wheel * 0.6, [0.6, 0.6, 0.62]);
    }
  }
}

function lights(bw: number, h: number, z: number, box: Box): void {
  box(-bw / 2 + 0.8, h / 2 - 0.2, z, 2.2, 0.4, 0.9, [1, 0.95, 0.72]);
  box(bw / 2 - 3, h / 2 - 0.2, z, 2.2, 0.4, 0.9, [1, 0.95, 0.72]);
  box(-bw / 2 + 0.8, -h / 2 - 0.2, z, 2.2, 0.4, 0.8, [0.9, 0.08, 0.08]);
  box(bw / 2 - 3, -h / 2 - 0.2, z, 2.2, 0.4, 0.8, [0.9, 0.08, 0.08]);
}

const bodyColors = new Map<string, Promise<Rgb>>();

/** the main colour of a car's paint, from the middle band of its sprite (the doors) */
function bodyColor(sprite: string): Promise<Rgb> {
  let c = bodyColors.get(sprite);
  if (!c) {
    c = pixels(sprite).then((p) => {
      let r = 0, g = 0, b = 0, n = 0;
      for (let y = Math.floor(p.height / 3); y < Math.floor((p.height * 2) / 3); y++) {
        for (let x = 0; x < p.width; x++) {
          const i = (y * p.width + x) * 4;
          const pr = p.data[i] / 255, pg = p.data[i + 1] / 255, pb = p.data[i + 2] / 255;
          if (p.data[i + 3] > 127 && pr + pg + pb > 0.25) {
            r += pr;
            g += pg;
            b += pb;
            n++;
          }
        }
      }
      return n ? ([r / n, g / n, b / n] as Rgb) : ([0.5, 0.5, 0.5] as Rgb);
    });
    bodyColors.set(sprite, c);
  }
  return c;
}

/** the sprite for the car in `slot` of a race */
export function spriteFor(slot: number, carNumber: number, mapCpuSprite: string | undefined, title = false): string {
  if (title) return `ui/car${slot + 1}.png`;
  return carNumber > 0 ? carSprite(carNumber) : cpuSprite(mapCpuSprite, slot);
}

/** how a car looks right now */
export interface CarLook {
  frozen: boolean;
  burning: number;
}

/**
 * One car on screen: its sprite (overhead) and its model (first person). The model is built
 * the first time it's needed.
 */
export class CarView {
  readonly root = new THREE.Group();
  private readonly sprite: THREE.Mesh;
  private readonly spriteMaterial: THREE.MeshBasicMaterial;
  private model: Model | null = null;
  private modelMaterials: THREE.MeshBasicMaterial[] = [];
  private building = false;
  private inside = false;

  constructor(readonly spriteName: string, smoothSprite: boolean) {
    this.spriteMaterial = new THREE.MeshBasicMaterial({ transparent: true, alphaTest: 0.05, depthWrite: false });
    this.sprite = new THREE.Mesh(new THREE.PlaneGeometry(CAR_W, CAR_L), this.spriteMaterial);
    this.sprite.position.z = 1;
    this.sprite.renderOrder = 2;
    this.root.add(this.sprite);
    void texture(spriteName, !smoothSprite).then((t) => {
      this.spriteMaterial.map = t;
      this.spriteMaterial.needsUpdate = true;
    });
  }

  /** overhead (sprite) or first person (model); `inside` = our own car, seen from the seat */
  setMode(firstPerson: boolean, inside: boolean): void {
    this.sprite.visible = !firstPerson;
    if (firstPerson && (!this.model || this.inside !== inside) && !this.building) {
      this.inside = inside;
      this.build();
    }
    if (this.model) this.model.group.visible = firstPerson;
  }

  private build(): void {
    this.building = true;
    const inside = this.inside;
    void Promise.all([bodyColor(this.spriteName), texture(this.spriteName, true)]).then(([body, paint]) => {
      this.building = false;
      if (this.model) this.root.remove(this.model.group);
      this.model = buildModel(shapeOf(this.spriteName), body, paint, inside);
      this.root.add(this.model.group);
      this.modelMaterials = [];
      this.model.group.traverse((o) => {
        if (o instanceof THREE.Mesh && o.material instanceof THREE.MeshBasicMaterial && !o.material.transparent) {
          this.modelMaterials.push(o.material);
        }
      });
      if (this.inside !== inside) this.build();
    });
  }

  /** places the car; heading in radians (0 = +x) */
  update(x: number, y: number, heading: number, look: CarLook, clock: number): void {
    this.root.position.set(x, y, 0);
    this.root.rotation.z = heading - Math.PI / 2;
    // frozen: icy blue; burning: an orange flicker
    let tint: Rgb = [1, 1, 1];
    if (look.frozen) tint = [0.6, 0.85, 1];
    else if (look.burning > 0) {
      const flick = 0.75 + 0.25 * Math.sin(look.burning * 40);
      tint = [1, 0.55 * flick + 0.2, 0.3 * flick];
    }
    this.spriteMaterial.color.setRGB(...tint);
    for (const m of this.modelMaterials) if (m.vertexColors) m.color.setRGB(...tint);
    this.model?.animate(clock);
  }

  dispose(): void {
    this.root.traverse((o) => {
      if (o instanceof THREE.Mesh) {
        o.geometry.dispose();
        (o.material as THREE.Material).dispose();
      }
    });
  }
}
