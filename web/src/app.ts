// The game's frame: one WebGL canvas showing a world (the title race, a drifting map, or the
// race itself), with the menus and HUD in HTML on top. The current stage moves its world on
// every frame; the current screen is the HTML over it.
import * as audio from './audio';
import { onKey } from './input';
import { Renderer, type View } from './render/renderer';

/** something moving in the canvas */
export interface Stage {
  /** moves on by `delta` seconds (real time) */
  frame(delta: number): void;
  view(): View | null;
  dispose(): void;
}

/** the HTML over the canvas */
export interface Screen {
  readonly el: HTMLElement;
  /** called every frame while it's showing */
  frame?(delta: number): void;
  dispose?(): void;
}

class App {
  private renderer!: Renderer;
  private ui!: HTMLElement;
  private stage: Stage | null = null;
  private screen: Screen | null = null;
  private last = performance.now();

  start(): void {
    this.renderer = new Renderer(document.getElementById('world') as HTMLCanvasElement);
    this.ui = document.getElementById('ui')!;
    addEventListener('resize', () => this.renderer.resize());
    // M toggles the sound anywhere
    onKey((code) => {
      if (code === 'KeyM') audio.setMuted(!audio.isMuted());
    });
    requestAnimationFrame((t) => this.loop(t));
  }

  /** what's moving behind the menus (the old one is thrown away unless it's the same) */
  setStage(stage: Stage | null): void {
    if (stage === this.stage) return;
    this.stage?.dispose();
    this.stage = stage;
  }

  currentStage(): Stage | null {
    return this.stage;
  }

  show(screen: Screen): void {
    this.screen?.dispose?.();
    this.screen = screen;
    this.ui.replaceChildren(screen.el);
    document.body.classList.toggle('menu-muted', screen.el.classList.contains('frosted'));
  }

  private loop(now: number): void {
    // a long pause (another tab) counts as one ordinary frame, not a jump
    const delta = Math.min(0.1, Math.max(0, (now - this.last) / 1000));
    this.last = now;
    this.stage?.frame(delta);
    this.screen?.frame?.(delta);
    this.renderer.render(this.stage?.view() ?? null);
    requestAnimationFrame((t) => this.loop(t));
  }
}

export const app = new App();
