// The ranked Top 20, and your own record.
import type { Screen } from '../app';
import { signOut } from '../account';
import { h } from '../dom';
import { online } from '../game/rooms';
import { net } from '../net';
import { nav } from '../nav';
import { card, cardTitle, centered, kicker, pill, row, smallButton } from './cards';
import { TIER_COLORS } from './multiplayer';

export class LeaderboardScreen implements Screen {
  readonly el: HTMLElement;
  private readonly you = h('div.gold', { style: { marginBottom: '10px' } });
  private readonly list = h('div.scroll', { style: { height: '330px' } });
  private shown = false;

  constructor() {
    online.top = null;
    net.send('Leaderboard');
    this.el = centered(card(
      kicker('RANKED  /  SEASON 1'),
      cardTitle('TOP 20'),
      this.you,
      this.list,
      h('div.card-buttons', null,
        smallButton('Back', () => void nav.multiplayer()),
        h('div.spacer'),
        smallButton('Sign out', () => {
          signOut();
          void nav.multiplayer();
        }),
      ),
    ));
  }

  frame(): void {
    const p = online.profile;
    this.you.textContent = p ? `${p.name}   ${p.tier}  ${p.rating}   /   ${p.races} races, ${p.wins} wins` : 'Sign in to get a rank';
    if (this.shown || !online.top) return;
    this.shown = true;
    const top = online.top;
    if (!top.length) {
      this.list.replaceChildren(row(h('span.grow.muted', { style: { textAlign: 'center' } },
        p?.ranked_on ? 'No ranked races yet. Be the first!' : 'Ranked is offline right now')));
      return;
    }
    this.list.replaceChildren(...top.map((t, i) => {
      const tier = pill(t.tier);
      tier.style.background = TIER_COLORS[t.tier] ?? '#cc8c52';
      tier.style.color = 'var(--dark)';
      return row(
        h(i < 3 ? 'span.gold' : 'span', { style: { width: '44px' } }, `#${i + 1}`),
        h(t.name === p?.name ? 'span.grow.gold' : 'span.grow', null, t.name),
        tier,
        h('span.gold', { style: { width: '56px', textAlign: 'right' } }, String(t.rating)),
      );
    }));
  }
}
