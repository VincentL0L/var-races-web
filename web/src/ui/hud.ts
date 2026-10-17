// The race HUD, laid out like a car's digital driver display in the gilded pixel style.
// Across the top, symmetrical: the standings tower on the left (time gaps under it), the
// instrument cluster in the middle (speedometer pod hanging below it, boost and position on
// its left, lap and stopwatch on its right), and the minimap on the right (pause and the held
// item under it). Over the race: the lap banner, damage flash, RETURN TO TRACK, FINISH!, and in
// battles health bars over the cars and arrows pointing at opponents off screen.
import { asset } from '../assets';
import * as audio from '../audio';
import { h } from '../dom';
import { touchScreen } from '../input';
import type { MapDef } from '../wasm';

const MAX_SPEED_MPH = 240;
/** the top row needs about this much width before it shrinks */
const FULL_WIDTH = 1240;
const BOOST_SEGMENTS = 12;
const ROULETTE_TIME = 0.8;
const BANNER_TIME = 1.8;
const ITEM_NAMES = ['nitro', 'rocket', 'oil', 'bubble', 'pulse', 'frost', 'fire'];
export const ALIVE = 10000;

/** one racer's line in the standings */
export interface Standing {
  slot: number;
  name: string;
  me: boolean;
  cpu: boolean;
  laps: number;
  progress: number;
  finishTime: number | null;
  hp: number;
  out: boolean;
  kills: number;
}

export interface HudState {
  standings: Standing[];
  /** laps in the race */
  laps: number;
  courseLength: number;
  raceTime: number;
  speed: number;
  /** boost tank 0..1 */
  boost: number;
  /** the held item (by name) and blaster shots left */
  item: string | null;
  ammo: number;
  offTrack: boolean;
  paused: boolean;
  /** every car's position, for the minimap */
  dots: { x: number; y: number; me: boolean; cpu: boolean }[];
}

export function formatTime(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds - m * 60;
  return `${m}:${s.toFixed(2).padStart(5, '0')}`;
}

function setText(el: HTMLElement, text: string): void {
  if (el.textContent !== text) el.textContent = text;
}

/** a recessed display: small gold label on the left, value on the right */
function screenBox(label: string, cls = ''): { el: HTMLElement; label: HTMLElement; value: HTMLElement } {
  const l = h('span.hud-label', null, label);
  const v = h('span.hud-value', null, '');
  return { el: h(`div.hud-screen${cls ? '.' + cls : ''}` as 'div', null, l, v), label: l, value: v };
}

export class Hud {
  readonly el: HTMLElement;
  private readonly standings = h('div.hud-rows');
  private readonly ahead = screenBox('AHEAD');
  private readonly behind = screenBox('BEHIND');
  private readonly pos = screenBox('POS');
  private readonly lap = screenBox('LAP');
  private readonly time = screenBox('TIME');
  private readonly boostSegs: HTMLElement[] = [];
  private readonly gauge = h('canvas.hud-gauge', { width: 192, height: 192 });
  private readonly gaugeImg = new Image();
  private readonly map = h('canvas.hud-map', { width: 300, height: 170 });
  private readonly pauseBox = screenBox(touchScreen ? 'PAUSE' : 'PAUSE  ESC', 'button');
  private readonly itemBox = screenBox(touchScreen ? 'ITEM' : 'ITEM  E', 'button');
  private readonly itemIcon = h('img.hud-item.pixel', { alt: '' });
  private readonly banner = h('div.hud-banner.panel');
  private readonly warning = h('div.hud-warning', null, 'RETURN TO TRACK');
  private readonly flash = h('div.hud-flash');
  private readonly label = h('div.hud-center');
  readonly marks = h('canvas.hud-marks');
  private rows: { el: HTMLElement; tab: HTMLElement; name: HTMLElement; status: HTMLElement }[] = [];
  private shownSpeed = 0;
  private clock = 0;
  private bannerTimer = 0;
  private shownLap = 0;
  private damage = 0;
  private roulette = 0;
  private heldItem: string | null = null;
  private history = new Map<number, number[]>();
  private lastSample = -1;

  constructor(private readonly mapDef: MapDef, onPause: () => void, onItem: () => void) {
    this.gaugeImg.src = asset('ui/speedometer.webp');
    for (let i = 0; i < BOOST_SEGMENTS; i++) this.boostSegs.push(h('i'));
    const boost = h('div.hud-screen', null, h('span.hud-label', null, 'BOOST'), h('div.hud-boost', null, ...this.boostSegs));
    const battle = mapDef.kind === 'battle';
    if (battle) {
      setText(this.ahead.label, 'ALIVE');
      setText(this.behind.label, 'KOS');
      setText(this.lap.label, 'HP');
    } else if (mapDef.kind === 'sprint') {
      setText(this.lap.label, 'DIST');
    }
    this.pauseBox.el.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      onPause();
    });
    this.itemBox.el.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      onItem();
    });
    this.itemBox.el.append(this.itemIcon);
    this.el = h('div.hud', null,
      this.marks,
      this.flash,
      h('div.hud-top', null,
        h('div.hud-side', null,
          h('div.panel.hud-standings', null, h('div.hud-title', null, 'STANDINGS'), this.standings),
          h('div.panel.hud-strip', null, this.ahead.el, this.behind.el),
        ),
        h('div.hud-cluster', null,
          h('div.panel.hud-housing', null,
            h('div.hud-modules', null, boost, this.pos.el),
            h('div.hud-pod'),
            h('div.hud-modules', null, this.lap.el, this.time.el),
          ),
          this.gauge,
        ),
        h('div.hud-side', null,
          h('div.panel.hud-minimap', null, h('div.hud-title.right', null, 'TRACK'), this.map),
          h('div.panel.hud-strip', null, this.pauseBox.el, this.itemBox.el),
        ),
      ),
      this.banner,
      this.warning,
      this.label,
    );
    this.drawTrack();
    this.fit();
    addEventListener('resize', this.fit);
  }

  /** the top row shrinks evenly on narrow windows */
  private fit = (): void => {
    const uiZoom = Number(document.getElementById('ui')?.style.zoom || 1);
    const scale = Math.min(1, innerWidth / FULL_WIDTH);
    this.el.style.setProperty('--hud-zoom', String(scale / uiZoom));
  };

  dispose(): void {
    removeEventListener('resize', this.fit);
  }

  /** we just got hit: the screen edges flash red */
  flashDamage(): void {
    this.damage = 1;
  }

  /** the big word in the middle: FINISH!, WINNER!, KNOCKED OUT... (empty hides it) */
  showLabel(text: string, color = '#ffe14a'): void {
    setText(this.label, text);
    this.label.style.color = color;
    this.label.style.display = text ? '' : 'none';
  }

  update(s: HudState, delta: number): void {
    this.clock += delta;
    const battle = this.mapDef.kind === 'battle';
    const sprint = this.mapDef.kind === 'sprint';
    this.record(s);
    // standings
    while (this.rows.length < s.standings.length) {
      const tab = h('span.hud-tab'), name = h('span.hud-name'), status = h('span.hud-status');
      const el = h('div.hud-row', null, tab, name, status);
      this.rows.push({ el, tab, name, status });
      this.standings.append(el);
    }
    while (this.rows.length > s.standings.length) this.rows.pop()!.el.remove();
    let myPlace = 0;
    s.standings.forEach((r, i) => {
      const row = this.rows[i];
      if (r.me) myPlace = i + 1;
      row.el.classList.toggle('me', r.me);
      row.tab.classList.toggle('lead', i === 0);
      setText(row.tab, String(i + 1));
      setText(row.name, r.me ? `${r.name} (YOU)` : r.name);
      const status =
        r.finishTime !== null && !battle ? formatTime(r.finishTime)
        : battle ? (r.out ? 'OUT' : `HP ${Math.round(r.hp)}`)
        : sprint ? `${this.percent(r, s)}%`
        : `LAP ${Math.max(1, Math.min(s.laps, r.laps + 1))}/${s.laps}`;
      setText(row.status, status);
      row.status.classList.toggle('done', r.finishTime !== null);
    });
    const me = s.standings.find((r) => r.me);
    // gaps (or, in a battle, who's still in and our knockouts)
    if (battle) {
      setText(this.ahead.value, `${s.standings.filter((r) => !r.out).length}/${s.standings.length}`);
      setText(this.behind.value, String(me?.kills ?? 0));
      this.behind.value.classList.add('gold');
    } else {
      const [ahead, behind] = this.gaps(s, myPlace - 1);
      setText(this.ahead.value, ahead);
      setText(this.behind.value, behind);
    }
    // cluster
    const lit = Math.round(Math.max(0, Math.min(1, s.boost)) * BOOST_SEGMENTS);
    this.boostSegs.forEach((seg, i) => seg.classList.toggle('on', i < lit));
    setText(this.pos.value, myPlace ? `P${myPlace}/${s.standings.length}` : '--');
    const finished = me?.finishTime != null && !battle;
    if (battle) {
      const hp = me ? Math.max(0, me.hp) : 100;
      setText(this.lap.value, me?.out ? 'OUT' : String(Math.round(hp)));
      this.lap.value.style.color = hp > 50 ? '' : hp > 25 ? 'var(--gold)' : '#ff5c38';
    } else if (sprint) {
      setText(this.lap.value, finished ? 'DONE' : me ? `${this.percent(me, s)}%` : '0%');
    } else {
      setText(this.lap.value, finished ? 'DONE' : `${me ? Math.max(1, Math.min(s.laps, me.laps + 1)) : 1}/${s.laps}`);
    }
    const shownTime = battle ? Math.max(0, 150 - s.raceTime) : finished ? me!.finishTime! : s.raceTime;
    setText(this.time.value, formatTime(shownTime));
    this.time.value.classList.toggle('gold', finished);
    this.drawGauge(s.speed * 0.4, delta);
    this.drawMap(s);
    this.updateItem(s, delta);
    setText(this.pauseBox.label, s.paused ? 'RESUME' : touchScreen ? 'PAUSE' : 'PAUSE  ESC');
    this.updateBanner(s, me, delta);
    // damage flash and the off-track warning
    this.damage = Math.max(0, this.damage - delta * 2.5);
    this.flash.style.opacity = String(this.damage * 0.45);
    this.warning.style.visibility = s.offTrack && !s.paused && Math.floor(this.clock * 3) % 2 === 0 ? 'visible' : 'hidden';
  }

  private percent(r: Standing, s: HudState): number {
    return Math.round(Math.max(0, Math.min(1, r.progress / Math.max(1, s.courseLength))) * 100);
  }

  /** notes everyone's progress ten times a second, to work out time gaps */
  private record(s: HudState): void {
    if (s.raceTime <= 0 || s.raceTime - this.lastSample < 0.1) return;
    this.lastSample = s.raceTime;
    for (const r of s.standings) {
      let hist = this.history.get(r.slot);
      if (!hist) this.history.set(r.slot, (hist = []));
      hist.push(s.raceTime, r.progress);
    }
  }

  /** when this racer had driven this far (seconds), or -1 if we don't know */
  private timeAt(slot: number, progress: number): number {
    const hist = this.history.get(slot);
    if (!hist || hist.length < 4) return -1;
    for (let i = 2; i < hist.length; i += 2) {
      if (hist[i + 1] >= progress) {
        const [t0, p0, t1, p1] = [hist[i - 2], hist[i - 1], hist[i], hist[i + 1]];
        return p1 > p0 ? t0 + ((t1 - t0) * (progress - p0)) / (p1 - p0) : t1;
      }
    }
    return -1;
  }

  /** how far, in seconds, the car ahead is, and the car behind */
  private gaps(s: HudState, me: number): [string, string] {
    if (me < 0 || s.raceTime <= 0 || s.standings[me].finishTime !== null) return ['--', '--'];
    const mine = s.standings[me];
    let ahead = '--', behind = '--';
    if (me === 0) ahead = 'LEAD';
    else {
      const t = this.timeAt(s.standings[me - 1].slot, mine.progress);
      if (t >= 0) ahead = `+${(s.raceTime - t).toFixed(1)}`;
    }
    if (me < s.standings.length - 1) {
      const t = this.timeAt(mine.slot, s.standings[me + 1].progress);
      if (t >= 0) behind = `-${(s.raceTime - t).toFixed(1)}`;
    }
    return [ahead, behind];
  }

  /** the round gauge: pixel-art dial, moving needle and digital readout */
  private drawGauge(mph: number, delta: number): void {
    // ease the needle toward the real speed so it sweeps like a physical gauge
    this.shownSpeed += (mph - this.shownSpeed) * Math.min(1, delta * 10);
    const g = this.gauge.getContext('2d')!;
    g.imageSmoothingEnabled = false;
    g.clearRect(0, 0, 192, 192);
    if (this.gaugeImg.complete) g.drawImage(this.gaugeImg, 0, 0, 192, 192);
    const cx = 96, cy = 96;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillStyle = '#ffcc47';
    g.font = '19px Speed';
    g.fillText(String(Math.round(mph)), cx, 44 * 3 + 1);
    g.fillStyle = '#d9a64d';
    g.font = '9px Michroma';
    g.fillText('MPH', cx, 51.5 * 3);
    // the needle sweeps clockwise from 210 degrees (0 mph) to -30 degrees (max)
    const ratio = Math.max(0, Math.min(1, this.shownSpeed / MAX_SPEED_MPH));
    const a = ((210 - ratio * 240) * Math.PI) / 180;
    const dx = Math.cos(a), dy = -Math.sin(a);
    const needle = (len: number, half: number, color: string) => {
      g.fillStyle = color;
      g.beginPath();
      g.moveTo(cx + dy * half, cy - dx * half);
      g.lineTo(cx - dy * half, cy + dx * half);
      g.lineTo(cx + dx * len, cy + dy * len);
      g.fill();
    };
    needle(63, 6.5, '#21120a');
    needle(60, 4, '#f05229');
    for (const [r, c] of [[10, '#21120a'], [7.5, '#ffcc47'], [3, '#21120a']] as const) {
      g.fillStyle = c;
      g.beginPath();
      g.arc(cx, cy, r, 0, Math.PI * 2);
      g.fill();
    }
  }

  private trackImage: HTMLCanvasElement | null = null;
  private mapScale = 1;
  private mapOrigin = { x: 0, y: 0 };

  /** the whole course in miniature, drawn once */
  private drawTrack(): void {
    const m = this.mapDef;
    const c = document.createElement('canvas');
    c.width = this.map.width;
    c.height = this.map.height;
    const g = c.getContext('2d')!;
    const scale = Math.min((c.width - 12) / m.width, (c.height - 8) / m.height);
    const ox = (c.width - m.width * scale) / 2, oy = (c.height + m.height * scale) / 2;
    const px = (x: number) => ox + x * scale, py = (y: number) => oy - y * scale;
    this.mapScale = scale;
    this.mapOrigin = { x: ox, y: oy };
    if (m.kind === 'battle') {
      // an arena: just its floor
      g.fillStyle = '#1a1412';
      g.fillRect(px(130), py(1310), 1660 * scale, 1180 * scale);
      g.fillStyle = '#6b6b75';
      g.fillRect(px(140), py(1300), 1640 * scale, 1160 * scale);
    } else {
      const pts = m.waypoints;
      // a sprint's line stops at the finish (the last waypoint is only the run-off)
      const stretches = m.kind === 'sprint' ? pts.length - 2 : pts.length;
      for (const [color, width] of [['#1a1412', 7], ['#6b6b75', 4]] as const) {
        g.strokeStyle = color;
        g.lineWidth = width;
        g.lineCap = g.lineJoin = 'round';
        g.beginPath();
        g.moveTo(px(pts[0][0]), py(pts[0][1]));
        for (let i = 1; i <= stretches; i++) {
          const p = pts[i % pts.length];
          g.lineTo(px(p[0]), py(p[1]));
        }
        g.stroke();
      }
      // the start line, and on a sprint a checkered flag at the finish
      g.fillStyle = '#fff';
      g.fillRect(px(136), py(300) - 1, 128 * scale, 2.5);
      if (m.kind === 'sprint') {
        const f = pts[pts.length - 2];
        for (let cy = 0; cy < 3; cy++) {
          for (let cx = 0; cx < 3; cx++) {
            g.fillStyle = (cx + cy) % 2 === 0 ? '#fff' : '#1a1412';
            g.fillRect(px(f[0]) - 4.5 + cx * 3, py(f[1]) - 4.5 + cy * 3, 3, 3);
          }
        }
      }
    }
    this.trackImage = c;
  }

  private drawMap(s: HudState): void {
    const g = this.map.getContext('2d')!;
    g.clearRect(0, 0, this.map.width, this.map.height);
    if (this.trackImage) g.drawImage(this.trackImage, 0, 0);
    const dot = (x: number, y: number, r: number, color: string) => {
      g.fillStyle = '#1a1412';
      g.beginPath();
      g.arc(x, y, r + 1.5, 0, Math.PI * 2);
      g.fill();
      g.fillStyle = color;
      g.beginPath();
      g.arc(x, y, r, 0, Math.PI * 2);
      g.fill();
    };
    // everyone else first, you on top in gold
    const at = (d: { x: number; y: number }) => [this.mapOrigin.x + d.x * this.mapScale, this.mapOrigin.y - d.y * this.mapScale] as const;
    for (const d of s.dots) if (!d.me) dot(...at(d), 3.5, d.cpu ? '#fff2d6' : '#66bfff');
    for (const d of s.dots) if (d.me) dot(...at(d), 5, '#ffcc47');
  }

  /** the held item: spinning through every item for a moment after a pickup, then the one you got */
  private updateItem(s: HudState, delta: number): void {
    if (s.item && !this.heldItem) {
      this.roulette = ROULETTE_TIME;
      audio.play('item_pickup', 0.5);
    }
    this.heldItem = s.item;
    if (this.roulette > 0) {
      this.roulette -= delta;
      if (this.roulette <= 0 && s.item) audio.play('item_land', 0.45);
    }
    const shown = s.item && this.roulette > 0 ? ITEM_NAMES[Math.floor(this.clock * 14) % ITEM_NAMES.length] : s.item;
    const src = shown ? asset(`ui/items/${shown}.webp`) : '';
    if (this.itemIcon.getAttribute('src') !== src) {
      if (src) this.itemIcon.src = src;
      else this.itemIcon.removeAttribute('src');
    }
    this.itemIcon.style.visibility = shown ? 'visible' : 'hidden';
    setText(this.itemBox.value, s.item ? (s.ammo > 0 && this.roulette <= 0 ? `x${s.ammo}` : '') : '-');
  }

  /** LAP 2/3 or FINAL LAP slides across the middle as you cross the line */
  private updateBanner(s: HudState, me: Standing | undefined, delta: number): void {
    if (me && this.mapDef.kind === 'circuit' && me.finishTime === null && me.laps > this.shownLap) {
      this.shownLap = me.laps;
      const lap = me.laps + 1;
      const final = lap === s.laps;
      setText(this.banner, final ? 'FINAL LAP' : `LAP ${lap}/${s.laps}`);
      this.banner.classList.toggle('final', final);
      audio.play(final ? 'final_lap' : 'lap', 0.5);
      this.bannerTimer = BANNER_TIME;
    }
    if (this.bannerTimer <= 0) {
      this.banner.style.display = 'none';
      return;
    }
    this.bannerTimer -= delta;
    const t = 1 - this.bannerTimer / BANNER_TIME;
    // slide in, hold, slide out
    let slide = t < 0.18 ? 1 - t / 0.18 : t > 0.82 ? -(t - 0.82) / 0.18 : 0;
    slide = slide * Math.abs(slide);
    this.banner.style.display = '';
    this.banner.style.transform = `translateX(calc(-50% + ${slide * 60}vw))`;
  }
}
