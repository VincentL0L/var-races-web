// The title screen: the VAR RACES sign over the title race, and the main menu.
import type { Screen } from '../app';
import { playMusic } from '../audio';
import { button, h } from '../dom';
import { nav } from '../nav';

/** the VAR RACES sign: plain red and white, with a faint shimmer rolling across it like a flag */
export function sign(): HTMLElement {
  return h('div.sign', null, h('span', null, 'VAR RACES'));
}

export class MenuScreen implements Screen {
  readonly el: HTMLElement;

  constructor() {
    playMusic('music_title');
    this.el = h('div.screen.title-screen', null,
      sign(),
      h('div.col.menu-buttons', null,
        button('Single Player', () => void nav.mapSelect()),
        button('Multiplayer', () => void nav.multiplayer()),
        button('Garage', () => void nav.garage()),
        button('Race Car Soccer', () => void nav.soccer()),
      ),
    );
  }
}
