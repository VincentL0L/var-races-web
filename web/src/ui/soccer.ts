// Race Car Soccer: play the CPU (the match runs right here, LocalSoccer), or create a 1v1 or
// 2v2 room and share its code, or join a friend's. The match is first person only. Online the
// server plays the match; our own car is run ahead of it on our controls (SoccerPredictor),
// the ball and the other cars glide to where the server says.
import { app, type Screen, type Stage } from '../app';
import * as audio from '../audio';
import { button, h } from '../dom';
import { NO_CONTROLS, onKey, pack, packBytes, soccerKeys, touchScreen, type Controls } from '../input';
import { FixedStep } from '../game/frames';
import { net, WAKING_AFTER } from '../net';
import { nav } from '../nav';
import { SoccerView, BLUE, ORANGE } from '../render/soccerView';
import { LocalSoccer, SoccerPredictor, type Announce, type ServerMsg, type SoccerCar, type SoccerMatch, type SoccerRoomInfo } from '../wasm';
import { card, cardTitle, centered, kicker, row, smallButton } from './cards';
import { TouchControls } from './touch';

const ANNOUNCE: Record<Announce, string> = {
  WaitingForPlayers: 'Waiting for players',
  PlayerLeft: 'Player left. Waiting for a replacement',
  Kickoff: 'KICKOFF',
  Go: 'GO!',
  Overtime: 'OVERTIME - next goal wins',
  BlueScores: 'BLUE SCORES!',
  OrangeScores: 'ORANGE SCORES!',
  BlueWins: 'BLUE WINS!',
  OrangeWins: 'ORANGE WINS!',
};

const PICKUP_NAMES: Record<string, string> = {
  BoostPad: 'BOOST +25', FullBoost: 'BOOST FULL', Nitro: 'NITRO', PowerShot: 'POWER SHOT', Magnet: 'MAGNET',
};

/** the setup card: create a match, or join one */
export class SoccerMenuScreen implements Screen {
  readonly el: HTMLElement;
  private readonly status = h('div.label', { style: { minHeight: '40px' } }, 'Connecting to the game server...');
  private readonly rooms = h('div');
  private readonly code = h('input.field', { placeholder: 'ROOM CODE', maxLength: 5, style: { flex: '1', textTransform: 'uppercase' } });
  private readonly stop: () => void;
  private list: SoccerRoomInfo[] = [];
  private refresh = 2;

  constructor() {
    audio.stopMusic();
    net.connect();
    const join = () => {
      const code = this.code.value.trim().toUpperCase();
      if (code) net.send({ SoccerJoin: { code } });
    };
    this.code.addEventListener('keydown', (e) => e.key === 'Enter' && join());
    this.el = centered(card(
      kicker('FIRST PERSON  /  VS CPU OR ONLINE'),
      cardTitle('RACE CAR SOCCER', 34),
      h('div.label', { style: { whiteSpace: 'pre-line', marginBottom: '12px' } },
        'First to 5, or lead after 3 minutes. Ties go to overtime.\nDrive over pickups - they activate automatically.\nGOLD: boost  |  CYAN: nitro  |  PINK: power shot\nPURPLE: magnet - pull a nearby ball to your bumper.'),
      h('div.row', { style: { flexWrap: 'nowrap' } },
        button('VS CPU 1v1', () => this.local(1)),
        button('VS CPU 2v2', () => this.local(2))),
      h('div.row', { style: { flexWrap: 'nowrap' } },
        button('Create 1v1', () => net.send({ SoccerCreate: { team_size: 1 } })),
        button('Create 2v2', () => net.send({ SoccerCreate: { team_size: 2 } }))),
      kicker('JOIN WITH A CODE'),
      h('div.row', { style: { flexWrap: 'nowrap' } }, this.code, smallButton('Join', join)),
      this.status,
      kicker('OPEN ROOMS'),
      this.rooms,
      h('div.card-buttons', null, smallButton('Back', () => {
        net.disconnect();
        void nav.mapSelect();
      })),
    ));
    this.stop = net.on((msg) => this.message(msg));
  }

  /** single player: us on blue, the CPU on orange (and a CPU team-mate in 2v2) */
  private local(teamSize: number): void {
    net.disconnect();
    const match = new SoccerStage('CPU', 0, teamSize);
    app.setStage(match);
    app.show(match.screen);
  }

  private message(msg: ServerMsg): void {
    if (typeof msg !== 'object') return;
    if ('SoccerRooms' in msg) {
      this.list = msg.SoccerRooms;
      this.renderRooms();
    } else if ('Error' in msg) this.status.textContent = msg.Error;
    else if ('SoccerJoined' in msg) {
      const { code, slot } = msg.SoccerJoined;
      net.inRoom = true;
      const match = new SoccerStage(code, slot);
      app.setStage(match);
      app.show(match.screen);
    }
  }

  private renderRooms(): void {
    if (!this.list.length) {
      this.rooms.replaceChildren(row(h('span.grow.muted', null, 'None yet - create one and share the code.')));
      return;
    }
    this.rooms.replaceChildren(...this.list.slice(0, 4).map((r) => row(
      h('span.gold', { style: { width: '80px' } }, r.code),
      h('span.grow', null, `${r.team_size}v${r.team_size}   ${r.players}/${r.team_size * 2}`),
      smallButton('Join', () => net.send({ SoccerJoin: { code: r.code } })),
    )));
  }

  frame(delta: number): void {
    if (net.connected) {
      if (this.status.textContent?.startsWith('Connecting') || this.status.textContent?.startsWith('Waking')) {
        this.status.textContent = "Create a match or join a friend's room code.";
      }
      this.refresh += delta;
      if (this.refresh >= 2) {
        this.refresh = 0;
        net.send('SoccerList');
      }
    } else {
      this.status.textContent = net.secondsTrying > WAKING_AFTER ? 'Waking up the server... (up to a minute)' : 'Connecting to the game server...';
    }
  }

  dispose(): void {
    this.stop();
  }
}

/** a match: the stadium, our car run ahead of the server, and the match's HUD */
class SoccerStage implements Stage {
  readonly screen: Screen;
  /** the stadium, built when the first state arrives (with the pickup pads) */
  private v: SoccerView | null = null;
  private readonly predictor = new SoccerPredictor();
  private readonly step = new FixedStep();
  private readonly stop: () => void;
  private readonly stopKeys: () => void;
  private readonly touch: TouchControls | null;
  private state: SoccerMatch | null = null;
  private host: number | null = null;
  private shown = new Map<number, SoccerCar>();
  private ball = { x: 700, y: 450, z: 26 };
  private seq = 0;
  private sent: [number, number][] = [];
  private sinceState = 0;
  private lastSerial = -1;
  private toastTime = 0;
  private jumpPending = false;
  private disposed = false;
  // the HUD
  private readonly roomLabel = h('span', { style: { width: '280px' } });
  private readonly score = h('span.title.small.plain', { style: { flex: '1', textAlign: 'center' } });
  private readonly clock = h('span', { style: { width: '100px', textAlign: 'right' } });
  private readonly banner = h('div.soccer-banner.title.medium');
  private readonly toast = h('div.soccer-toast.title.small');
  private readonly boost = h('span');
  private readonly powers = h('span');
  private readonly ballInfo = h('span');
  private readonly waiting = h('div.label', { style: { whiteSpace: 'pre-line', minHeight: '140px', textAlign: 'center' } });
  private readonly winner = h('div.title.medium.plain');
  private readonly rematch: HTMLButtonElement;
  private readonly waitCard: HTMLElement;
  private readonly endCard: HTMLElement;

  /** a match against the CPU, simulated here instead of on the server (null online) */
  private readonly local: LocalSoccer | null;

  constructor(private readonly code: string, private readonly slot: number, localTeamSize = 0) {
    this.local = localTeamSize > 0 ? new LocalSoccer(localTeamSize) : null;
    this.rematch = button('Rematch', () => (this.local ? this.local.rematch() : net.send('Rematch')));
    this.waitCard = h('div.screen', null, card(cardTitle('WAITING FOR PLAYERS', 26), this.waiting, button('Leave Room', () => this.leave())));
    this.endCard = h('div.screen', null, card(this.winner, this.rematch, button('Main Menu', () => this.leave())));
    this.touch = touchScreen ? new TouchControls(true, () => {}) : null;
    const strip = h('div.soccer-strip', null, this.roomLabel, this.score, this.clock, smallButton('Leave', () => this.leave()));
    const readout = h('div.soccer-strip', null, this.boost, this.powers, this.ballInfo);
    const hint = touchScreen ? null : h('div.soccer-strip.muted', null, 'WASD / ARROWS drive   |   SHIFT boost   |   SPACE jump   |   ESC leave');
    this.screen = { el: h('div.screen.soccer', { style: { padding: '0', justifyContent: 'space-between' } },
      strip, this.banner, this.toast, h('div', { style: { width: '100%' } }, readout, hint), this.touch?.el, this.waitCard, this.endCard) };
    this.stop = this.local ? () => {} : net.on((msg) => this.message(msg));
    if (this.local) this.message({ Soccer: { state: this.local.state() as SoccerMatch, host: 0, ack: 0 } } as ServerMsg);
    this.stopKeys = onKey((k) => {
      if (k === 'Escape') this.leave();
      if (k === 'Space') this.jumpPending = true;
    });
  }

  private message(msg: ServerMsg): void {
    if (typeof msg !== 'object' || !('Soccer' in msg)) return;
    const { state, host, ack } = msg.Soccer;
    const reset = !this.state || this.state.phase !== state.phase;
    this.v ??= new SoccerView(state.pickups);
    this.state = state;
    this.host = host ?? null;
    this.sinceState = 0;
    const mine = state.cars.find((c) => c?.slot === this.slot);
    if (mine) {
      this.predictor.reconcile(mine, ack);
      if (mine.pickup_serial > this.lastSerial) {
        if (this.lastSerial >= 0 && mine.last_pickup) {
          this.toast.textContent = `${PICKUP_NAMES[mine.last_pickup]}!`;
          this.toastTime = 2;
          audio.play('item_pickup', 0.45);
        }
        this.lastSerial = mine.pickup_serial;
      }
    }
    if (reset) {
      // a kickoff or the end: everything jumps to where it is
      this.ball = { x: state.ball.x, y: state.ball.y, z: state.ball.z };
      this.shown.clear();
    }
  }

  private controls(): Controls {
    if (!this.state || this.state.phase === 'Finished' || this.state.phase === 'Waiting') return NO_CONTROLS;
    const keys = soccerKeys();
    const c = this.touch?.active() ? this.touch.controls() : keys;
    const jump = c.jump || this.jumpPending;
    this.jumpPending = false;
    return { ...c, jump };
  }

  view(): SoccerView | null {
    return this.v;
  }

  frame(delta: number): void {
    if (this.disposed) return;
    this.step.run(delta, () => {
      const c = this.controls();
      this.seq++;
      if (this.local) {
        // the whole match runs here: one tick, then read it back as if the server sent it
        this.local.step(pack(c));
        this.message({ Soccer: { state: this.local.state() as SoccerMatch, host: 0, ack: this.seq } } as ServerMsg);
        return;
      }
      this.sent.push(packBytes(c));
      while (this.sent.length > 4) this.sent.shift();
      net.send({ Inputs: { last: this.seq, inputs: this.sent } });
      this.predictor.predict(this.seq, pack(c));
    });
    this.sinceState += delta;
    this.toastTime = Math.max(0, this.toastTime - delta);
    const s = this.state;
    if (!s || !this.v) return;
    // the ball and other cars glide toward the server's positions; ours is predicted
    const k = 1 - Math.exp(-25 * delta);
    this.ball = { x: this.ball.x + (s.ball.x - this.ball.x) * k, y: this.ball.y + (s.ball.y - this.ball.y) * k, z: this.ball.z + (s.ball.z - this.ball.z) * k };
    const cars: SoccerCar[] = [];
    for (const target of s.cars) {
      if (!target) continue;
      if (target.slot === this.slot) {
        cars.push(this.local ? target : ((this.predictor.car() as SoccerCar | null) ?? target));
        continue;
      }
      const prev = this.shown.get(target.slot) ?? target;
      const d = Math.atan2(Math.sin(target.heading - prev.heading), Math.cos(target.heading - prev.heading));
      const car = { ...target, x: prev.x + (target.x - prev.x) * k, y: prev.y + (target.y - prev.y) * k, z: prev.z + (target.z - prev.z) * k, heading: prev.heading + d * k };
      this.shown.set(target.slot, car);
      cars.push(car);
    }
    this.v.update({ cars, ball: this.ball, pickups: s.pickups, me: this.slot }, delta);
    this.touch?.update(null, s.phase === 'Playing' || s.phase === 'Kickoff', delta);
    this.hud(s, cars);
  }

  private hud(s: SoccerMatch, cars: SoccerCar[]): void {
    const size = s.team_size;
    const count = s.cars.filter(Boolean).length;
    this.roomLabel.textContent = this.local ? `VS CPU | ${size}v${size}` : `ROOM ${this.code} | ${size}v${size}`;
    this.score.textContent = `BLUE  ${s.blue} : ${s.orange}  ORANGE`;
    const t = Math.ceil(s.remaining);
    this.clock.textContent = s.remaining <= 0 ? 'OT' : `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
    this.banner.textContent = s.phase === 'Kickoff' ? String(Math.max(1, Math.ceil(s.phase_time)))
      : s.phase === 'Goal' ? ANNOUNCE[s.announce] : s.phase === 'Playing' && s.remaining <= 0 ? 'OVERTIME' : '';
    this.toast.style.visibility = this.toastTime > 0 && s.phase === 'Playing' ? 'visible' : 'hidden';
    this.waitCard.style.display = s.phase === 'Waiting' ? '' : 'none';
    this.waiting.textContent = `ROOM CODE: ${this.code}\n${size}v${size}  |  ${count}/${size * 2} connected\n\nShare this code with your friends.\nThe match starts when everyone joins.`;
    this.endCard.style.display = s.phase === 'Finished' ? '' : 'none';
    this.winner.textContent = ANNOUNCE[s.announce];
    this.rematch.style.display = this.host === this.slot ? '' : 'none';
    const me = cars.find((c) => c.slot === this.slot);
    if (!me) return;
    const team = this.slot % 2 === 0;
    this.boost.textContent = `${team ? 'BLUE' : 'ORANGE'} TEAM  |  BOOST ${Math.floor(me.boost)}`;
    this.boost.style.color = `rgb(${(team ? BLUE : ORANGE).map((v) => Math.round(v * 255)).join(',')})`;
    const powers = [['NITRO', me.nitro_time], ['POWER SHOT', me.shot_time], ['MAGNET', me.magnet_time]] as const;
    const active = powers.filter(([, t]) => t > 0).map(([n, t]) => `${n} ${Math.ceil(t)}s`);
    this.powers.textContent = active.length ? active.join(' | ') : 'DRIVE OVER PICKUPS';
    const angle = Math.atan2(this.ball.y - me.y, this.ball.x - me.x);
    const rel = Math.atan2(Math.sin(angle - me.heading), Math.cos(angle - me.heading)) * (180 / Math.PI);
    const bearing = Math.abs(rel) > 110 ? 'BEHIND' : rel > 20 ? 'LEFT' : rel < -20 ? 'RIGHT' : 'AHEAD';
    const dist = Math.round(Math.hypot(this.ball.x - me.x, this.ball.y - me.y));
    this.ballInfo.textContent = this.sinceState > 2 && !this.local ? 'CONNECTION LOST - waiting for server' : `BALL ${bearing}  ${dist}m`;
  }

  private leave(): void {
    if (this.disposed) return;
    this.dispose();
    app.setStage(null);
    void nav.mapSelect();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.stop();
    this.stopKeys();
    this.predictor.free();
    if (this.local) {
      this.local.free();
      return;
    }
    net.send('Leave');
    net.inRoom = false;
  }
}
