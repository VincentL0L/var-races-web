// The online menu: open public races, hosting a public
// or private race, and joining a friend's race with its code.
import type { Screen } from '../app';
import * as audio from '../audio';
import { button, h } from '../dom';
import { mapById } from '../game/backdrops';
import { online } from '../game/rooms';
import { net, WAKING_AFTER } from '../net';
import { nav } from '../nav';
import { pref } from '../prefs';
import type { RoomInfo, ServerMsg } from '../wasm';
import { card, cardTitle, centered, kicker, row, smallButton } from './cards';

const REFRESH_TIME = 2;
const BLOCKED_AFTER = 75;
const MAX_ROWS = 3;
const MAX_PLAYERS = 6;

export class MultiplayerScreen implements Screen {
  readonly el: HTMLElement;
  private readonly kickerEl = kicker('CONNECTING...');
  private readonly rooms = h('div');
  private readonly code = h('input.field', { placeholder: 'ABCD', maxLength: 4, style: { width: '100%', textAlign: 'center', textTransform: 'uppercase' } });
  private readonly actions: HTMLButtonElement[];
  private readonly stop: () => void;
  private refresh = REFRESH_TIME;
  private shownRooms = '';
  private list: RoomInfo[] = [];

  constructor() {
    audio.playMusic('music_title');
    net.connect();
    const pub = button('Public', () => this.host('public'));
    const priv = button('Private', () => this.host('private'));
    const join = button('Join', () => this.join(this.code.value));
    this.code.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') this.join(this.code.value);
    });
    this.actions = [pub, priv, join];
    for (const b of this.actions) b.style.flex = '1';
    this.el = centered(card(
      this.kickerEl,
      cardTitle('ONLINE RACE'),
      kicker('OPEN RACES'), this.rooms,
      kicker('HOST A RACE'), h('div.row', { style: { flexWrap: 'nowrap' } }, pub, priv),
      kicker('JOIN WITH A CODE'), h('div.row', { style: { flexWrap: 'nowrap' } }, h('div', { style: { flex: '1' } }, this.code), join),
      h('div.card-buttons', null, smallButton('Back', () => {
        net.disconnect();
        void nav.menu();
      })),
    ));
    this.stop = net.on((msg) => this.message(msg));
  }

  private message(msg: ServerMsg): void {
    if (typeof msg !== 'object') return;
    if ('Rooms' in msg) this.list = msg.Rooms;
    else if ('Joined' in msg) void nav.onlineLobby(msg.Joined.map);
  }

  private host(kind: 'public' | 'private'): void {
    if (net.connected) void nav.mapSelect(kind);
  }

  private join(code: string): void {
    code = code.trim().toUpperCase();
    if (net.connected && code.length === 4) net.send({ Join: { code, car: pref('car') } });
  }

  frame(delta: number): void {
    // the connection: the free server takes up to a minute to wake; much longer than that
    // and it's this device's network that's blocking it (school and work Wi-Fi often do)
    const error = online.error ?? net.error;
    if (error) {
      this.kickerEl.textContent = error.toUpperCase();
      this.kickerEl.classList.add('error');
    } else if (net.connected) {
      this.kickerEl.textContent = 'MULTIPLAYER';
      this.kickerEl.classList.remove('error');
    } else {
      const t = net.secondsTrying;
      this.kickerEl.textContent = t > BLOCKED_AFTER ? "CAN'T CONNECT. YOUR WI-FI MAY BLOCK ONLINE GAMES - TRY MOBILE DATA"
        : t > WAKING_AFTER ? 'WAKING UP THE SERVER... (UP TO A MINUTE)' : 'CONNECTING...';
    }
    for (const b of this.actions) b.disabled = !net.connected;
    this.refresh += delta;
    if (net.connected && this.refresh >= REFRESH_TIME) {
      this.refresh = 0;
      net.send('ListRooms');
    }
    this.renderRooms();
  }

  private renderRooms(): void {
    const key = `${net.connected}${JSON.stringify(this.list)}`;
    if (key === this.shownRooms) return;
    this.shownRooms = key;
    if (!net.connected) {
      this.rooms.replaceChildren(row(h('span.grow.muted', { style: { textAlign: 'center' } }, '...')));
      return;
    }
    if (!this.list.length) {
      this.rooms.replaceChildren(row(h('span.grow.muted', { style: { textAlign: 'center' } }, 'No open races. Host one below!')));
      return;
    }
    this.rooms.replaceChildren(...this.list.slice(0, MAX_ROWS).map((r) => row(
      h('span.gold', { style: { width: '70px' } }, r.code),
      h('span.grow', null, mapById(r.map).name.toUpperCase()),
      h('span', { style: { marginRight: '12px' } }, `${r.players}/${MAX_PLAYERS}`),
      smallButton('Join', () => this.join(r.code)),
    )));
  }

  dispose(): void {
    this.stop();
  }
}
