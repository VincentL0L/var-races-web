// Shared ready-screen presentation; controllers own local simulation or server messages.
import type { Screen } from '../app';
import * as audio from '../audio';
import { button, h } from '../dom';
import { mapById } from '../game/backdrops';
import { OnlineSource } from '../game/online';
import { RaceStage } from '../game/race';
import { leaveRoom, online } from '../game/rooms';
import { LocalSource } from '../game/session';
import { readBest, recordKey } from '../game/records';
import { carSprite, cpuSprite } from '../assets';
import { net } from '../net';
import { nav } from '../nav';
import { pref, setPref } from '../prefs';
import type { MapDef, ServerMsg, Settings } from '../wasm';
import { card, cardTitle, carIcon, centered, kicker, picker, pill, row, smallButton } from './cards';
import { formatTime } from './hud';

const LAP_CHOICES = [1, 2, 3, 5];
const DIFFICULTIES = ['EASY', 'NORMAL', 'HARD', 'EXTRA HARD'];
interface Participant { sprite: string; name: string; you: boolean; status: 'READY' | 'NOT READY' | 'CPU' }

abstract class LobbyCard implements Screen {
  readonly el: HTMLElement;
  protected readonly map: MapDef;
  protected readonly kickerEl: HTMLElement;
  protected readonly drivers = h('div.lobby-drivers');
  protected readonly note = h('div.note');
  protected readonly ready: HTMLButtonElement;
  protected readonly laps = picker('LAPS', (d) => {
    const i = Math.max(0, LAP_CHOICES.indexOf(this.settings.laps));
    this.changeSettings({ ...this.settings, laps: LAP_CHOICES[(i + d + LAP_CHOICES.length) % LAP_CHOICES.length] });
  });
  private readonly difficulty = picker('CPU DIFFICULTY', (d) => this.changeSettings({ ...this.settings, difficulty: (this.settings.difficulty + d + 4) % 4 }));
  private readonly addCpu = smallButton('Add CPU', () => this.changeSettings({ ...this.settings, cpu_count: Math.min(3, this.settings.cpu_count + 1) }));
  protected readonly extra = h('div');
  protected settings: Settings = { laps: 1, difficulty: 1, cpu_count: 0 };
  private driversKey = '';

  constructor(mapId: string, kickerText: string, title: string, private readonly solo: boolean) {
    audio.playMusic('music_title');
    this.map = mapById(mapId);
    this.kickerEl = kicker(kickerText);
    this.ready = button('Ready', () => this.onReady());
    if (!solo) this.drivers.classList.add('multiplayer-drivers');
    this.el = centered(card(this.kickerEl, cardTitle(title), kicker('DRIVERS'), this.drivers,
      h('div.lobby-add-cpu', null, this.addCpu),
      kicker('RACE SETTINGS'), this.map.kind === 'circuit' && this.laps.el,
      this.difficulty.el, this.extra, this.note,
      h('div.card-buttons', null, smallButton(solo ? 'Back' : 'Leave', () => this.onLeave()), h('div.spacer'), this.ready)));
  }
  protected abstract onReady(): void;
  protected abstract onLeave(): void;
  protected abstract changeSettings(settings: Settings): void;

  protected renderView(participants: Participant[], canChange: boolean, waiting: boolean): void {
    const key = JSON.stringify([participants, canChange]);
    if (key !== this.driversKey) {
      this.driversKey = key;
      this.drivers.replaceChildren(...participants.map((p) => {
        const remove = smallButton('×', () => this.changeSettings({ ...this.settings, cpu_count: Math.max(0, this.settings.cpu_count - 1) }));
        remove.classList.add('remove-cpu');
        remove.setAttribute('aria-label', `Remove ${p.name}`);
        remove.title = `Remove ${p.name}`;
        remove.disabled = !canChange;
        return row(carIcon(p.sprite),
        h(p.you ? 'span.grow.gold' : 'span.grow', null, p.name),
        pill(p.status, p.status === 'CPU' ? 'cpu' : p.status === 'READY' ? 'ready' : ''),
        p.status === 'CPU' && remove);
      }));
    }
    const { laps, difficulty, cpu_count } = this.settings;
    this.laps.value.textContent = `${laps} ${laps === 1 ? 'LAP' : 'LAPS'}`;
    this.addCpu.disabled = !canChange || cpu_count >= 3;
    this.addCpu.textContent = cpu_count >= 3 ? '3 / 3 CPUs' : 'Add CPU';
    this.addCpu.title = cpu_count >= 3 ? 'Maximum of 3 CPUs' : 'Add a CPU driver';
    this.difficulty.value.textContent = DIFFICULTIES[difficulty];
    this.difficulty.value.style.color = difficulty === 0 ? '#45a852' : difficulty === 3 ? '#d966ff' : difficulty === 2 ? '#ff8066' : '';
    for (const control of [this.laps, this.difficulty]) {
      control.arrows.forEach((b) => { b.disabled = !canChange; });
    }
    this.difficulty.arrows.forEach((b) => { b.disabled ||= cpu_count === 0; });
    const insufficient = this.map.kind === 'battle' && participants.length < 2;
    this.ready.disabled = waiting || insufficient;
    this.ready.textContent = waiting ? 'Waiting' : 'Ready';
    this.note.textContent = insufficient ? 'Add a CPU or invite another driver to battle.' : !this.solo && !canChange ? 'The host picks race settings.'
      : this.solo && cpu_count === 0 ? 'No CPUs: a time trial against your best (and its ghost).' : '';
  }
}

export class LocalLobbyScreen extends LobbyCard {
  private starting = false;
  private disposed = false;
  private lookup = 0;
  private readonly bestLabel = h('div.note');
  private readonly ghost = smallButton('', () => { setPref('ghost', !pref('ghost')); this.updateGhost(); });
  private hasGhost = false;

  constructor(mapId: string) {
    super(mapId, 'SINGLE PLAYER', mapById(mapId).name.toUpperCase(), true);
    this.extra.append(this.bestLabel, this.ghost);
    this.render();
  }
  private updateGhost(): void {
    this.ghost.textContent = `Ghost: ${pref('ghost') ? 'On' : 'Off'}`;
    this.ghost.disabled = !this.hasGhost || this.starting;
    this.ghost.setAttribute('aria-pressed', String(pref('ghost')));
  }
  private render(): void {
    const participants: Participant[] = [{ sprite: carSprite(pref('car')), name: 'YOU', you: true, status: this.starting ? 'READY' : 'NOT READY' }];
    for (let i = 0; i < this.settings.cpu_count; i++) participants.push({ sprite: cpuSprite(this.map.cpu_sprite, i), name: `${this.map.cpu_name} ${i + 1}`, you: false, status: 'CPU' });
    this.renderView(participants, !this.starting, this.starting);
    // personal bests and the ghost are for time trials (no CPUs)
    this.extra.style.display = this.settings.cpu_count ? 'none' : '';
    const lookup = ++this.lookup;
    this.hasGhost = false;
    this.bestLabel.textContent = 'Personal best: —';
    this.updateGhost();
    void readBest(recordKey(this.map.id, this.settings.laps, pref('car'))).then((best) => {
      if (this.disposed || lookup !== this.lookup) return;
      this.bestLabel.textContent = `Personal best: ${best ? formatTime(best.time) : '—'} · saved in this browser`;
      this.hasGhost = !!best?.samples.length;
      this.updateGhost();
    });
  }
  protected changeSettings(settings: Settings): void { if (!this.starting) { this.settings = settings; this.render(); } }
  protected onReady(): void {
    if (this.starting) return;
    this.starting = true;
    audio.play('ready', 0.5);
    this.render();
    const { laps, cpu_count, difficulty } = this.settings;
    void RaceStage.open(new LocalSource(this.map, pref('car'), laps, cpu_count, difficulty));
  }
  protected onLeave(): void { if (!this.starting) void nav.mapSelect(); }
  dispose(): void { this.disposed = true; }
}

export class OnlineLobbyScreen extends LobbyCard {
  private readonly stop: () => void;
  private readonly stopClose: () => void;
  private pendingReady = false;
  private starting = false;
  private disconnected = false;

  constructor(mapId: string) {
    const room = online.room!;
    super(mapId, `${room.visibility === 'Public' ? 'PUBLIC ROOM' : 'PRIVATE ROOM'}  /  ${mapById(mapId).name.toUpperCase()}`, room.code, false);
    this.stop = net.on((msg) => this.message(msg));
    this.stopClose = net.onClose(() => {
      this.disconnected = true;
      this.kickerEl.textContent = (net.error ?? 'Lost connection').toUpperCase();
      this.kickerEl.classList.add('error');
      this.ready.disabled = true;
    });
    this.render();
  }
  private message(msg: ServerMsg): void {
    if (typeof msg !== 'object') return;
    if ('Lobby' in msg || 'Error' in msg) this.pendingReady = false;
    if ('Race' in msg && !this.starting) {
      this.starting = true;
      const room = online.room!;
      void RaceStage.open(new OnlineSource(this.map, room.slot, online.lobby?.players ?? [], msg));
    }
  }
  private render(): void {
    const room = online.room, lobby = online.lobby;
    if (!room || !lobby) { this.ready.disabled = true; return; }
    this.settings = lobby.settings;
    const players = [...lobby.players].sort((a, b) => Number(b.slot === room.slot) - Number(a.slot === room.slot));
    const participants: Participant[] = players.map((p) => ({ sprite: carSprite(p.car), name: p.slot === room.slot ? 'YOU' : p.name.toUpperCase(), you: p.slot === room.slot, status: p.ready ? 'READY' : 'NOT READY' }));
    for (let i = 0; i < this.settings.cpu_count; i++) participants.push({ sprite: cpuSprite(this.map.cpu_sprite, i), name: `${this.map.cpu_name} ${i + 1}`, you: false, status: 'CPU' });
    const me = players.find((p) => p.slot === room.slot);
    this.renderView(participants, lobby.host === room.slot && !this.starting && !this.disconnected, this.pendingReady || !!me?.ready || this.starting || this.disconnected);
  }
  protected changeSettings(settings: Settings): void { net.send({ Settings: settings }); }
  protected onReady(): void {
    if (this.pendingReady || this.starting || this.disconnected) return;
    this.pendingReady = net.send({ Ready: true });
    audio.play('ready', 0.5);
  }
  protected onLeave(): void { if (!this.starting) { leaveRoom(); void nav.multiplayer(); } }
  frame(): void { this.render(); }
  dispose(): void { this.stop(); this.stopClose(); }
}
