// Loading pictures as three.js textures and as pixels.
import * as THREE from 'three';
import { asset } from './gen/assets';

export { asset };

const textures = new Map<string, Promise<THREE.Texture>>();

/** a picture under assets/ (named by its .png path) as a texture, loaded once */
export function texture(name: string, pixelArt = true): Promise<THREE.Texture> {
  const key = `${name}|${pixelArt}`;
  let t = textures.get(key);
  if (!t) {
    t = new THREE.TextureLoader().loadAsync(asset(webp(name))).then((tex) => {
      tex.colorSpace = THREE.SRGBColorSpace;
      if (pixelArt) {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
      }
      return tex;
    });
    textures.set(key, t);
  }
  return t;
}

/** the exact pixels of a picture (no colour management), for data like heights */
export async function pixels(name: string): Promise<ImageData> {
  const blob = await (await fetch(asset(webp(name)))).blob();
  const bitmap = await createImageBitmap(blob, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(bitmap, 0, 0);
  bitmap.close();
  return ctx.getImageData(0, 0, canvas.width, canvas.height, { colorSpace: 'srgb' });
}

function webp(name: string): string {
  return name.replace(/\.png$/, '.webp');
}

/** the sprite of a garage car number */
export function carSprite(car: number): string {
  const models = ['sprinter', 'roadster', 'gt', 'muscle', 'hauler'];
  const m = Math.max(0, Math.min(models.length - 1, Math.floor((car - 1) / 3)));
  const paint = Math.max(0, (car - 1) % 3);
  return `ui/cars/${models[m]}_${paint}.png`;
}

/** the sprite of CPU number `index` (0-2) on a map */
export function cpuSprite(cpuSprite: string | undefined, index: number): string {
  return cpuSprite ? `ui/${cpuSprite}.png` : `ui/car${index + 4}.png`;
}
