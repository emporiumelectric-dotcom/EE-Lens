/*
 * Thin Supabase auth client -- just enough to sign in, sign out, and keep the
 * session. Plain fetch against Supabase's own HTTP API, no SDK and no CDN, so
 * this file still works when index.html is opened straight from disk like
 * everything else here.
 *
 * This step only handles who is signed in. Actually syncing the catalogue to
 * Supabase (push on save, pull on sign-in) lives in cloud.js.
 *
 * SUPABASE_URL / SUPABASE_ANON_KEY come from config.js, loaded before this
 * file.
 */

const SESSION_KEY = 'ee-lens-session';

let session = loadSession();
const authListeners = [];

/**
 * How long a single request to Supabase gets before this gives up on it.
 * Plain fetch() has no timeout of its own: a request that never gets a
 * response -- whatever the cause -- hangs the returned promise forever,
 * which is exactly what "Signing in..." spinning with no error looked
 * like. AbortController turns that into an actual rejection so a caller's
 * try/catch (trySignIn in app.js already has one) gets the chance to run.
 */
const FETCH_TIMEOUT_MS = 15_000;

/** fetch() that rejects with a clear, user-facing message instead of hanging forever. */
async function fetchWithTimeout(url, options = {}, timeoutMs = FETCH_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error("Couldn't reach the server, try again.");
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function loadSession() {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function saveSession(next) {
  session = next;
  try {
    if (next) localStorage.setItem(SESSION_KEY, JSON.stringify(next));
    else localStorage.removeItem(SESSION_KEY);
  } catch {
    // Private browsing or a full quota: the session just won't survive a reload.
  }
  authListeners.forEach((fn) => fn(session));
}

function onAuthChange(fn) {
  authListeners.push(fn);
}

function isSignedIn() {
  return !!session?.access_token && session.expires_at * 1000 > Date.now();
}

async function signIn(email, password) {
  const response = await fetchWithTimeout(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: SUPABASE_ANON_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error_description || data.msg || 'Sign in failed. Check the email and password.');
  }
  saveSession({
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_at: data.expires_at,
    email: data.user?.email || email
  });
  return session;
}

async function signOut() {
  const token = session?.access_token;
  saveSession(null);
  if (!token) return;
  try {
    await fetchWithTimeout(`${SUPABASE_URL}/auth/v1/logout`, {
      method: 'POST',
      headers: { apikey: SUPABASE_ANON_KEY, Authorization: `Bearer ${token}` }
    });
  } catch {
    // Signed out locally regardless; the token on the server just expires on its own.
  }
}

/** Refreshes a session that is about to expire. Safe to call often. */
async function ensureFreshSession() {
  if (!session?.refresh_token) return session;
  const expiringSoon = !session.expires_at || session.expires_at * 1000 < Date.now() + 60_000;
  if (!expiringSoon) return session;
  try {
    const response = await fetchWithTimeout(`${SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, {
      method: 'POST',
      headers: { apikey: SUPABASE_ANON_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: session.refresh_token })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) { saveSession(null); return null; }
    saveSession({
      access_token: data.access_token,
      refresh_token: data.refresh_token,
      expires_at: data.expires_at,
      email: data.user?.email || session.email
    });
  } catch {
    // Offline: keep whatever session we have rather than sign the user out.
  }
  return session;
}
