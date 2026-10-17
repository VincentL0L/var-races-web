// Hills for the first-person view. The race itself is worked out on the flat map, so the hills
// are a way of looking at it: the ground, the scenery and the cars are all lifted by the same
// smooth height, so the road climbs and dips and everything on it follows.
//
// The height is a sum of a few long, gentle waves, worked out both here (for the cars, the
// camera) and in the vertex shader of every lifted material (patched by `lift`), with the same
// numbers, so they agree exactly. Overhead, the hills are switched off (flat).
import * as THREE from 'three';
import { hashCode } from './architecture';

const hillA = new THREE.Vector4(), hillB = new THREE.Vector4();
let amp = 0;
let on = false;

/** sets the hills for a map (how high, and a shape of their own) */
export function setHills(mapId: string): void {
  switch (mapId) {
    case 'sf': amp = 46; break;
    case 'summit': amp = 70; break;
    case 'classic': case 'gp': amp = 26; break;
    case 'moon': case 'monaco': amp = 30; break; // Monaco climbs the hillside
    case 'rooftop': case 'junkyard': case 'stadium': case 'rink': amp = 0; break;
    default: amp = 18; break; // the other cities: gentle rises
  }
  let s = hashCode(mapId) >>> 0 || 1;
  const rnd = () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return (s >>> 0) / 4294967296;
  };
  hillA.set(amp, 0.0019 + rnd() * 0.0012, 0.0015 + rnd() * 0.0012, 0.0028 + rnd() * 0.0015);
  hillB.set(rnd() * 6.28, rnd() * 6.28, rnd() * 6.28, 0);
  hillsOn(on);
}

/** hills in first person; flat overhead */
export function hillsOn(value: boolean): void {
  on = value;
  hillA.x = on ? amp : 0;
}

/** how high the ground is at (x, y) */
export function hill(x: number, y: number): number {
  const a = hillA, b = hillB;
  if (a.x === 0) return 0;
  return a.x * (0.6 * Math.sin(x * a.y + b.x) * Math.cos(y * a.z + b.y) + 0.4 * Math.sin((x * 0.7 - y * 0.5) * a.w + b.z));
}

/** the slope (rise per unit) along x and y at (x, y) */
export function slope(x: number, y: number): [number, number] {
  const e = 2;
  return [(hill(x + e, y) - hill(x - e, y)) / (2 * e), (hill(x, y + e) - hill(x, y - e)) / (2 * e)];
}

const GLSL = `
uniform vec4 u_hillA;
uniform vec4 u_hillB;
float hill(vec2 p) {
  return u_hillA.x * (0.6 * sin(p.x * u_hillA.y + u_hillB.x) * cos(p.y * u_hillA.z + u_hillB.y)
    + 0.4 * sin((p.x * 0.7 - p.y * 0.5) * u_hillA.w + u_hillB.z));
}
`;

/** makes a material ride the hills: each vertex is lifted by the height under it (in world space) */
export function lift<M extends THREE.Material>(material: M): M {
  if (material.userData.hills) return material;
  material.userData.hills = true;
  material.needsUpdate = true;
  material.onBeforeCompile = (shader) => {
    shader.uniforms.u_hillA = { value: hillA };
    shader.uniforms.u_hillB = { value: hillB };
    shader.vertexShader = GLSL + shader.vertexShader.replace('#include <project_vertex>', `
      vec4 hw = modelMatrix * vec4(transformed, 1.0);
      hw.z += hill(hw.xy);
      vec4 mvPosition = viewMatrix * hw;
      gl_Position = projectionMatrix * mvPosition;`);
  };
  material.customProgramCacheKey = () => 'hills';
  return material;
}

/** lifts every material in a tree of objects */
export function liftAll(root: THREE.Object3D): void {
  root.traverse((o) => {
    if (o instanceof THREE.Mesh) for (const m of Array.isArray(o.material) ? o.material : [o.material]) lift(m);
  });
}
