import { carSprite } from '../assets';
import * as THREE from 'three';
// The race on screen: the world (third or first person), the HUD, the start lights and flag,
// the pause menu, every sound, and the finish. The race itself comes from a RaceSource: run
// right here in single player, or by the server online.
import { app, type Screen, type Stage } from '../app';
import * as audio from '../audio';
import { button, h } from '../dom';
import { NO_CONTROLS, onKey, raceKeys, touchScreen, type Controls } from '../input';
import { nav } from '../nav';
import { pref, setPref } from '../prefs';
import { cameraLabel, nextCameraMode } from '../render/camera';
import { spriteFor } from '../render/cars';
import { World } from '../render/world';
import { ALIVE, Hud, type Standing } from '../ui/hud';
import { StartFlag, StartLights } from '../ui/start';
import { TouchControls } from '../ui/touch';
import type { Event, Item, Snapshot } from '../wasm';
import type { RaceSource } from './session';

/** how far from the course counts as off it (the road is about 125 wide) */
const OFF_TRACK_DISTANCE = 95;
/** seconds the FINISH! label shows before the results */
const END_DELAY = 3;

const ITEM_NAMES: Record<Item, string> = {
  Nitro: 'nitro', Rocket: 'rocket', Oil: 'oil', Bubble: 'bubble', Pulse: 'pulse', Frost: 'frost', Fire: 'fire',
};

/** one racer's line in the results */
export interface Result {
  name: string;
  me: boolean;
  finishTime: number | null;
}

export class RaceStage implements Stage {
  readonly el: HTMLElement;
  private readonly hud: Hud;
  private readonly lights = new StartLights();
  private flag: StartFlag | null = null;
  private readonly pausePanel: HTMLElement;
  private readonly touch: TouchControls | null;
  private readonly engine = new audio.Engine();
  private readonly stopKeys: () => void;
  private paused = false;
  private started = false;
  private finishedAt = -1;
  private countdown = '';
  private itemPressed = false;
  private knockedOut = false;
  private shake = 0;
  private disposed = false;
  private carsKey = '';

  private constructor(private readonly source: RaceSource, private readonly w: World) {
    this.hud = new Hud(source.map, () => this.setPaused(!this.paused), () => this.useItem(), () => this.toggleView(), source.trial, source.best?.time ?? null);
    this.touch = touchScreen ? new TouchControls(!source.trial, () => this.useItem()) : null;
    this.pausePanel = h('div.pause', null,
      h('div.panel', null,
        h('div.title.medium', null, 'PAUSED'),
        h('div.label', { style: { margin: '6px 0 18px' } }, source.online ? 'The race keeps going online' : 'Esc to resume'),
        h('div.col', null,
          button('Resume', () => this.setPaused(false), 'wide'),
          button(source.canRestart ? 'Restart' : 'Leave Race', () => this.restart(), 'wide'),
          button('Main Menu', () => this.quit(), 'wide'),
        ),
      ),
    );
    this.pausePanel.style.display = 'none';
    this.el = h('div.screen.race', { style: { padding: '0' } }, this.hud.el, this.lights.el, this.touch?.el, this.pausePanel);
    this.stopKeys = onKey((code) => this.key(code));
    w.setCameraMode(pref('cameraMode'));
    this.showView();
    this.syncCars();
    audio.stopMusic();
  }

  /** opens the race screen (the flag waves over it first) */
  static async open(source: RaceSource): Promise<RaceStage> {
    const [world] = await Promise.all([World.create(source.map), source.prepare?.()]);
    const race = new RaceStage(source, world);
    race.flag = new StartFlag();
    document.body.append(race.flag.el);
    app.setStage(race);
    // the HTML over the race (the stage itself moves everything on, once a frame)
    const screen: Screen = { el: race.el };
    app.show(screen);
    return race;
  }

  view(): World {
    return this.w;
  }

  /** the cars drawn: everyone in the latest snapshot */
  private syncCars(): void {
    const s = this.source.latest();
    this.w.setCars(s.cars.map((c) => ({ slot: c.slot, sprite: spriteFor(c.slot, this.source.carNumber(c.slot), this.source.map.cpu_sprite) })));
  }

  private key(code: string): void {
    if (code === 'Escape' || code === 'KeyP') this.setPaused(!this.paused);
    else if (this.paused && code === 'KeyR') this.restart();
    else if (this.paused && code === 'KeyQ') this.quit();
    else if (code === 'KeyV') this.toggleView();
    else if (code === 'KeyE') this.useItem();
  }

  private toggleView(): void {
    const mode = nextCameraMode(this.w.cameraMode());
    setPref('cameraMode', mode);
    this.w.setCameraMode(mode);
    this.showView();
  }

  private showView(): void {
    this.el.dataset.cameraMode = this.w.cameraMode();
    this.hud.setView(cameraLabel(this.w.cameraMode()));
  }

  /** the item button: used on the next tick, with its sound right away */
  private useItem(): void {
    const me = this.source.latest().cars.find((c) => c.slot === this.source.mySlot);
    const car = this.source.myCar();
    if (!this.started || this.finishedAt >= 0 || this.paused || !me?.held || (car && car.spin > 0)) return;
    this.itemPressed = true;
    const pitch = audio.random(0.95, 1.08);
    switch (me.held[0]) {
      case 'Rocket': audio.play('rocket', 0.6); break;
      case 'Oil': audio.play('oil', 0.6); break;
      case 'Bubble': audio.play('shield', 0.55); break;
      case 'Pulse': audio.play('pulse', 0.6); break;
      case 'Frost': audio.play('frost_shot', 0.5, pitch); break;
      case 'Fire': audio.play('fire_shot', 0.5, pitch); break;
    }
  }

  private setPaused(value: boolean): void {
    this.paused = value;
    this.pausePanel.style.display = value ? '' : 'none';
    audio.pauseMusic(value);
    if (value) this.engine.stop();
  }

  /** single player: back to a fresh lobby; online: leave the race */
  private restart(): void {
    if (this.source.canRestart) {
      const next = this.source.retry?.();
      this.dispose();
      if (next) void RaceStage.open(next);
    } else this.quit();
  }

  private quit(): void {
    this.dispose();
    void nav.menu();
  }

  private controls(): Controls {
    const car = this.source.myCar();
    const live = this.started && this.finishedAt < 0 && !this.paused && !this.knockedOut && !(car && (car.spin > 0 || car.freeze > 0));
    if (!live) return NO_CONTROLS;
    const keys = raceKeys();
    const c = this.touch?.active() ? this.touch.controls() : keys;
    const item = this.itemPressed || keys.item;
    this.itemPressed = false;
    return { ...c, item: !this.source.trial && item };
  }

  frame(delta: number): void {
    if (this.disposed) return;
    this.source.tick(delta, this.controls(), this.paused);
    const s = this.source.latest();
    const carsKey = s.cars.map((c) => c.slot).join();
    if (carsKey !== this.carsKey) {
      this.carsKey = carsKey;
      this.syncCars();
    }
    for (const e of this.source.takeEvents()) this.event(e, s);
    const me = s.cars.find((c) => c.slot === this.source.mySlot);
    const car = this.source.myCar();
    // the cars, with ours where we see it (predicted online)
    const cars = this.source.cars();
    this.shake = Math.max(0, this.shake - delta * 8);
    const items = this.source.items(cars);
    this.w.update({ cars, items, follow: this.source.mySlot, shake: this.shake }, delta);
    const ghost = this.source.ghost?.();
    this.w.showGhost(ghost ? carSprite(ghost.car) : '', ghost?.frame ?? null);
    // the engine note follows our speed
    if (car && !this.paused && this.finishedAt < 0) {
      const c = this.controls();
      this.engine.update(Math.hypot(car.vel.x, car.vel.y), c.gas, car.boosting);
    } else this.engine.stop();
    this.updateHud(s, delta);
    this.lights.update(this.countdown, this.source.online || !this.paused ? delta : 0, Math.min(1, innerWidth / 1240) / Number(document.getElementById('ui')?.style.zoom || 1));
    this.touch?.update(me?.held ? ITEM_NAMES[me.held[0]] : null, !this.paused && this.finishedAt < 0, delta, car ? Math.hypot(car.vel.x, car.vel.y) : 0, car?.mana ?? 100);
    if (this.flag) {
      this.flag.update(delta);
      if (this.flag.done) this.flag = null;
    }
    // the results, a moment after crossing the line
    if (this.finishedAt >= 0 && !this.paused) {
      this.finishedAt += delta;
      if (this.finishedAt >= END_DELAY) this.end(s);
    }
  }

  private updateHud(s: Snapshot, delta: number): void {
    const battle = this.source.map.kind === 'battle';
    const order = s.order.map((slot) => s.cars.find((c) => c.slot === slot)!).filter(Boolean);
    const standings: Standing[] = order.map((c) => ({
      slot: c.slot,
      name: this.source.name(c.slot),
      me: c.slot === this.source.mySlot,
      cpu: this.source.carNumber(c.slot) === 0,
      laps: c.laps,
      progress: battle ? ALIVE + c.hp : c.progress,
      finishTime: c.finish_time ?? null,
      hp: c.hp,
      out: c.out,
      kills: c.kills,
    }));
    const me = s.cars.find((c) => c.slot === this.source.mySlot);
    const car = this.source.myCar();
    this.hud.update({
      standings,
      laps: s.laps,
      courseLength: s.course_length,
      raceTime: me?.finish_time ?? s.race_time,
      speed: car ? Math.hypot(car.vel.x, car.vel.y) : 0,
      boost: car ? car.mana / 100 : 0,
      item: me?.held ? ITEM_NAMES[me.held[0]] : null,
      ammo: me?.held && (me.held[0] === 'Frost' || me.held[0] === 'Fire') ? me.held[1] : 0,
      offTrack: this.started && this.finishedAt < 0 && !battle && (me?.off_course ?? 0) > OFF_TRACK_DISTANCE,
      paused: this.paused,
      dots: s.cars.map((c) => ({ x: c.car.pos.x, y: c.car.pos.y, me: c.slot === this.source.mySlot, cpu: this.source.carNumber(c.slot) === 0 })),
    }, delta);
    this.drawBattleMarks(s);
  }

  /** what happened in the race: sounds, the countdown, our finish */
  private event(e: Event, s: Snapshot): void {
    const mine = (car: number) => car === this.source.mySlot;
    if (e === 'Go') {
      this.countdown = 'GO!';
      this.started = true;
      audio.playMusic(this.source.map.kind === 'battle' ? 'music_battle' : 'music_race');
    } else if (e === 'BattleOver') {
      if (this.finishedAt < 0) this.finish(s);
    } else if (typeof e === 'object') {
      if ('Countdown' in e) this.countdown = String(e.Countdown);
      else if ('Nitro' in e && mine(e.Nitro.car)) audio.play('nitro', 0.6);
      else if ('Blocked' in e && mine(e.Blocked.car)) audio.play('block', 0.6);
      else if ('Hit' in e && mine(e.Hit.car)) {
        const sound = { Spin: ['spin', 0.5, 1], Freeze: ['freeze', 0.6, 1], Burn: ['burn', 0.6, 1], Slow: ['pulse', 0.5, 0.8] } as const;
        const [name, vol, pitch] = sound[e.Hit.effect];
        audio.play(name, vol, pitch);
      } else if ('Damage' in e && mine(e.Damage.car)) {
        this.hud.flashDamage();
        audio.play('hit', 0.6, audio.random(0.9, 1.1));
      } else if ('Out' in e) {
        if (mine(e.Out.car)) {
          audio.play('knockout', 0.8);
          this.knockedOut = true;
          this.hud.showLabel('KNOCKED OUT', '#fa8072');
        } else audio.play('knockout', 0.4, 1.15);
      } else if ('Crash' in e && mine(e.Crash.car)) {
        audio.play('crash', 0.55, audio.random(0.85, 1.15));
        this.shake = 1.5;
      } else if ('Finish' in e && mine(e.Finish.car) && this.finishedAt < 0) this.finish(s);
    }
  }

  private finish(s: Snapshot): void {
    this.finishedAt = 0;
    this.engine.stop();
    audio.stopMusic();
    audio.play('finish', 0.6);
    if (this.source.map.kind === 'battle') {
      this.hud.showLabel(s.order[0] === this.source.mySlot ? 'WINNER!' : 'BATTLE OVER');
    } else this.hud.showLabel('FINISH!');
  }

  private end(s: Snapshot): void {
    const results: Result[] = s.order.map((slot) => {
      const c = s.cars.find((x) => x.slot === slot)!;
      return { name: this.source.name(slot), me: slot === this.source.mySlot, finishTime: c.finish_time ?? null };
    });
    const source = this.source;
    this.dispose(false);
    void import('../ui/end').then(({ EndScreen }) => {
      app.show(new EndScreen(results, source));
    });
  }

  /** battle: health bars over the cars, and arrows at the screen's edge for opponents out of sight */
  private drawBattleMarks(s: Snapshot): void {
    const canvas = this.hud.marks;
    if (this.source.map.kind !== 'battle') return;
    const ratio = Math.min(2, devicePixelRatio);
    const W = innerWidth, H = innerHeight;
    if (canvas.width !== Math.round(W * ratio)) {
      canvas.width = Math.round(W * ratio);
      canvas.height = Math.round(H * ratio);
    }
    const g = canvas.getContext('2d')!;
    g.setTransform(ratio, 0, 0, ratio, 0, 0);
    g.clearRect(0, 0, W, H);
    const u = Math.min(1, W / 900, H / 620);
    const fp = this.w.cameraMode() === 'firstPerson';
    for (const c of s.cars) {
      if (c.out) continue;
      const hp = Math.max(0, Math.min(1, c.hp / 100));
      const p = this.w.project(c.car.pos.x, c.car.pos.y, fp ? 9 : 0);
      if (p && !(fp && c.slot === this.source.mySlot)) {
        // a little health bar over every car still in
        const w = 34 * u, hh = 5 * u, x = p.x * W - w / 2, y = p.y * H - (fp ? 0 : 22 * u) - hh;
        g.fillStyle = 'rgba(26,18,13,0.85)';
        g.fillRect(x - u, y - u, w + 2 * u, hh + 2 * u);
        g.fillStyle = hp > 0.5 ? 'rgb(89,217,77)' : hp > 0.25 ? 'rgb(242,191,77)' : 'rgb(242,77,77)';
        g.fillRect(x, y, w * hp, hh);
      }
      if (c.slot === this.source.mySlot || p) continue;
      // pin an arrow to the edge, along the line from the middle of the screen
      const me = s.cars.find((x) => x.slot === this.source.mySlot);
      if (!me) continue;
      // Bearing in the active camera's frame, including chase-camera turn smoothing.
      const cam = this.w.camera();
      const forward = cam.getWorldDirection(new THREE.Vector3());
      const heading = Math.atan2(forward.y, forward.x);
      const wx = c.car.pos.x - me.car.pos.x, wy = c.car.pos.y - me.car.pos.y;
      const dx = wx * Math.sin(heading) - wy * Math.cos(heading);
      const dy = -(wx * Math.cos(heading) + wy * Math.sin(heading));
      const margin = 46 * u, top = Math.min(H * 0.45, 290 * u);
      const cx = W / 2, cy = (top + H - margin) / 2, halfW = cx - margin, halfH = (H - margin - top) / 2;
      const t = Math.min(halfW / Math.max(1e-3, Math.abs(dx)), halfH / Math.max(1e-3, Math.abs(dy)));
      const ax = cx + dx * t, ay = cy + dy * t, len = Math.hypot(dx, dy), nx = dx / len, ny = dy / len;
      const near = Math.max(0.45, Math.min(1, 1 - (len - 450) / 1350));
      const tri = (size: number, color: string) => {
        g.fillStyle = color;
        g.beginPath();
        g.moveTo(ax + nx * size, ay + ny * size);
        g.lineTo(ax - nx * size * 0.6 - ny * size * 0.8, ay - ny * size * 0.6 + nx * size * 0.8);
        g.lineTo(ax - nx * size * 0.6 + ny * size * 0.8, ay - ny * size * 0.6 - nx * size * 0.8);
        g.fill();
      };
      tri(26 * u * near + 3 * u, 'rgba(26,18,13,0.85)');
      tri(26 * u * near, `rgba(242,${Math.round(77 + 102 * (1 - near))},51,0.95)`);
    }
  }

  dispose(stopSource = true): void {
    if (this.disposed) return;
    this.disposed = true;
    this.stopKeys();
    this.engine.stop();
    this.hud.dispose();
    this.flag?.el.remove();
    this.w.dispose();
    if (stopSource) this.source.dispose();
  }
}
