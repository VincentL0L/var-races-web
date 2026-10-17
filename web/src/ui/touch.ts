// Phone and tablet controls, laid out like a real car's:
//  - bottom left: big left and right arrows to steer
//  - bottom right: a brake pedal and a gas pedal side by side, like a car's footwell. Double tap
//    and hold the gas for boost (when it runs out it carries on as gas). To back up, stop and
//    keep holding the brake: after a moment it reverses.
//  - above the gas: the ITEM button, showing the item you're holding (tap to use it)
// The car drives like an automatic (see sim::car): it creeps at idle, lifting off coasts, and
// the brake stops it without rolling backwards.
import { asset } from '../assets';
import { h } from '../dom';
import { NO_CONTROLS, type Controls } from '../input';

const REVERSE_DELAY = 0.4;
const TAP_TIME = 0.3;
const DOUBLE_TAP_GAP = 0.3;

type Role = 'left' | 'right' | 'gas' | 'brake' | 'item' | 'none';

export class TouchControls {
  readonly el: HTMLElement;
  private readonly parts: Partial<Record<Role, HTMLElement>> = {};
  private readonly itemIcon = h('img.pixel', { alt: '' });
  private readonly fingers = new Map<number, Role>();
  private used = false;
  private clock = 0;
  private gasPointer = -1;
  private gasDownAt = -10;
  private lastTapEnd = -10;
  private lastWasTap = false;
  private boosting = false;
  private stoppedBraking = 0;
  private enabled = true;
  private speed = 0;
  private mana = 100;

  constructor(items: boolean, private readonly onItem: () => void) {
    const part = (role: Role, cls: string, ...children: (Node | string)[]) => {
      const el = h(`div.touch-${cls}` as 'div', null, ...children);
      this.parts[role] = el;
      return el;
    };
    this.el = h('div.touch', null,
          part('left', 'arrow.left', h('i')), part('right', 'arrow.right', h('i')),
          part('brake', 'pedal.brake', h('b'), h('span', null, 'BRAKE')),
          part('gas', 'pedal.gas', h('b'), h('span', null, 'GAS')),
          items ? part('item', 'item', this.itemIcon, h('span', null, 'ITEM')) : null);
    this.el.addEventListener('pointerdown', (e) => this.down(e));
    this.el.addEventListener('pointermove', (e) => this.move(e));
    for (const type of ['pointerup', 'pointercancel', 'lostpointercapture'] as const) {
      this.el.addEventListener(type, (e) => this.up(e as PointerEvent));
    }
  }

  /** true once the touch controls have been used (keyboard players never see them do anything) */
  active(): boolean {
    return this.used;
  }

  /** which control a point is on (fingers can roll between the arrows and between the pedals) */
  private roleAt(x: number, y: number): Role {
    const r = (role: Role) => this.parts[role]?.getBoundingClientRect();
    const item = r('item');
    if (item && Math.hypot(x - (item.left + item.width / 2), y - (item.top + item.height / 2)) < item.width * 0.62) return 'item';
    const left = r('left')!, right = r('right')!, brake = r('brake')!, gas = r('gas')!;
    if (x < innerWidth / 2) {
      if (y < left.top - 60) return 'none';
      return x < (left.right + right.left) / 2 ? 'left' : 'right';
    }
    if (y < Math.min(brake.top, gas.top) - 60) return 'none';
    return x >= (brake.right + gas.left) / 2 ? 'gas' : 'brake';
  }

  private down(e: PointerEvent): void {
    this.used = true;
    this.el.setPointerCapture(e.pointerId);
    const role = this.roleAt(e.clientX, e.clientY);
    this.fingers.set(e.pointerId, role);
    if (role === 'item') this.onItem();
    if (role === 'gas') {
      this.gasPointer = e.pointerId;
      this.boosting = this.lastWasTap && this.clock - this.lastTapEnd < DOUBLE_TAP_GAP;
      this.gasDownAt = this.clock;
    }
  }

  private move(e: PointerEvent): void {
    const role = this.fingers.get(e.pointerId);
    if (role === undefined || role === 'item') return;
    const now = this.roleAt(e.clientX, e.clientY);
    // a finger can slide from one pedal to the other, like a foot, but not onto the item
    if (now !== 'item' && now !== 'none') this.fingers.set(e.pointerId, now);
  }

  private up(e: PointerEvent): void {
    if (e.pointerId === this.gasPointer) {
      this.gasPointer = -1;
      this.lastWasTap = this.clock - this.gasDownAt < TAP_TIME && !this.boosting;
      this.lastTapEnd = this.clock;
      this.boosting = false;
    }
    this.fingers.delete(e.pointerId);
  }

  private held(role: Role): boolean {
    for (const r of this.fingers.values()) if (r === role) return true;
    return false;
  }

  /** what the fingers are doing this frame */
  controls(): Controls {
    if (!this.enabled) return { ...NO_CONTROLS, automatic: true };
    const steer = (this.held('left') ? 1 : 0) - (this.held('right') ? 1 : 0);
    const gas = this.held('gas'), brake = this.held('brake');
    // stopped and still holding the brake: after a moment it backs up
    const backwards = this.stoppedBraking > REVERSE_DELAY;
    if (backwards) return { ...NO_CONTROLS, automatic: true, gas: true, backwards: true, steer };
    // once the boost runs dry, the held pedal is just gas until the finger lifts
    if (this.boosting && this.mana <= 0.5) this.boosting = false;
    return { ...NO_CONTROLS, automatic: true, gas, brake, boost: gas && this.boosting, steer };
  }

  /** the held item (by name), whether the car can be driven, its speed and boost */
  update(item: string | null, enabled: boolean, delta = 1 / 60, speed = 0, mana = 100): void {
    this.clock += delta;
    this.enabled = enabled;
    this.speed = speed;
    this.mana = mana;
    this.el.style.display = enabled ? '' : 'none';
    const gas = this.held('gas'), brake = this.held('brake');
    this.stoppedBraking = enabled && brake && !gas && (this.speed < 8 || this.stoppedBraking > REVERSE_DELAY) ? this.stoppedBraking + delta : 0;
    for (const [role, el] of Object.entries(this.parts)) el?.classList.toggle('down', this.held(role as Role));
    this.parts.gas?.classList.toggle('boost', gas && this.boosting);
    const gasLabel = this.parts.gas?.querySelector('span');
    if (gasLabel) gasLabel.textContent = gas && this.boosting ? 'BOOST' : 'GAS';
    if (this.parts.item) {
      const src = item ? asset(`ui/items/${item}.webp`) : '';
      if (src && this.itemIcon.getAttribute('src') !== src) this.itemIcon.src = src;
      this.itemIcon.style.display = item ? '' : 'none';
      this.parts.item.classList.toggle('empty', !item);
    }
  }
}
