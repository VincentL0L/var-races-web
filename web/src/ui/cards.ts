// Building blocks for the menus, in the same design language as the HUD: a gilded card, small
// gold section labels, recessed rows and status pills.
import { asset } from '../assets';
import { button, h } from '../dom';

export function card(...children: (Node | string | null | false)[]): HTMLElement {
  return h('div.card-panel', null, ...children);
}

export function kicker(text: string): HTMLElement {
  return h('div.kicker', null, text);
}

export function cardTitle(text: string, size = 44): HTMLElement {
  return h('div.card-title', { style: { fontSize: `${size}px` } }, text);
}

/** a recessed row, like the screens in the HUD */
export function row(...children: (Node | string | null | false)[]): HTMLElement {
  return h('div.crow', null, ...children);
}

export function pill(text: string, kind: '' | 'ready' | 'cpu' = ''): HTMLElement {
  return h(`span.pill${kind ? '.' + kind : ''}` as 'span', null, text);
}

/** a car's pixel-art sprite, standing up, sized for a row */
export function carIcon(sprite: string): HTMLImageElement {
  return h('img.car', { src: asset(sprite.replace('.png', '.webp')), alt: '' });
}

export function smallButton(label: string, onClick: () => void): HTMLButtonElement {
  return button(label, onClick, 'small', label === 'Back' || label === 'Leave' ? 'secondary' : 'tertiary');
}

/** one setting: a recessed row with its name, then < value > */
export function picker(name: string, onStep: (direction: number) => void): { el: HTMLElement; value: HTMLElement; arrows: HTMLButtonElement[] } {
  const value = h('span.value');
  const less = smallButton('<', () => onStep(-1)), more = smallButton('>', () => onStep(1));
  less.setAttribute('aria-label', `Decrease ${name.toLowerCase()}`);
  more.setAttribute('aria-label', `Increase ${name.toLowerCase()}`);
  const el = row(h('span.grow', null, name), h('div.picker', null, less, value, more));
  el.classList.add('setting-row');
  return { el, value, arrows: [less, more] };
}

/** a full-screen frosted holder for a card, centred */
export function centered(...children: Node[]): HTMLElement {
  return h('div.screen.frosted', null, ...children);
}
