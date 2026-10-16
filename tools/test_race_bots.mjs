// End-to-end test against a running race server (Node 22+).
// node tools/test_race_bots.mjs ws://localhost:8080
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
const url = process.argv[2] || 'ws://localhost:8080';
const clients = [];
async function until(test, label) {
  const deadline = Date.now() + 15000;
  while (!test()) {
    if (Date.now() > deadline) throw new Error('Timed out: ' + label);
    await delay(20);
  }
}
async function connect() {
  const c = { ws: new WebSocket(url), messages: [] };
  clients.push(c);
  c.ws.addEventListener('message', ({ data }) => {
    const message = String(data);
    c.messages.push(message);
    if (message.startsWith('JOINED|')) c.code = message.split('|')[1];
    if (message.startsWith('SETTINGS|')) c.settings = message;
    if (message.startsWith('HOST|')) c.host = message.split('|')[1];
  });
  await until(() => c.ws.readyState === WebSocket.OPEN, 'connect');
  c.send = message => c.ws.send(message);
  return c;
}
async function setting(c, value) {
  c.send('SETTINGS|1|1|' + value);
  await until(() => c.settings === 'SETTINGS|1|1|' + value, 'bots ' + value);
}
try {
  const host = await connect();
  host.send('CREATE|private|1|classic');
  await until(() => host.settings, 'create room');
  assert.equal(host.settings, 'SETTINGS|1|1|true');
  await until(() => host.messages.some(m => m.startsWith('POS|CPU')), 'default bots');
  const guest = await connect();
  guest.send('JOIN|' + host.code + '|2');
  await until(() => guest.settings, 'guest join');
  guest.send('SETTINGS|1|1|false');
  await delay(200);
  assert.equal(host.settings, 'SETTINGS|1|1|true', 'Non-host cannot remove bots');
  await setting(host, false);
  await until(() => guest.settings === host.settings, 'guest settings sync');
  for (const c of [host, guest]) {
    for (let i = 1; i <= 3; i++) assert(c.messages.includes('LEFT|CPU' + i));
    c.messages.length = 0;
  }
  await delay(250);
  assert(!host.messages.some(m => m.startsWith('POS|CPU')), 'No ghost bot positions');
  await setting(host, true);
  await until(() => guest.messages.some(m => m.startsWith('POS|CPU')), 'bots restored');
  await setting(host, false);
  const late = await connect();
  late.send('JOIN|' + host.code + '|3');
  await until(() => late.settings, 'late join');
  assert.equal(late.settings, 'SETTINGS|1|1|false', 'Late join inherits bots off');
  host.ws.close();
  await until(() => guest.host === 'Player 2', 'host transfer');
  await setting(guest, true);
  await setting(guest, false);
  guest.send('READY|true'); late.send('READY|true');
  await until(() => guest.messages.includes('FLAG'), 'countdown');
  guest.send('SETTINGS|1|1|true');
  await until(() => guest.messages.includes('COUNTDOWN|GO!'), 'race start');
  assert.equal(guest.settings, 'SETTINGS|1|1|false', 'Countdown locks settings');
  guest.messages.length = 0;
  await until(() => guest.messages.some(m => m.startsWith('LEADER|')), 'leaderboard');
  await delay(300);
  assert(!guest.messages.some(m => m.startsWith('POS|CPU')), 'No CPU cars during race');
  for (const m of guest.messages.filter(m => m.startsWith('LEADER|'))) {
    assert(!m.includes('|CPU'), 'No CPU standings');
    assert(m.includes('Player 2') && m.includes('Player 3'), 'Human racers remain');
  }
  console.log('PASS: default bots, host-only toggle, shared settings, bot removal/restoration, late join, host transfer, countdown lock and players-only race');
  const legacy = await connect(); legacy.send('CREATE|public|1|classic');
  await until(() => legacy.settings, 'legacy room');
  await setting(legacy, false);
  legacy.send('SETTINGS|2|2');
  await until(() => legacy.settings === 'SETTINGS|2|2|false', 'legacy settings retain bot choice');
  console.log('PASS: legacy settings preserve the bot choice');
} finally {
  for (const c of clients) c.ws.close();
}
