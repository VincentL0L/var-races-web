// Browser regression checks against Vite: node tools/camera-check.mjs [url] [screenshots]
// Frames and controls are scripted so view changes can be compared byte for byte.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const url = process.argv[2] ?? 'http://localhost:8000/';
const shots = process.argv[3];
if (shots) await mkdir(shots, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', args: ['--use-angle=metal', '--ignore-gpu-blocklist'] });
const errors = [];
const fixtureUrl = `/@fs${fileURLToPath(new URL('./camera-fixture.mjs', import.meta.url))}`;
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('request', (r) => {
    if (/identitytoolkit|securetoken|firestore|accounts\.google/.test(r.url())) errors.push(`unexpected account request: ${r.url()}`);
  });
  await page.addInitScript(() => { window.requestAnimationFrame = () => 0; });
  await page.goto(url);
  await page.getByText('Single Player', { exact: true }).waitFor();
  const results = await page.evaluate(async (path) => (await import(path)).runChecks(), fixtureUrl);
  for (const result of results) console.log(`- ${result}`);

  for (const [stored, expected] of [[{ firstPerson: true }, 'firstPerson'], [{ firstPerson: false }, 'thirdPerson'], [{ cameraMode: 'bad' }, 'thirdPerson'], [{ cameraMode: 'firstPerson' }, 'firstPerson']]) {
    await page.evaluate((value) => localStorage.setItem('var-races', JSON.stringify(value)), stored);
    await page.reload();
    await page.getByText('Single Player', { exact: true }).waitFor();
    const actual = await page.evaluate(async (path) => (await import(path)).currentPreference(), fixtureUrl);
    assert.equal(actual, expected);
  }
  console.log('- legacy and current camera preference migration');
  if (shots) {
    await page.evaluate(async (path) => (await import(path)).prepareShot(), fixtureUrl);
    await page.screenshot({ path: `${shots}/chase-tokyo.png` });
    await page.keyboard.press('KeyV');
    await page.evaluate(async (path) => (await import(path)).renderCurrent(), fixtureUrl);
    await page.screenshot({ path: `${shots}/first-tokyo.png` });
  }
  const touch = await browser.newContext({ viewport: { width: 844, height: 390 }, hasTouch: true });
  const mobile = await touch.newPage();
  mobile.on('pageerror', (e) => errors.push(e.message));
  await mobile.goto(url);
  await mobile.getByText('Single Player', { exact: true }).click();
  await mobile.getByRole('button', { name: 'Next', exact: true }).click();
  await mobile.getByRole('button', { name: 'Ready', exact: true }).click();
  await mobile.locator('.race[data-camera-mode="thirdPerson"]').waitFor();
  await mobile.getByRole('button', { name: 'Switch view', exact: true }).tap();
  await mobile.locator('.race[data-camera-mode="firstPerson"]').waitFor();
  await mobile.getByRole('button', { name: 'Switch view', exact: true }).tap();
  await mobile.locator('.race[data-camera-mode="thirdPerson"]').waitFor();
  if (shots) await mobile.screenshot({ path: `${shots}/chase-touch.png` });
  await touch.close();
  console.log('- touch-only camera switching on a landscape phone');
  assert.deepEqual(errors, []);
  console.log('camera checks passed');
} finally {
  await browser.close();
}
