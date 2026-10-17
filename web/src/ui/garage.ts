// The garage: five cars in three paints, each trading something off (a quick launch or a high
// top speed, sharp handling or a heavy body), over the title race under frosted glass.
import { CarPreview } from '../render/carPreview';
import type { Screen } from '../app';
import { asset, carSprite } from '../assets';
import * as audio from '../audio';
import { button, h } from '../dom';
import { nav } from '../nav';
import { pref, setPref } from '../prefs';
import { cars } from '../wasm';
import { card, cardTitle, centered, kicker, smallButton } from './cards';

interface CarInfo {
  id: string;
  name: string;
  tagline: string;
  speed: number;
  accel: number;
  handling: number;
  weight: number;
}

const PAINTS = 3;
const STATS = [
  ['SPEED', 'speed', '#ffcc47'],
  ['ACCEL', 'accel', '#66d973'],
  ['HANDLING', 'handling', '#59b3ff'],
  ['WEIGHT', 'weight', '#f2734d'],
] as const;

export class GarageScreen implements Screen {
  readonly el: HTMLElement;
  private readonly models = cars() as CarInfo[];
  private model: number;
  private paint: number;
  private readonly name = cardTitle('');
  private readonly tagline = h('div.label', { style: { width: '250px', marginBottom: '14px', fontSize: '11px' } });
  private readonly preview = new CarPreview();
  private readonly bars: HTMLElement[][] = [];
  private readonly swatches: HTMLElement[] = [];

  constructor() {
    audio.playMusic('music_title');
    const car = pref('car');
    this.model = Math.max(0, Math.min(this.models.length - 1, Math.floor((car - 1) / PAINTS)));
    this.paint = Math.max(0, (car - 1) % PAINTS);
    const n = this.models.length;
    const stats = STATS.map(([label]) => {
      const segs = Array.from({ length: 10 }, () => h('i', { style: { width: '14px', height: '12px', background: '#291f17' } }));
      this.bars.push(segs);
      return h('div.row', { style: { justifyContent: 'flex-start', gap: '3px', marginBottom: '8px' } },
        h('span.hud-label', { style: { width: '78px' } }, label), ...segs);
    });
    const paints = Array.from({ length: PAINTS }, (_, p) => {
      const sw = h('button.crow.clickable.paint-swatch', { 'aria-label': `Paint ${p + 1}`, onclick: () => { audio.play('click', 0.4); this.show(this.model, p); } },
        h('img.pixel', { style: { width: '28px', height: '56px', margin: '0 6px' } }));
      this.swatches.push(sw);
      return sw;
    });
    this.el = centered(card(
      kicker('GARAGE'),
      this.name,
      h('div.row', { style: { alignItems: 'flex-start', flexWrap: 'nowrap', gap: '26px' } },
        h('div.row', { style: { gap: '8px', flexWrap: 'nowrap' } },
          smallButton('<', () => this.show((this.model + n - 1) % n, this.paint)),
          h('div.crow', { style: { padding: '0' } }, this.preview.canvas),
          smallButton('>', () => this.show((this.model + 1) % n, this.paint)),
        ),
        h('div', null, this.tagline, ...stats, kicker('PAINT'), h('div.row', { style: { justifyContent: 'flex-start' } }, ...paints)),
      ),
      h('div.card-buttons', null,
        smallButton('Back', () => void nav.menu()),
        h('div.spacer'),
        button('Select', () => {
          setPref('car', this.model * PAINTS + this.paint + 1);
          void nav.menu();
        }),
      ),
    ));
    this.el.querySelector<HTMLElement>('.card-panel')!.style.width = '720px';
    this.show(this.model, this.paint);
  }

  frame(delta: number): void { this.preview.frame(delta); }
  dispose(): void { this.preview.dispose(); }

  private show(model: number, paint: number): void {
    this.model = model;
    this.paint = paint;
    const car = this.models[model];
    this.name.textContent = car.name.toUpperCase();
    this.tagline.textContent = car.tagline;
    const src = (p: number) => asset(carSprite(model * PAINTS + p + 1).replace('.png', '.webp'));
    this.preview.show(carSprite(model * PAINTS + paint + 1));
    STATS.forEach(([, key, color], s) => {
      this.bars[s].forEach((seg, k) => (seg.style.background = k < car[key] ? color : '#291f17'));
    });
    this.swatches.forEach((sw, p) => {
      (sw.firstElementChild as HTMLImageElement).src = src(p);
      sw.setAttribute('aria-pressed', String(p === paint));
      sw.style.borderImageSource = p === paint ? 'var(--frame)' : '';
    });
  }
}
