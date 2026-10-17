// The WebGL canvas. Colour management is off: textures and colours go to the screen exactly
// as they are in the pictures, like the original game.
import * as THREE from 'three';
/** something the renderer can draw: a scene through a camera */
export interface View {
  readonly scene: THREE.Scene;
  camera(): THREE.Camera;
  resize(width: number, height: number): void;
}

THREE.ColorManagement.enabled = false;

export class Renderer {
  readonly gl: THREE.WebGLRenderer;
  width = 1;
  height = 1;

  constructor(canvas: HTMLCanvasElement) {
    this.gl = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
    this.gl.outputColorSpace = THREE.LinearSRGBColorSpace;
    this.gl.setClearColor(0x0a0a0c);
    this.resize();
  }

  resize(): void {
    this.width = innerWidth;
    this.height = innerHeight;
    this.gl.setPixelRatio(Math.min(devicePixelRatio, 2));
    this.gl.setSize(this.width, this.height, false);
  }

  render(view: View | null): void {
    if (!view) {
      this.gl.clear();
      return;
    }
    view.resize(this.width, this.height);
    this.gl.render(view.scene, view.camera());
  }
}
