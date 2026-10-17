// A fixed track grid shared by solo races and multiplayer room creation. In single player
// there are also tabs for the modes that aren't races on a map, Parking and Police Chase,
// each with one card.
import type { Screen } from '../app';
import { asset } from '../assets';
import * as audio from '../audio';
import { button, h } from '../dom';
import { allMaps } from '../game/backdrops';
import { online } from '../game/rooms';
import { net } from '../net';
import { nav } from '../nav';
import { pref } from '../prefs';
import type { MapDef, MapKind, ServerMsg } from '../wasm';
import { card, cardTitle, centered, kicker, smallButton } from './cards';

type Mode = 'parking' | 'chase';
type Tab = MapKind | Mode;
const TABS: [Tab, string][] = [
  ['circuit', 'Circuits'], ['sprint', 'Sprints'], ['battle', 'Battles'], ['parking', 'Parking'], ['chase', 'Chase'],
];
const MODES: Record<Mode, [string, string]> = {
  parking: ['PARKING', 'Park in the bay without a scratch. Six levels, three stars each.'],
  chase: ['POLICE CHASE', 'Outrun the cops down an endless city street. Dodge, jump, grab coins.'],
};
const isMode = (t: Tab): t is Mode => t in MODES;
let lastMap = 'classic';

export class MapSelectScreen implements Screen {
  readonly el: HTMLElement;
  private readonly maps: MapDef[];
  private readonly tabDefs: typeof TABS;
  private selected: string;
  private readonly tiles = new Map<string, HTMLElement>();
  private readonly tabs: HTMLButtonElement[] = [];
  private readonly grid = h('div.cards');
  private readonly note = h('div.note');
  private readonly kickerEl: HTMLElement;
  private readonly stop: (() => void) | null = null;
  private creating = false;
  private tab: Tab = 'circuit';

  constructor(private readonly hosting: false | 'public' | 'private') {
    audio.playMusic('music_title');
    this.maps = allMaps().filter((m) => hosting || m.kind !== 'battle');
    // battles are online only; parking and the chase are single player only
    this.tabDefs = TABS.filter(([kind]) => (hosting ? !isMode(kind) : kind !== 'battle'));
    this.selected = this.maps.some((m) => m.id === lastMap) ? lastMap : this.maps[0].id;
    this.kickerEl = kicker(!hosting ? 'SINGLE PLAYER' : hosting === 'public' ? 'HOST A PUBLIC RACE' : 'HOST A PRIVATE RACE');
    this.tabDefs.forEach(([kind, label]) => this.tabs.push(smallButton(label, () => this.showTab(kind))));
    this.el = centered(card(
      this.kickerEl,
      cardTitle('CHOOSE A TRACK', 40),
      h('div.tabs', { style: { flexWrap: 'wrap', justifyContent: 'flex-start' } }, ...this.tabs),
      h('div.scroll.track-scroll', null, this.grid),
      this.note,
      h('div.card-buttons', null,
        smallButton('Back', () => (hosting ? void nav.multiplayer() : void nav.menu())),
        h('div.spacer'),
        button(hosting ? 'Create' : 'Next', () => this.go()),
      ),
    ));
    this.el.querySelector<HTMLElement>('.card-panel')!.classList.add('track-picker');
    this.showTab(this.map(this.selected).kind);
    if (hosting) {
      this.stop = net.on((msg) => this.message(msg));
      net.connect();
    }
  }

  private map(id: string): MapDef {
    return this.maps.find((m) => m.id === id) ?? this.maps[0];
  }

  private showTab(kind: Tab): void {
    this.tab = kind;
    this.tabDefs.forEach(([k], i) => {
      this.tabs[i].classList.toggle('on', k === kind);
      this.tabs[i].setAttribute('aria-pressed', String(k === kind));
    });
    if (isMode(kind)) {
      this.grid.replaceChildren(this.modeTile(kind));
      return;
    }
    // highlight the first map of the tab unless the picked one is already on it
    if (this.map(this.selected).kind !== kind) this.selected = this.maps.find((m) => m.kind === kind)!.id;
    this.grid.replaceChildren(...this.maps.filter((m) => m.kind === kind).map((m) => this.tile(m)));
    this.select(this.selected);
  }

  private tile(m: MapDef): HTMLElement {
    let t = this.tiles.get(m.id);
    if (!t) {
      t = h('div.card', {
        onclick: () => {
          audio.play('click', 0.4);
          if (this.selected === m.id) this.go();
          else this.select(m.id);
        },
        role: 'button', tabIndex: 0,
        onkeydown: (e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this.select(m.id); } },
      },
        h('img', { src: asset(`maps/${m.id}/preview.webp`), alt: m.name, loading: 'lazy', style: { imageRendering: 'auto' } }),
        h('div.name', null, m.name.toUpperCase()),
      );
      this.tiles.set(m.id, t);
    }
    return t;
  }

  /** the one card of a mode tab (tap it twice, or Next) */
  private modeTile(mode: Mode): HTMLElement {
    const [name, text] = MODES[mode];
    let last = 0;
    return h('div.card.on', {
      onclick: () => {
        audio.play('click', 0.4);
        const now = performance.now();
        if (now - last < 500) this.go();
        last = now;
      },
      style: { width: '210px' },
    },
      h('img', { src: modePicture(mode), alt: name, style: { imageRendering: 'pixelated' } }),
      h('div.name', null, name),
      h('div.muted', { style: { textAlign: 'left', fontSize: '9px' } }, text),
    );
  }

  private select(id: string): void {
    this.selected = id;
    for (const [mid, t] of this.tiles) t.classList.toggle('on', mid === id);
  }

  private go(): void {
    if (isMode(this.tab)) {
      void (this.tab === 'parking' ? nav.parking() : nav.chase());
      return;
    }
    lastMap = this.selected;
    if (!this.hosting) {
      void nav.localLobby(this.selected);
      return;
    }
    // the server makes the room; we move on once we're in it
    if (this.creating) return;
    if (!net.send({ Create: { visibility: this.hosting === 'public' ? 'Public' : 'Private', map: this.selected, car: pref('car') } })) {
      this.kickerEl.textContent = 'CONNECTING TO THE SERVER...';
      return;
    }
    this.creating = true;
    this.kickerEl.textContent = 'CREATING THE RACE...';
  }

  private message(msg: ServerMsg): void {
    if (typeof msg !== 'object') return;
    if ('Joined' in msg && this.creating) void nav.onlineLobby(online.room!.mapId);
    else if ('Error' in msg) {
      this.creating = false;
      this.kickerEl.textContent = msg.Error.toUpperCase();
      this.kickerEl.classList.add('error');
    }
  }

  dispose(): void {
    this.stop?.();
  }
}

/** a little pixel picture for a mode card, drawn here (160 x 90) */
function modePicture(mode: Mode): string {
  const c = document.createElement('canvas');
  c.width = 160;
  c.height = 90;
  const g = c.getContext('2d')!;
  const fill = (x: number, y: number, w: number, hh: number, col: string) => {
    g.fillStyle = col;
    g.fillRect(x, y, w, hh);
  };
  if (mode === 'parking') {
    fill(0, 0, 160, 90, '#5c5c61');
    const cars = ['#d9433b', '#3f73e6', '#f2c84b', '#e6e6e6', '#33a666'];
    for (let i = 0; i < 7; i++) {
      const x = 6 + i * 22;
      g.strokeStyle = '#fff';
      g.strokeRect(x + 0.5, 8.5, 22, 36);
      if (i === 4) fill(x + 2, 10, 19, 33, '#33e666');
      else {
        fill(x + 5, 12, 12, 26, cars[i % cars.length]);
        fill(x + 6, 18, 10, 6, '#1a1f29');
      }
    }
    fill(98, 56, 12, 26, '#d9433b');
    fill(99, 60, 10, 6, '#1a1f29');
  } else {
    fill(0, 0, 160, 90, '#99c7f5');
    for (let y = 30; y < 90; y++) {
      const half = Math.round(6 + ((y - 30) / 60) * 70);
      fill(0, y, 160, 1, '#b8b8b3');
      fill(80 - half, y, half * 2, 1, '#45454a');
      if ((y >> 2) % 2 === 0) {
        fill(80 - Math.round(half / 3), y, 1, 1, '#fff');
        fill(80 + Math.round(half / 3), y, 1, 1, '#fff');
      }
    }
    const walls = ['#eddbbd', '#c7d6eb', '#ebc2b3'];
    walls.forEach((col, i) => {
      fill(i * 14, 8 + i * 6, 14, 60, col);
      fill(146 - i * 14, 8 + i * 6, 14, 60, col);
    });
    fill(72, 58, 16, 12, '#d9403a');
    fill(68, 76, 24, 14, '#f2f2f2');
    fill(72, 72, 7, 4, '#e61a1a');
    fill(81, 72, 7, 4, '#3366ff');
  }
  return c.toDataURL();
}
