// The results: everyone's place and time over a slowly drifting checkered flag. After a ranked
// race the rating change arrives a moment later; after an online race the host can start
// another in the same room.
import { app, type Screen } from '../app';
import * as audio from '../audio';
import { button, h } from '../dom';
import type { Result } from '../game/race';
import { leaveRoom, online } from '../game/rooms';
import type { RaceSource } from '../game/session';
import { net } from '../net';
import { nav } from '../nav';
import type { ServerMsg } from '../wasm';
import { formatTime } from './hud';

export class EndScreen implements Screen {
  readonly el: HTMLElement;
  private readonly rank = h('div.title.small.plain', { style: { margin: '0 0 20px' } });
  private readonly again: HTMLButtonElement | null = null;
  private readonly stop: (() => void) | null = null;
  private rankShown = false;
  /** back to the same room's lobby for another race */
  private staying = false;
  private offset = 0;
  private readonly flag = h('div.checkers');

  constructor(results: Result[], private readonly source: RaceSource) {
    audio.playMusic('music_results', false);
    app.setStage(null);
    const place = results.findIndex((r) => r.me) + 1;
    const mine = results[place - 1];
    let line = `Position: ${place} of ${results.length}`;
    if (mine?.finishTime != null) line += `     Time: ${formatTime(mine.finishTime)}`;
    const ranked = source.online && online.room?.visibility === 'Ranked';
    const table = h('table.results', null,
      h('tr', null, h('th', null, 'Pos'), h('th', null, 'Player'), h('th', null, 'Time')),
      ...results.map((r, i) =>
        h(`tr${r.me ? '.me' : ''}${r.finishTime === null ? '.racing' : ''}` as 'tr', null,
          h('td', null, String(i + 1)), h('td', null, r.name), h('td', null, r.finishTime === null ? 'racing' : formatTime(r.finishTime)))),
    );
    if (ranked) this.rank.textContent = 'Working out your new rank...';
    const buttons: HTMLElement[] = [button('Main Menu', () => this.leave())];
    if (source.online && !ranked) {
      this.again = button('Race Again', () => net.send('Rematch'));
      buttons.unshift(this.again);
    }
    this.el = h('div.screen', null,
      this.flag,
      h('div.panel', { style: { padding: '12px 32px 16px', textAlign: 'center', position: 'relative' } },
        h('div.title.medium.plain', { style: { margin: '8px 0 24px' } }, 'Race Complete!'),
        h('div.label', { style: { marginBottom: ranked ? '8px' : '24px' } }, line),
        ranked && this.rank,
        h('div.scroll.results-box', null, table),
        h('div.row', { style: { marginTop: '24px' } }, ...buttons),
      ),
    );
    if (source.online) {
      this.stop = net.on((msg) => this.message(msg));
    }
  }

  private message(msg: ServerMsg): void {
    if (msg === 'Rematch') {
      this.staying = true;
      void nav.onlineLobby(online.room!.mapId);
    }
  }

  frame(delta: number): void {
    // the checkered flag drifts on a diagonal, like it's waving
    this.offset = (this.offset + delta * 14) % 112;
    this.flag.style.backgroundPosition = `${this.offset}px ${-this.offset}px`;
    if (this.again) {
      const host = online.lobby?.host === online.room?.slot;
      const over = this.source.latest().phase === 'Over';
      this.again.disabled = !host || !over;
      this.again.title = !host ? 'The host starts the next race' : over ? '' : 'Waiting for everyone to finish';
    }
    const r = online.rankResult;
    if (r && !this.rankShown) {
      this.rankShown = true;
      if (!r.rated) this.rank.textContent = "No one else raced, so this one didn't count";
      else {
        const change = r.new - r.old;
        this.rank.textContent = `RATING ${r.old}  >  ${r.new}   (${change >= 0 ? '+' : ''}${change})   ${r.tier}`;
        this.rank.style.color = change >= 0 ? '#73e673' : '#ff8066';
        audio.play(change >= 0 ? 'final_lap' : 'back', 0.5);
      }
    }
  }

  private leave(): void {
    if (this.source.online) leaveRoom();
    void nav.menu();
  }

  dispose(): void {
    this.stop?.();
    if (this.staying) this.source.detach?.();
    else this.source.dispose();
  }
}
