// End-to-end check in real Chrome against a running dev server and race server:
// two guests configure CPUs, meet online, and race.
//   node tools/e2e.mjs [http://localhost:8000] [screenshot folder]
// Needs Google Chrome and `npm run dev` (or vite + the server) running.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const url = process.argv[2] ?? 'http://localhost:8000/';
const shots = process.argv[3];
const browser = await chromium.launch({ channel: 'chrome', args: ['--use-angle=metal', '--ignore-gpu-blocklist'] });
const errors = [];
if (shots) await mkdir(shots, { recursive: true });
const open = async (name) => {
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 720 } })).newPage();
  page.on('request', (r) => {
    if (/identitytoolkit|securetoken|firestore|accounts\.google/.test(r.url())) errors.push(`unexpected account request: ${r.url()}`);
  });
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  page.on('console', (m) => m.type() === 'error' && errors.push(`${name}: ${m.text()}`));
  await page.goto(url);
  await page.getByText('Multiplayer', { exact: true }).waitFor();
  return page;
};
const click = (page, text) => page.getByText(text, { exact: true }).first().click();
const shot = (page, name) => shots && page.screenshot({ path: `${shots}/${name}.png` });
const step = (msg) => console.log(`- ${msg}`);
const mode = (page) => page.locator('[data-camera-mode]').getAttribute('data-camera-mode');
const switchView = async (page, expected, button = false) => {
  if (button) await page.getByRole('button', { name: 'Switch view', exact: true }).click();
  else await page.keyboard.press('KeyV');
  assert.equal(await mode(page), expected);
};

try {
  const a = await open('A'), b = await open('B');
  step('A hosts a public guest race');
  await click(a, 'Multiplayer');
  await a.getByText('MULTIPLAYER', { exact: true }).waitFor({ timeout: 20000 });
  assert.equal(await a.getByText(/Sign in|RANKED|Top 20/).count(), 0);
  await click(a, 'Public');
  await click(a, 'Create');
  await a.getByText('PUBLIC ROOM', { exact: false }).waitFor({ timeout: 10000 });
  const addCpu = a.getByRole('button', { name: 'Add CPU', exact: true });
  const addBounds = await addCpu.boundingBox();
  for (let count = 1; count <= 3; count++) {
    await addCpu.click();
    await a.waitForFunction(n => document.querySelectorAll('.remove-cpu').length === n, count);
    assert.deepEqual(await a.locator('.lobby-add-cpu .btn').boundingBox(), addBounds);
  }
  assert.ok(await a.getByRole('button', { name: '3 / 3 CPUs' }).isDisabled());
  await a.locator('.remove-cpu').first().click();
  await a.waitForFunction(() => document.querySelectorAll('.remove-cpu').length === 2);
  assert.deepEqual(await addCpu.boundingBox(), addBounds);
  await addCpu.click();
  await a.waitForFunction(() => document.querySelectorAll('.remove-cpu').length === 3);
  step('B finds it in the list and joins');
  await click(b, 'Multiplayer');
  await b.getByRole('button', { name: 'Join' }).first().waitFor({ timeout: 10000 });
  await b.locator('.crow').filter({ hasText: 'CLASSIC' }).getByRole('button', { name: 'Join' }).click();
  await b.getByText('PUBLIC ROOM', { exact: false }).waitFor({ timeout: 10000 });
  await a.getByText('PLAYER 2', { exact: true }).waitFor({ timeout: 5000 });
  await shot(a, 'online-lobby');
  step('both ready: the flag, the countdown, GO');
  await click(a, 'Ready');
  await click(b, 'Ready');
  await a.locator('.hud').waitFor({ timeout: 10000 });
  await b.locator('.hud').waitFor({ timeout: 10000 });
  assert.equal(await mode(a), 'thirdPerson');
  assert.equal(await mode(b), 'thirdPerson');
  await switchView(a, 'firstPerson');
  assert.equal(await mode(b), 'thirdPerson');
  await a.waitForTimeout(5800);
  step('A drives');
  await a.keyboard.down('KeyW');
  await a.waitForTimeout(2500);
  await shot(a, 'online-race-a');
  await shot(b, 'online-race-b');
  await a.keyboard.up('KeyW');
  const pos = await a.locator('.hud-screen').filter({ hasText: 'POS' }).locator('.hud-value').textContent();
  step(`A is ${pos} in a race of ${pos?.split('/')[1]} cars (expected 5: 2 players and 3 CPUs)`);
  if (!pos?.endsWith('/5')) throw new Error(`expected 5 cars, HUD shows ${pos}`);

} finally {
  await browser.close();
}
if (errors.length) {
  console.error(`page errors:\n${errors.join('\n')}`);
  process.exit(1);
}
console.log('ok');
