// Tiny helpers for building the menus and the HUD out of plain DOM.
import { play } from './audio';

type Child = Node | string | null | undefined | false;

/** an element: h('div.panel.wide', {onclick}, child, child...) */
export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K | `${K}.${string}`,
  props: Partial<Record<string, unknown>> | null = null,
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const [name, ...classes] = tag.split('.');
  const el = document.createElement(name as K);
  if (classes.length) el.className = classes.join(' ');
  for (const [k, v] of Object.entries(props ?? {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v as EventListener);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k in el && k !== 'list') (el as unknown as Record<string, unknown>)[k] = v;
    else el.setAttribute(k, String(v));
  }
  for (const c of children) if (c !== null && c !== undefined && c !== false) el.append(c);
  return el;
}

/** a gilded button that clicks when pressed */
export function button(label: string, onClick: () => void, cls = ''): HTMLButtonElement {
  return h(`button.btn${cls ? '.' + cls : ''}` as 'button', {
    onclick: (e: Event) => {
      const b = e.currentTarget as HTMLButtonElement;
      if (!b.disabled) onClick();
    },
    onpointerdown: (e: Event) => play((e.currentTarget as HTMLButtonElement).disabled ? 'back' : 'click', 0.5),
  }, label);
}

/** text in the game's own racing font */
export function title(text: string, cls = ''): HTMLElement {
  return h(`div.title${cls ? '.' + cls : ''}` as 'div', null, text);
}
