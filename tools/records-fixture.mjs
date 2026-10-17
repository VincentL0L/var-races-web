// Vite-only test harness. This file is never imported by the shipped game.
import { app } from '../web/src/app.ts';
import { recordKey, readBest, saveBest, TrialRecording, ghostFrame } from '../web/src/game/records.ts';
import { LocalSource } from '../web/src/game/session.ts';
import { mapById } from '../web/src/game/backdrops.ts';
import { setPref } from '../web/src/prefs.ts';
import { NO_CONTROLS } from '../web/src/input.ts';
const assert = (ok, why) => { if (!ok) throw new Error(why); };
const key = recordKey('test-record', 1, 1);
let menuStage;
export function captureMenuStage() { menuStage = app.currentStage(); assert(menuStage !== null, 'live menu stage'); }
export function menuStageState() { return { same: menuStage === app.currentStage(), car: menuStage.car }; }
export async function persistedTime() { return (await readBest(key))?.time; }
export async function runChecks() {
  assert(recordKey('classic', 1, 1) === recordKey('classic', 1, 3), 'paints must share PBs');
  assert(recordKey('classic', 1, 1) !== recordKey('classic', 1, 4), 'models must separate PBs');
  assert(recordKey('classic', 1, 1) !== recordKey('classic', 2, 1), 'laps must separate PBs');
  const recording = new TrialRecording();
  for (let i = 0; i <= 300; i++) recording.sample(i / 60, i, i * 2, 3.1, i === 300);
  const first = recording.finish(key, 1, 5);
  assert(first.samples.length >= 400 && first.samples.length <= 408, 'record at 20 Hz');
  assert((await saveBest(first)).improved, 'first PB');
  assert(!(await saveBest({ ...first, time: 6 })).improved, 'slower run');
  assert(!(await saveBest(first)).improved, 'tied run');
  const faster = { ...first, time: 4, samples: new Float32Array([0, 10, 20, 3.1, 4, 30, 40, -3.1]) };
  assert((await saveBest(faster)).improved, 'faster run');
  const frame = ghostFrame(faster, 2);
  assert(frame.x === 20 && frame.y === 30, 'ghost position interpolation');
  assert(Math.abs(Math.abs(frame.heading) - Math.PI) < 0.001, 'ghost heading shortest arc');
  assert(ghostFrame(faster, 5) === null, 'ghost ends after finish');
  assert(await readBest('0:test-record:1:0') === null, 'revision isolation');
  const long = new TrialRecording();
  long.sample(0, 0, 0, 0); long.sample(1201, 1, 1, 0, true);
  assert(long.finish('long', 1, 1201).samples.length === 0, 'recording memory bound');
  const source = new LocalSource(mapById('classic'), 1, 1);
  await source.prepare();
  const initial = source.latest().tick;
  source.tick(1, NO_CONTROLS, true);
  assert(source.latest().tick === initial, 'pause freezes simulation and recording');
  const retry = source.retry();
  assert(retry.latest().race_time === 0 && retry.latest().cars.length === 1 && retry.latest().boxes.length === 0, 'retry is clean solo simulation');
  retry.dispose();
  assert(source.trialResult === undefined, 'abandoned run must not save');
  // Drive the recorder with controlled fixed-tick snapshots, including the exact finish.
  const race = source.race;
  const snap = source.latest();
  const me = snap.cars[0];
  let tick = 0;
  const originalStep = race.step.bind(race), originalSnapshot = race.snapshot.bind(race);
  race.step = () => { tick++; };
  race.snapshot = () => ({ ...snap, phase: tick < 61 ? 'Racing' : 'Over', race_time: tick / 60,
    cars: [{ ...me, car: { ...me.car, pos: { x: tick, y: tick * 2 } }, finish_time: tick < 61 ? null : 61 / 60 }] });
  for (let i = 0; i < 62; i++) source.tick(1 / 60, NO_CONTROLS, false);
  const result = await source.trialResult;
  assert(result?.time === 61 / 60 && result.improved, 'save exact simulation finish time');
  const best = await readBest(recordKey('classic', 1, 1));
  assert(Math.abs(best.samples.at(-4) - 61 / 60) < 0.00001, 'exact final recording sample');
  race.step = originalStep; race.snapshot = originalSnapshot;
  source.dispose();
  setPref('ghost', true);
  const withGhost = new LocalSource(mapById('classic'), 1, 1);
  setPref('ghost', false);
  const withoutGhost = new LocalSource(mapById('classic'), 1, 1);
  await Promise.all([withGhost.prepare(), withoutGhost.prepare()]);
  for (let i = 0; i < 320; i++) {
    withGhost.tick(1 / 60, { ...NO_CONTROLS, gas: true }, false);
    withoutGhost.tick(1 / 60, { ...NO_CONTROLS, gas: true }, false);
  }
  assert(withGhost.ghost() !== null && withoutGhost.ghost() === null, 'ghost preference affects playback');
  assert(JSON.stringify(withGhost.latest()) === JSON.stringify(withoutGhost.latest()), 'ghost cannot affect simulation');
  withGhost.dispose(); withoutGhost.dispose();
  setPref('ghost', true);
  return ['PB keys, atomic faster-only replacement, interpolation, revision isolation, bounded recording', 'fixed-tick recording, pause, abandonment, retry, and finish persistence'];
}
export async function storageFailureCheck() {
  // Force a distinct module instance with an unopened database, then emulate blocked storage.
  const original = indexedDB.open.bind(indexedDB);
  indexedDB.open = () => { throw new Error('Storage blocked'); };
  try {
    const records = await import('../web/src/game/records.ts?blocked-storage-test');
    const result = await records.saveBest({ key: 'blocked', car: 1, time: 5, samples: new Float32Array() });
    assert(result.improved && !result.saved, 'failed storage should return session-only success');
    assert((await records.readBest('blocked'))?.time === 5, 'session fallback');
  } finally { indexedDB.open = original; }
}

export async function garageLifecycleChecks() {
  const { nav } = await import('../web/src/nav.ts');
  const { app } = await import('../web/src/app.ts');
  const { carSprite } = await import('../web/src/assets.ts');
  const stage = app.currentStage();
  for (let visit = 0; visit < 3; visit++) {
    await nav.garage();
    const preview = app.screen.preview;
    for (let car = 1; car <= 15; car++) {
      preview.show(carSprite(car));
      for (let wait = 0; !preview.model.models.length && wait < 100; wait++) await new Promise((r) => setTimeout(r, 10));
      assert(preview.model.models.length === 2, `model/paint ${car} did not load`);
      preview.frame(1 / 60);
    }
    await nav.menu();
    assert(preview.gl.info.memory.geometries === 0, 'garage geometry leak');
    assert(preview.gl.info.memory.textures === 0, 'garage texture leak');
    assert(app.currentStage() === stage, 'garage restarted backdrop');
  }
}

/** Six orthogonal views for inspecting the actual cube UVs and texture. */
export async function prepareBlockShot() {
  const { CarPreview } = await import('../web/src/render/carPreview.ts');
  const { ItemsView } = await import('../web/src/render/items.ts');
  const { texture } = await import('../web/src/assets.ts');
  await texture('ui/items/box_face.png');
  const preview = new CarPreview();
  preview.canvas.style.cssText = 'position:fixed;left:0;top:0;width:720px;height:480px;background:#281b12;z-index:100';
  document.body.append(preview.canvas);
  preview.camera.position.set(0, -55, 0);
  preview.camera.lookAt(0, 0, 0);
  const items = new ItemsView(Array.from({ length: 6 }, () => [0, 0]));
  items.update({ boxes: Array(6).fill(true), rockets: [], shots: [], slicks: [], shields: [] }, preview.camera.position, 0);
  await new Promise((r) => setTimeout(r, 100));
  const meshes = items.root.children.filter((o) => o.isMesh);
  items.root.children.forEach((o) => { o.visible = o.isMesh; });
  meshes.forEach((cube, i) => {
    cube.position.set((i % 3 - 1) * 16, 0, i < 3 ? 8 : -8);
    cube.rotation.set(i === 4 ? Math.PI / 2 : i === 5 ? -Math.PI / 2 : 0, 0,
      i === 1 ? -Math.PI / 2 : i === 2 ? Math.PI : i === 3 ? Math.PI / 2 : 0);
  });
  preview.scene.add(items.root);
  preview.frame(0);
}
