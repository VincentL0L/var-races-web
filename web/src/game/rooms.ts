// What we know about our online room and account, kept up to date from the server's messages
// whichever screen is showing (a message can arrive while one screen hands over to the next).
import { net } from '../net';
import type { Lobby, Profile, RankResult, TopEntry, Visibility } from '../wasm';

export interface Room {
  code: string;
  visibility: Visibility;
  slot: number;
  mapId: string;
}

export const online = {
  room: null as Room | null,
  lobby: null as Lobby | null,
  /** ranked: players waiting and seconds until the start */
  queue: null as { players: number; seconds: number } | null,
  profile: null as Profile | null,
  rankResult: null as RankResult | null,
  top: null as TopEntry[] | null,
  /** the last error the server sent */
  error: null as string | null,
};

net.on((msg) => {
  if (msg === 'AuthFailed') {
    online.profile = null;
    return;
  }
  if (typeof msg !== 'object') return;
  if ('Joined' in msg) {
    const j = msg.Joined;
    online.room = { code: j.code, visibility: j.visibility, slot: j.slot, mapId: j.map };
    online.lobby = null;
    online.queue = null;
    online.rankResult = null;
    online.error = null;
    net.inRoom = true;
  } else if ('Lobby' in msg) online.lobby = msg.Lobby;
  else if ('Queue' in msg) online.queue = msg.Queue;
  else if ('Profile' in msg) online.profile = msg.Profile;
  else if ('RankResult' in msg) online.rankResult = msg.RankResult;
  else if ('Top' in msg) online.top = msg.Top;
  else if ('Error' in msg) online.error = msg.Error;
});

/** leaves the room we're in (the connection stays up for the menus) */
export function leaveRoom(): void {
  if (online.room) net.send('Leave');
  online.room = null;
  online.lobby = null;
  net.inRoom = false;
}
