// End-to-end check in real Chrome against a running dev server and race server:
// two players meet online and race, then play a soccer match.
//   node tools/e2e.mjs [http://localhost:8000] [screenshot folder]
// Needs Google Chrome and `npm run dev` (or vite + the server) running.
import { chromium } from 'playwright-core';

const url = process.argv[2] ?? 'http://localhost:8000/';
const shots = process.argv[3];
const browser = await chromium.launch({ channel: 'chrome', args: ['--use-angle=metal', '--ignore-gpu-blocklist'] });
const errors = [];
const open = async (name) => {
  const page = await (await browser.newContext({ viewport: { width: 1280, height: 720 } })).newPage();
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  page.on('console', (m) => m.type() === 'error' && errors.push(`${name}: ${m.text()}`));
  await page.goto(url);
  await page.getByText('Multiplayer', { exact: true }).waitFor();
  return page;
};
const click = (page, text) => page.getByText(text, { exact: true }).first().click();
const shot = (page, name) => shots && page.screenshot({ path: `${shots}/${name}.png` });
const step = (msg) => console.log(`- ${msg}`);

try {
  const a = await open('A'), b = await open('B');
  step('A hosts a public race');
  await click(a, 'Multiplayer');
  await a.getByText('MULTIPLAYER', { exact: true }).waitFor({ timeout: 20000 });
  await click(a, 'Public');
  await click(a, 'Create');
  await a.getByText('PUBLIC ROOM', { exact: false }).waitFor({ timeout: 10000 });
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
  await a.waitForTimeout(5800);
  step('A drives');
  await a.keyboard.down('KeyW');
  await a.waitForTimeout(2500);
  const mph = Number(await a.locator('.hud-gauge').evaluate(() => document.querySelector('.hud-value')?.textContent ?? '0').catch(() => 0));
  await shot(a, 'online-race-a');
  await shot(b, 'online-race-b');
  await a.keyboard.up('KeyW');
  const pos = await a.locator('.hud-screen').filter({ hasText: 'POS' }).locator('.hud-value').textContent();
  step(`A is ${pos} in a race of ${pos?.split('/')[1]} cars (expected 5: 2 players and 3 CPUs)`);
  if (!pos?.endsWith('/5')) throw new Error(`expected 5 cars, HUD shows ${pos}`);
  void mph;

  step('soccer: A creates a 1v1, B joins with the code');
  await click(a, 'Main Menu').catch(async () => {
    await a.keyboard.press('Escape');
    await click(a, 'Main Menu');
  });
  await b.keyboard.press('Escape');
  await click(b, 'Main Menu');
  await click(a, 'Race Car Soccer');
  await click(a, 'Create 1v1');
  const wait = a.getByText('ROOM CODE:', { exact: false });
  await wait.waitFor({ timeout: 10000 });
  const code = (await wait.textContent()).match(/ROOM CODE: (\w+)/)[1];
  await click(b, 'Race Car Soccer');
  await b.locator('input.field').fill(code);
  await click(b, 'Join');
  await a.waitForTimeout(4500);
  await a.keyboard.down('KeyW');
  await a.keyboard.down('ShiftLeft');
  await a.waitForTimeout(2500);
  await shot(a, 'soccer-a');
  await shot(b, 'soccer-b');
  step(`soccer score: ${await a.locator('.soccer-strip .title').textContent()}`);
} finally {
  await browser.close();
}
if (errors.length) {
  console.error(`page errors:\n${errors.join('\n')}`);
  process.exit(1);
}
console.log('ok');
