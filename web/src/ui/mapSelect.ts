// Choosing a track: tabs for circuits, sprints and battle arenas, a grid of map cards, and the
// view to race in (overhead or first person). Hosting online, Create asks the server for a room.
import type { Screen } from '../app';
import { asset } from '../assets';
import * as audio from '../audio';
import { button, h } from '../dom';
import { allMaps } from '../game/backdrops';
import { online } from '../game/rooms';
import { net } from '../net';
import { nav } from '../nav';
import { pref, setPref } from '../prefs';
import type { MapDef, MapKind, ServerMsg } from '../wasm';
import { card, cardTitle, centered, kicker, smallButton } from './cards';

const TABS: [MapKind, string, string][] = [
  ['circuit', 'Circuits', ''],
  ['sprint', 'Sprints', 'One run from start to finish. No laps.'],
  ['battle', 'Battle', 'Demolition derby: ram everyone, last car driving wins. Heavy cars hit harder.'],
];
let lastMap = 'classic';

export class MapSelectScreen implements Screen {
  readonly el: HTMLElement;
  private readonly maps = allMaps();
  private selected: string;
  private readonly tiles = new Map<string, HTMLElement>();
  private readonly tabs: HTMLButtonElement[] = [];
  private readonly grid = h('div.cards');
  private readonly note = h('div.note');
  private readonly kickerEl: HTMLElement;
  private readonly stop: (() => void) | null = null;
  private creating = false;

  constructor(private readonly hosting: false | 'public' | 'private') {
    audio.playMusic('music_title');
    this.selected = lastMap;
    this.kickerEl = kicker(!hosting ? 'SINGLE PLAYER' : hosting === 'public' ? 'HOST A PUBLIC RACE' : 'HOST A PRIVATE RACE');
    TABS.forEach(([kind, label]) => this.tabs.push(smallButton(label, () => this.showTab(kind))));
    const view = smallButton(viewText(), () => {
      setPref('firstPerson', !pref('firstPerson'));
      view.textContent = viewText();
    });
    view.style.minWidth = '230px';
    this.el = centered(card(
      this.kickerEl,
      h('div.row', { style: { justifyContent: 'space-between', flexWrap: 'nowrap' } }, cardTitle('CHOOSE A TRACK', 40), h('div.tabs', null, ...this.tabs)),
      h('div.scroll', null, this.grid),
      this.note,
      h('div.card-buttons', null,
        smallButton('Back', () => (hosting ? void nav.multiplayer() : void nav.menu())),
        h('div.spacer'),
        view,
        button(hosting ? 'Create' : 'Next', () => this.go()),
      ),
    ));
    this.el.querySelector<HTMLElement>('.card-panel')!.classList.add('wide');
    this.showTab(this.map(this.selected).kind);
    if (hosting) {
      this.stop = net.on((msg) => this.message(msg));
      net.connect();
    }
  }

  private map(id: string): MapDef {
    return this.maps.find((m) => m.id === id) ?? this.maps[0];
  }

  private showTab(kind: MapKind): void {
    TABS.forEach(([k, , note], i) => {
      this.tabs[i].classList.toggle('on', k === kind);
      if (k === kind) this.note.textContent = note;
    });
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
        style: { width: '210px' },
      },
        h('img', { src: asset(`maps/${m.id}/preview.webp`), alt: m.name, loading: 'lazy', style: { imageRendering: 'auto' } }),
        h('div.name', null, m.name.toUpperCase()),
        h('div.muted', { style: { textAlign: 'left', fontSize: '9px' } }, m.tagline),
      );
      this.tiles.set(m.id, t);
    }
    return t;
  }

  private select(id: string): void {
    this.selected = id;
    for (const [mid, t] of this.tiles) t.classList.toggle('on', mid === id);
  }

  private go(): void {
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

function viewText(): string {
  return pref('firstPerson') ? 'View: 1st Person' : 'View: Overhead';
}
