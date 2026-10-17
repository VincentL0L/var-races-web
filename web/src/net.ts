// The connection to the race server: one WebSocket of binary messages (see crates/proto),
// shared by every online screen. The free server sleeps when nobody plays and takes a while to
// wake up, so until we're in a room a dropped or hung connection quietly tries again.
//
// The server is wss://var-races-web.onrender.com, ws://localhost:8080 when the page itself is
// on localhost, or whatever ?server=wss://host says.
import { decode, encode, protocolVersion, type ClientMsg, type ServerMsg } from './wasm';

const PUBLIC_SERVER = 'wss://var-races-web.onrender.com';
const RETRY_DELAY = 2000;
/** a connection that hasn't opened by now is given up on and tried again */
const CONNECT_TIMEOUT = 8000;
/** after this long without getting through, the server is probably asleep */
export const WAKING_AFTER = 4;

export function serverUrl(): string {
  const param = new URLSearchParams(location.search).get('server');
  if (param) return param;
  const host = location.hostname;
  return host === 'localhost' || host === '127.0.0.1' ? `ws://${host}:8080` : PUBLIC_SERVER;
}

type Listener = (msg: ServerMsg) => void;

class Connection {
  private ws: WebSocket | null = null;
  private listeners = new Set<Listener>();
  private retry: ReturnType<typeof setTimeout> | null = null;
  private timeout: ReturnType<typeof setTimeout> | null = null;
  private trying = 0;
  private open = false;
  private wanted = false;
  /** true while in a room: a dropped connection then ends the race instead of retrying */
  inRoom = false;
  /** why the connection failed, for the menus (null while all is well) */
  error: string | null = null;

  /** starts connecting (and keeps trying) if not already */
  connect(): void {
    this.wanted = true;
    this.error = null;
    if (this.ws) return;
    if (!this.trying) this.trying = performance.now();
    const ws = new WebSocket(serverUrl());
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.timeout = setTimeout(() => {
      // stuck halfway (a hung network, a server mid-restart): start a fresh one
      if (ws.readyState !== WebSocket.OPEN) ws.close();
    }, CONNECT_TIMEOUT);
    ws.onopen = () => {
      if (this.timeout) clearTimeout(this.timeout);
      this.open = true;
      this.trying = 0;
      this.raw({ Hello: { version: protocolVersion() } });
    };
    ws.onmessage = (e) => {
      const msg = decode(new Uint8Array(e.data as ArrayBuffer)) as ServerMsg | null;
      if (msg) for (const l of [...this.listeners]) l(msg);
    };
    ws.onclose = () => {
      if (this.timeout) clearTimeout(this.timeout);
      const wasOpen = this.open;
      this.ws = null;
      this.open = false;
      if (!this.wanted) return;
      if (this.inRoom) {
        this.inRoom = false;
        this.error = 'Lost connection - the server restarted. Go back and join again';
        this.notifyClosed();
        return;
      }
      if (wasOpen) this.notifyClosed();
      // not in a race: quietly try again in a moment
      this.retry = setTimeout(() => {
        this.retry = null;
        if (this.wanted) this.connect();
      }, RETRY_DELAY);
    };
  }

  private closedListeners = new Set<() => void>();

  private notifyClosed(): void {
    for (const l of [...this.closedListeners]) l();
  }

  /** calls back when an open connection drops; returns a function that stops it */
  onClose(listener: () => void): () => void {
    this.closedListeners.add(listener);
    return () => this.closedListeners.delete(listener);
  }

  /** hangs up (back to single player, or the title screen) */
  disconnect(): void {
    this.wanted = false;
    this.inRoom = false;
    if (this.retry) clearTimeout(this.retry);
    this.retry = null;
    this.ws?.close();
    this.ws = null;
    this.open = false;
    this.trying = 0;
  }

  get connected(): boolean {
    return this.open;
  }

  /** seconds we've been trying to get through (0 when connected) */
  get secondsTrying(): number {
    return this.trying ? (performance.now() - this.trying) / 1000 : 0;
  }

  send(msg: ClientMsg): boolean {
    if (!this.open) return false;
    this.raw(msg);
    return true;
  }

  private raw(msg: ClientMsg): void {
    this.ws?.send(encode(msg));
  }

  /** calls back on every message; returns a function that stops it */
  on(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

}

export const net = new Connection();
