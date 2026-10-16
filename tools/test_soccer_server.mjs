// End-to-end WebSocket test against a running server. Requires Node 22+.
// node tools/test_soccer_server.mjs ws://localhost:8080
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
const url = process.argv[2] || 'ws://localhost:8080';
const clients = [];
async function until(test, label, timeout = 20000) {
  const start = Date.now();
  while (!test()) {
    if (Date.now() - start > timeout) throw new Error('Timed out: ' + label);
    await delay(20);
  }
}
async function connect() {
  const c = { ws: new WebSocket(url), messages: [], states: new Map() };
  clients.push(c);
  c.ws.addEventListener('message', ({ data }) => {
    c.messages.push(String(data));
    if (data.startsWith('SOCCER_JOINED|')) {
      const [, code, slot] = data.split('|'); c.code = code; c.slot = Number(slot);
    }
    if (data.startsWith('SOCCER_STATE|')) {
      c.state = JSON.parse(data.substring(13)); c.states.set(c.state.tick, data);
      if (c.states.size > 100) c.states.delete(c.states.keys().next().value);
    }
  });
  await until(() => c.ws.readyState === WebSocket.OPEN, 'socket open');
  c.send = message => c.ws.send(message);
  return c;
}
function sameState(a, b) {
  const ticks = [...a.states.keys()].filter(t => b.states.has(t));
  assert(ticks.length > 0, 'Both players received a shared simulation tick');
  const tick = ticks.at(-1); assert.equal(a.states.get(tick), b.states.get(tick));
}
async function oneVersusOne() {
  const host = await connect(); host.send('SOCCER|CREATE|1');
  await until(() => host.code && host.state, 'create 1v1');
  assert.equal(host.state.phase, 0);
  const guest = await connect(); guest.send('SOCCER|JOIN|' + host.code);
  await until(() => host.state.phase === 1 && guest.state?.phase === 1, 'two-player kickoff');
  assert.equal(host.slot, 0); assert.equal(guest.slot, 1);
  const extra = await connect(); extra.send('SOCCER|JOIN|' + host.code);
  await until(() => extra.messages.some(m => m.startsWith('SOCCER_ERROR|')), 'reject full room');
  // Play an entire match using only normal controller inputs, no state injection.
  let phase = -1, playingSince = 0;
  const drive = setInterval(() => {
    if (host.state.phase !== phase) { phase = host.state.phase; if (phase === 2) playingSince = Date.now(); }
    if (phase === 2) {
      host.send('SOCCER|INPUT|1|0|1|0');
      const move = Date.now() - playingSince < 1200;
      guest.send(move ? 'SOCCER|INPUT|1|1|0|0' : 'SOCCER|INPUT|0|0|0|0');
    }
  }, 50);
  try {
    await until(() => host.state.blue >= 1 && guest.state?.blue >= 1, 'shared goal', 30000);
    sameState(host, guest);
    assert(host.state.effects.find(e => e[0] === 0)[5] >= 2, 'Driver collected boost and a power-up');
    assert(host.state.pickups.some(cooldown => cooldown > 0), 'Pickup cooldowns replicate');
    console.log('PASS 1v1: kickoff, pickups, shared power-up effects, car/ball collision, goal and identical snapshots');
    await until(() => host.state.phase === 4 && guest.state?.phase === 4, 'first to five', 120000);
    assert.equal(host.state.blue, 5); sameState(host, guest);
    guest.send('SOCCER|REMATCH'); await delay(150);
    assert.equal(host.state.phase, 4, 'Only the host can restart');
    host.send('SOCCER|REMATCH');
    await until(() => host.state.phase === 1 && host.state.blue === 0, 'rematch resets score');
    console.log('PASS 1v1: five-goal win, host-only rematch, score reset');
  } finally { clearInterval(drive); }
  guest.ws.close();
  await until(() => host.state.phase === 0 && host.state.cars.length === 1, 'disconnect pauses match');
  const replacement = await connect(); replacement.send('SOCCER|JOIN|' + host.code);
  await until(() => host.state.phase === 1 && replacement.state?.cars.length === 2, 'replacement player');
  host.ws.close(); replacement.ws.close(); extra.ws.close();
  console.log('PASS 1v1: disconnect cleanup and replacement player');
}
async function twoVersusTwo() {
  const players = [await connect()]; players[0].send('SOCCER|CREATE|2');
  await until(() => players[0].code && players[0].state, 'create 2v2');
  for (let i = 1; i < 4; i++) {
    const c = await connect(); players.push(c); c.send('SOCCER|JOIN|' + players[0].code);
    await until(() => c.code && c.state, 'join player ' + i);
    if (i < 3) assert.equal(c.state.phase, 0, 'Wait for four players');
  }
  await until(() => players.every(c => c.state?.phase === 2), 'four-player kickoff');
  assert.deepEqual(players.map(c => c.slot % 2), [0, 1, 0, 1]);
  players[0].send('SOCCER|INPUT|1|0|1|1');
  await until(() => players[0].state.cars[0][3] > 1 && players[0].state.cars[0][5] < 100, 'jump and boost replicated');
  const aerial = setInterval(() => players[0].send('SOCCER|INPUT|0|0|1|1'), 50);
  try {
    await until(() => players.every(c => c.state.cars.find(car => car[0] === 0)?.[3] > 300), 'high aerial boost on all peers');
  } finally { clearInterval(aerial); }
  players[0].send('SOCCER|INPUT|0|0|0|0');
  for (let i = 1; i < 4; i++) sameState(players[0], players[i]);
  players[2].ws.close();
  await until(() => players[0].state.phase === 0 && players[0].state.cars.length === 3, '2v2 disconnect');
  const c = await connect(); c.send('SOCCER|JOIN|' + players[0].code);
  await until(() => players[0].state.phase === 1 && c.state?.cars.length === 4, '2v2 replacement');
  for (const p of [...players, c]) p.ws.close();
  console.log('PASS 2v2: balanced teams, four-player kickoff, high aerial boost, shared state, replacement');
}
try {
  await oneVersusOne();
  await twoVersusTwo();
  console.log('All soccer multiplayer checks passed.');
} finally { for (const c of clients) c.ws.close(); }
