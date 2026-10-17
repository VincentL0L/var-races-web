// Opens the game in Chrome (headless), optionally presses keys or clicks, and saves a
// screenshot. For checking the game by eye during development.
//   node tools/shot.mjs <url> <out.png> [steps as JSON]
// steps: [{"wait": ms}, {"click": "text"}, {"key": "KeyW", "hold": ms}, {"eval": "js"}, {"shot": "file.png"}]
import { chromium } from 'playwright-core';

const [url, out, stepsJson] = process.argv.slice(2);
const browser = await chromium.launch({
  channel: 'chrome',
  args: ['--use-angle=metal', '--enable-gpu', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
const logs = [];
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`));
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`));
await page.goto(url);
await page.waitForTimeout(1500);
for (const step of JSON.parse(stepsJson ?? '[]')) {
  if (step.wait) await page.waitForTimeout(step.wait);
  if (step.click) await page.getByText(step.click, { exact: true }).first().click();
  if (step.key) {
    await page.keyboard.down(step.key);
    await page.waitForTimeout(step.hold ?? 50);
    await page.keyboard.up(step.key);
  }
  if (step.down) await page.keyboard.down(step.down);
  if (step.up) await page.keyboard.up(step.up);
  if (step.eval) logs.push(`[eval] ${JSON.stringify(await page.evaluate(step.eval))}`);
  if (step.shot) await page.screenshot({ path: step.shot });
}
await page.screenshot({ path: out });
console.log(logs.join('\n'));
await browser.close();
