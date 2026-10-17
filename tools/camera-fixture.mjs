// Loaded through Vite so the harness shares the application's exact module instances,
// including after hot reload. Never imported by the production game.
import { app } from '../web/src/app.ts';
import { net } from '../web/src/net.ts';
import { pref, setPref } from '../web/src/prefs.ts';
import { LocalRace, encode } from '../web/src/wasm.ts';
import { allMaps, mapById, TitleStage } from '../web/src/game/backdrops.ts';
import { OnlineSource } from '../web/src/game/online.ts';
import { LocalSource } from '../web/src/game/session.ts';
import { RaceStage } from '../web/src/game/race.ts';
import { CameraRig, RACE_CAMERA } from '../web/src/render/camera.ts';
import * as THREE from 'three';

export async function runChecks() {
  const assert = (ok, why) => { if (!ok) throw new Error(why); };
  const key = (type, code) => window.dispatchEvent(new KeyboardEvent(type, { code, bubbles: true }));
  const release = () => ['KeyW', 'KeyA', 'KeyV', 'Space', 'ShiftLeft'].forEach((k) => key('keyup', k));
  const turnView = () => { key('keydown', 'KeyV'); key('keyup', 'KeyV'); };
  let packets = [];
  const oldSend = net.send, oldConnect = net.connect;
  net.send = (msg) => { packets.push([...encode(msg)]); return true; };
  net.connect = () => {};
  const seedRace = (id) => {
    const race = new LocalRace(id, 123);
    const slot = race.addPlayer('Player 1', 4);
    race.start();
    for (let i = 0; i < 400 && race.snapshot().phase !== 'Racing'; i++) race.step(slot, 0);
    const first = { Race: { snapshot: race.snapshot(), events: race.takeEvents(), ack: 0 } };
    race.free();
    return { slot, first };
  };
  const run = async (id, toggles) => {
    release();
    setPref('cameraMode', 'thirdPerson');
    let stage, source;
    {
      const { slot, first } = seedRace(id);
      source = new OnlineSource(allMaps().find((m) => m.id === id), slot, [{ slot, name: 'Player 1', car: 4, ready: true }], first);
      stage = await RaceStage.open(source);
    }
    packets = [];
    key('keydown', 'KeyW');
    for (let i = 0; i < 90; i++) {
      if (i === 20) key('keydown', 'KeyA');
      if (i === 40) key('keyup', 'KeyA');
      if (i === 45) key('keydown', 'Space');
      if (toggles && i % 9 === 0) {
        const before = packets.length;
        turnView();
        assert(packets.length === before, 'view switch sent a message');
      }
      stage.frame(1 / 60);
    }
    const state = source.myCar();
    assert(packets.length === 90, `expected 90 input packets for ${id} (toggles=${toggles}), got ${packets.length}`);
    assert(packets.some((p) => JSON.stringify(p) !== JSON.stringify(packets[0])), 'script must exercise changing controls');
    const captured = { packets: [...packets], state: JSON.stringify(state) };
    release();
    app.setStage(null);
    return captured;
  };
  const checks = [];
  for (const id of ['classic', 'summit', 'junkyard']) {
    const a = await run(id, false), b = await run(id, true);
    assert(JSON.stringify(a) === JSON.stringify(b), `camera changed input bytes or prediction: ${id}`);
    checks.push(`${id}: 90 identical input packets and identical predicted state`);
  }
  // Camera pose and collision regressions; these operate exclusively on visual geometry.
  const rig = new CameraRig(RACE_CAMERA);
  rig.resize(1280, 720);
  const pose = { x: 0, y: 0, z: 0, heading: 0 };
  rig.update(pose, 6, 1 / 60);
  assert(rig.camera.position.x < -50 && rig.camera.position.z > 20, 'chase camera must be behind and above');
  const wall = new THREE.Mesh(new THREE.BoxGeometry(4, 100, 100), new THREE.MeshBasicMaterial());
  wall.position.set(-30, 0, 20);
  wall.updateMatrixWorld(true);
  rig.update(pose, 6, 1 / 60, [wall]);
  assert(rig.camera.position.x > -28, 'camera crossed a wall');
  rig.update({ ...pose, x: 1000 }, 6, 1 / 60);
  assert(rig.camera.position.x > 900, 'teleport must reset camera smoothing');
  rig.setMode('firstPerson');
  rig.update({ ...pose, z: 50 }, 13.3, 1 / 60);
  assert(Math.abs(rig.camera.position.z - 63.3) < 0.01, 'camera must follow jump height');
  rig.resize(390, 844);
  assert(rig.camera.aspect < 1 && Number.isFinite(rig.camera.fov), 'portrait camera');
  wall.geometry.dispose(); wall.material.dispose();
  checks.push('camera obstruction, teleport, jump height, and portrait framing');

  // Exercise every map's perspective scenery and both models, including local sources.
  for (const map of allMaps().filter((m) => m.kind !== 'battle')) {
    setPref('cameraMode', 'thirdPerson');
    const stage = await RaceStage.open(new LocalSource(map, 4, 1));
    stage.frame(1 / 60);
    app.renderer.render(stage.view());
    turnView(); stage.frame(1 / 60); app.renderer.render(stage.view());
    assert(stage.el.dataset.cameraMode === 'firstPerson', `view toggle failed: ${map.id}`);
    app.setStage(null);
  }
  checks.push('all solo maps render in both views with local simulation');
  const raceMemory = [];
  for (let i = 0; i < 4; i++) {
    const stage = await RaceStage.open(new LocalSource(allMaps()[0], 4, 1));
    await new Promise((r) => setTimeout(r, 100));
    for (let j = 0; j < 8; j++) { turnView(); stage.frame(1 / 60); app.renderer.render(stage.view()); }
    app.setStage(null); app.renderer.render(null);
    const { geometries, textures } = app.renderer.gl.info.memory;
    raceMemory.push([geometries, textures]);
  }
  assert(raceMemory.slice(1).every((n) => JSON.stringify(n) === JSON.stringify(raceMemory[1])), `race resources leaked: ${JSON.stringify(raceMemory)}`);
  checks.push(`race disposal: stable geometry/texture counts ${JSON.stringify(raceMemory)}`);


  for (const create of [() => TitleStage.create()]) {
    const stage = await create(); app.setStage(stage); stage.frame(1 / 60); app.renderer.render(stage.view());
    assert(stage.view().camera().isPerspectiveCamera, 'menu retained orthographic camera');
  }
  app.setStage(null);
  net.send = oldSend; net.connect = oldConnect;
  return checks;
}

export function currentPreference() { return pref('cameraMode'); }
export async function prepareShot() {
  setPref('cameraMode', 'thirdPerson');
  const stage = await RaceStage.open(new LocalSource(mapById('tokyo'), 4, 1));
  for (let i = 0; i < 600; i++) stage.frame(1 / 60);
  await new Promise((r) => setTimeout(r, 200));
  renderCurrent();
}
export function renderCurrent() {
  app.currentStage().frame(1 / 60);
  app.renderer.render(app.currentStage().view());
}
