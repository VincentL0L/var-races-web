// Player accounts for ranked races, with Firebase Authentication: Google, or an email and
// password with a username. The Firebase code is only downloaded when the online menu opens.
// Whenever the sign-in token changes, the server is shown the new one (net.setToken).
import type { Auth, User } from 'firebase/auth';
import { h } from './dom';
import { net } from './net';

const CONFIG = {
  apiKey: 'AIzaSyBlIzA_86YzqDi3_G9BbUAVzh0ss0MIYaA',
  authDomain: 'var-races.firebaseapp.com',
  projectId: 'var-races',
  appId: '1:222306009427:web:132612da1788a9ad8a21a6',
  messagingSenderId: '222306009427',
};

type FirebaseAuth = typeof import('firebase/auth');

export const account = {
  /** Firebase is loaded and sign-in can start */
  ready: false,
  uid: null as string | null,
  name: null as string | null,
  /** why the last sign-in failed */
  error: null as string | null,
};

let loading: Promise<void> | null = null;
let fb: FirebaseAuth;
let auth: Auth;

/** loads Firebase (once) and picks up a sign-in from an earlier visit */
export function startAccounts(): Promise<void> {
  loading ??= (async () => {
    const [{ initializeApp }, lib] = await Promise.all([import('firebase/app'), import('firebase/auth')]);
    fb = lib;
    auth = fb.initializeAuth(initializeApp(CONFIG), {
      persistence: [fb.indexedDBLocalPersistence, fb.browserLocalPersistence],
      popupRedirectResolver: fb.browserPopupRedirectResolver,
    });
    fb.onIdTokenChanged(auth, (user) => void signedIn(user));
    // the token lasts an hour: keep the server's copy fresh
    setInterval(() => void (auth.currentUser && signedIn(auth.currentUser)), 10 * 60 * 1000);
    account.ready = true;
  })().catch((e) => {
    console.warn('accounts', e);
    account.error = 'unavailable';
  });
  return loading;
}

async function signedIn(user: User | null): Promise<void> {
  account.uid = user?.uid ?? null;
  account.name = user ? user.displayName || 'Racer' : null;
  net.setToken(user ? await user.getIdToken() : null);
}

/** Google's sign-in window (call it straight from a click, or the browser blocks it) */
export function signInWithGoogle(): void {
  if (!account.ready) return;
  account.error = null;
  const provider = new fb.GoogleAuthProvider();
  fb.signInWithPopup(auth, provider).catch((e: { code?: string }) => {
    if (e.code === 'auth/popup-blocked' || e.code === 'auth/operation-not-supported-in-this-environment') {
      void fb.signInWithRedirect(auth, provider);
    } else if (e.code !== 'auth/popup-closed-by-user' && e.code !== 'auth/cancelled-popup-request') {
      account.error = e.code ?? String(e);
    }
  });
}

export function signOut(): void {
  if (account.ready) void fb.signOut(auth);
}

const MESSAGES: Record<string, string> = {
  'auth/invalid-credential': 'Wrong email or password',
  'auth/wrong-password': 'Wrong email or password',
  'auth/user-not-found': 'No account with that email',
  'auth/email-already-in-use': 'That email already has an account. Sign in instead',
  'auth/weak-password': 'Password needs at least 6 characters',
  'auth/invalid-email': "That email doesn't look right",
  'auth/too-many-requests': 'Too many tries. Wait a minute and try again',
  'auth/operation-not-allowed': "Email accounts aren't switched on yet",
  'auth/network-request-failed': 'No connection',
};

const nice = (e: unknown) => {
  const code = (e as { code?: string })?.code ?? '';
  return MESSAGES[code] ?? `Something went wrong (${code})`;
};

/**
 * The email form: sign in, or create an account with a username. A real HTML form, so
 * password managers, autofill and phone keyboards all work.
 */
export function openEmailForm(): void {
  if (!account.ready) return;
  let mode: 'in' | 'up' = 'in';
  const username = h('input.field', { name: 'username', maxLength: 14, autocomplete: 'username' });
  const email = h('input.field', { name: 'email', type: 'email', autocomplete: 'email', required: true });
  const password = h('input.field', { name: 'password', type: 'password', minLength: 6, required: true, autocomplete: 'current-password' });
  const msg = h('div.error');
  const go = h('button.btn.wide', { type: 'submit' }, 'Sign In');
  const userRow = h('div', null, h('div.kicker', null, 'USERNAME (SHOWN ON THE LEADERBOARD)'), username);
  const tabIn = h('button.btn.small', { type: 'button', onclick: () => setMode('in') }, 'Sign In');
  const tabUp = h('button.btn.small', { type: 'button', onclick: () => setMode('up') }, 'Create Account');
  const close = () => box.remove();
  const setMode = (m: 'in' | 'up') => {
    mode = m;
    userRow.style.display = m === 'up' ? '' : 'none';
    go.textContent = m === 'up' ? 'Create Account' : 'Sign In';
    password.autocomplete = m === 'up' ? 'new-password' : 'current-password';
    tabIn.classList.toggle('on', m === 'in');
    tabUp.classList.toggle('on', m === 'up');
    msg.textContent = '';
  };
  const form = h('form.panel.col', {
    style: { width: 'min(400px, 92vw)', padding: '10px 18px 16px' },
    onsubmit: (ev: Event) => {
      ev.preventDefault();
      msg.textContent = '...';
      const fail = (e: unknown) => (msg.textContent = nice(e));
      if (mode === 'in') {
        fb.signInWithEmailAndPassword(auth, email.value, password.value).then(close, fail);
        return;
      }
      const name = username.value.trim();
      if (name.length < 3) {
        msg.textContent = 'Pick a username (3 or more letters)';
        return;
      }
      fb.createUserWithEmailAndPassword(auth, email.value, password.value)
        .then(async (c) => {
          await fb.updateProfile(c.user, { displayName: name });
          await c.user.getIdToken(true);
          await signedIn(c.user);
          close();
        })
        .catch(fail);
    },
  },
    h('div.title.small.plain', { style: { textAlign: 'left' } }, 'ACCOUNT'),
    h('div.row', { style: { justifyContent: 'stretch' } }, tabIn, tabUp),
    userRow,
    h('div.kicker', null, 'EMAIL'), email,
    h('div.kicker', null, 'PASSWORD'), password,
    go, msg,
    h('div.row', { style: { justifyContent: 'space-between' } },
      h('a.kicker', { style: { cursor: 'pointer' }, onclick: () => {
        if (!email.value) {
          msg.textContent = 'Type your email first';
          return;
        }
        fb.sendPasswordResetEmail(auth, email.value).then(() => (msg.textContent = 'Check your email for a reset link'), (e) => (msg.textContent = nice(e)));
      } }, 'Forgot password?'),
      h('a.kicker', { style: { cursor: 'pointer' }, onclick: close }, 'Close'),
    ),
  );
  const box = h('div.screen', { style: { position: 'fixed', zIndex: '20', background: 'rgba(10,6,4,0.6)' } }, form);
  document.getElementById('ui')!.append(box);
  setMode('in');
  setTimeout(() => email.focus(), 50);
}
