// Loads the fonts and the gilded UI art (as CSS variables), and scales the menus down on
// screens too small for them (they're laid out for at least 990 x 620).
import { asset } from './gen/assets';
import './styles.css';

const MIN_WIDTH = 990;
const MIN_HEIGHT = 620;

export async function loadTheme(): Promise<void> {
  const root = document.documentElement.style;
  const art: Promise<void>[] = [];
  for (const name of ['panel', 'frame', 'button', 'button_over', 'button_down', 'button_disabled', 'field']) {
    const url = asset(`ui/gilded/${name}.webp`);
    root.setProperty(`--${name.replace('_', '-')}`, `url("${url}")`);
    // Decode every state before interaction, so a first press never swaps to
    // an unloaded border image and exposes the browser's fallback border.
    const image = new Image();
    image.src = url;
    art.push(image.decode().catch(() => {}));
  }
  const fonts = [new FontFace('Speed', `url("${asset('fonts/speed.woff2')}")`), new FontFace('Michroma', `url("${asset('fonts/michroma.woff2')}")`)];
  for (const f of fonts) document.fonts.add(f);
  // show the menus as soon as the fonts are in, but don't wait forever for them
  await Promise.race([Promise.all(fonts.map((f) => f.load())), new Promise((r) => setTimeout(r, 2500))]);
  await Promise.all(art);
  const fit = () => {
    const ui = document.getElementById('ui')!;
    const zoom = Math.min(1, innerWidth / MIN_WIDTH, innerHeight / MIN_HEIGHT);
    ui.style.zoom = String(zoom);
  };
  addEventListener('resize', fit);
  fit();
}
