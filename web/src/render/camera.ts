// Presentation only: this controller consumes rendered poses and never reads or sends input.
import * as THREE from 'three';
import { Occluders } from './occluders';

export type CameraMode = 'firstPerson' | 'thirdPerson';
export interface CameraPose { x: number; y: number; z: number; heading: number; }
export interface CameraProfile {
  distance: number;
  height: number;
  lookAhead: number;
  eyeForward: number;
  /** how quickly the third-person camera swings round to follow the car's heading (per second) */
  turnRate: number;
}
export const RACE_CAMERA: CameraProfile = { distance: 70, height: 32, lookAhead: 24, eyeForward: 3.5, turnRate: 8 };
/** the title screen's chase: swings round more lazily, so the CPU's steering doesn't sway the whole view */
export const TITLE_CAMERA: CameraProfile = { ...RACE_CAMERA, turnRate: 2.5 };

export function nextCameraMode(mode: CameraMode): CameraMode {
  return mode === 'firstPerson' ? 'thirdPerson' : 'firstPerson';
}
export function cameraLabel(mode: CameraMode): string {
  return mode === 'firstPerson' ? '1st Person' : '3rd Person';
}

export class CameraRig {
  readonly camera = new THREE.PerspectiveCamera(60, 1, 0.3, 2600);
  private mode: CameraMode = 'thirdPerson';
  private heading = 0;
  private initialized = false;
  private boomDistance = 0;
  private aspect = 0;
  private readonly last = new THREE.Vector3();
  private readonly anchor = new THREE.Vector3();
  private readonly desired = new THREE.Vector3();
  private readonly position = new THREE.Vector3();
  private readonly aim = new THREE.Vector3();
  private readonly ray = new THREE.Raycaster();
  private readonly direction = new THREE.Vector3();
  private readonly side = new THREE.Vector3();
  private readonly origin = new THREE.Vector3();
  private readonly hits: THREE.Intersection[] = [];

  constructor(private readonly profile: CameraProfile) {
    this.camera.up.set(0, 0, 1);
  }

  setMode(mode: CameraMode): void {
    if (this.mode === mode) return;
    this.mode = mode;
    this.reset();
  }

  reset(): void { this.initialized = false; }

  resize(width: number, height: number): void {
    const aspect = width / Math.max(1, height);
    if (aspect === this.aspect) return;
    this.aspect = aspect;
    this.camera.aspect = aspect;
    this.camera.fov = Math.max(40, Math.min(75, 2 * Math.atan(1 / aspect) * 180 / Math.PI));
    this.camera.updateProjectionMatrix();
  }

  update(pose: CameraPose, eye: number, delta: number, obstacles: Occluders | THREE.Object3D[] = [], shake = 0): void {
    const { x, y, z, heading } = pose;
    const snap = !this.initialized || this.last.distanceToSquared(this.anchor.set(x, y, z)) > 200 ** 2;
    const rate = this.mode === 'firstPerson' ? 18 : this.profile.turnRate;
    const turn = Math.atan2(Math.sin(heading - this.heading), Math.cos(heading - this.heading));
    this.heading = snap ? heading : this.heading + turn * (1 - Math.exp(-rate * delta));
    this.last.set(x, y, z);
    this.initialized = true;
    const fx = Math.cos(this.heading), fy = Math.sin(this.heading);
    if (this.mode === 'firstPerson') {
      const sx = shake > 0.05 ? (Math.random() * 2 - 1) * shake * 0.15 : 0;
      const sz = shake > 0.05 ? (Math.random() * 2 - 1) * shake * 0.1 : 0;
      this.camera.position.set(x + fx * this.profile.eyeForward - fy * sx, y + fy * this.profile.eyeForward + fx * sx, z + eye + sz);
      this.aim.copy(this.camera.position).add(this.direction.set(fx, fy, -0.045));
    } else {
      this.desired.set(x - fx * this.profile.distance, y - fy * this.profile.distance, z + this.profile.height);
      if (snap) this.position.copy(this.desired);
      else this.position.lerp(this.desired, 1 - Math.exp(-12 * delta));
      this.anchor.set(x, y, z + eye);
      this.direction.subVectors(this.position, this.anchor);
      const length = this.direction.length();
      let distance = length;
      if (length > 0.001) {
        this.direction.divideScalar(length);
        this.ray.near = 0;
        this.ray.far = length + 3;
        // Sample the camera's width as well as its centre, keeping the near plane clear.
        for (const side of [0, -3, 3]) {
          this.origin.copy(this.anchor).addScaledVector(this.side.set(-fy, fx, 0), side);
          this.ray.set(this.origin, this.direction);
          let hit: number | null = null;
          if (obstacles instanceof Occluders) hit = obstacles.nearest(this.ray.ray, this.ray.far);
          else {
            this.hits.length = 0;
            this.ray.intersectObjects(obstacles, true, this.hits);
            if (this.hits.length) hit = this.hits[0].distance;
          }
          if (hit !== null) distance = Math.min(distance, Math.max(0.5, hit - 4));
        }
      }
      if (snap) this.boomDistance = distance;
      else if (distance < this.boomDistance) this.boomDistance = distance;
      else this.boomDistance += (distance - this.boomDistance) * (1 - Math.exp(-8 * delta));
      this.camera.position.copy(this.anchor).addScaledVector(this.direction, this.boomDistance);
      this.aim.set(x + fx * this.profile.lookAhead, y + fy * this.profile.lookAhead, z + eye);
    }
    this.camera.lookAt(this.aim);
    this.camera.updateMatrixWorld();
  }
}
