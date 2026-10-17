import * as THREE from 'three';
import { CarView } from './cars';

/** Small, independently owned garage scene; uses exactly the gameplay car models. */
export class CarPreview {
  readonly canvas = document.createElement('canvas');
  private readonly gl: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera = new THREE.PerspectiveCamera(38, 1, 0.1, 150);
  private model: CarView | null = null;
  private width = 0;
  private height = 0;
  constructor() {
    this.canvas.className = 'garage-preview';
    this.canvas.setAttribute('aria-label', 'Front-left 3D car preview');
    this.gl = new THREE.WebGLRenderer({ canvas: this.canvas, alpha: true, antialias: true });
    this.gl.outputColorSpace = THREE.LinearSRGBColorSpace;
    this.gl.setPixelRatio(Math.min(devicePixelRatio, 2));
    this.camera.up.set(0, 0, 1);
    this.camera.position.set(-27, 34, 18);
    this.camera.lookAt(0, 0, 3.5);
  }
  show(sprite: string): void {
    if (this.model?.spriteName === sprite) return;
    if (this.model) { this.scene.remove(this.model.root); this.model.dispose(); }
    this.model = new CarView(sprite);
    this.scene.add(this.model.root);
  }
  frame(_delta: number): void {
    const { width, height } = this.canvas.getBoundingClientRect();
    if (!width || !height) return;
    if (width !== this.width || height !== this.height) {
      this.width = width; this.height = height;
      this.gl.setSize(width, height, false);
      this.camera.aspect = width / height;
      this.camera.updateProjectionMatrix();
    }
    this.model?.update(0, 0, Math.PI / 2, { frozen: false, burning: 0 }, 0);
    this.gl.render(this.scene, this.camera);
  }
  dispose(): void {
    this.model?.dispose();
    this.scene.clear();
    this.gl.dispose();
    this.gl.forceContextLoss();
  }
}
