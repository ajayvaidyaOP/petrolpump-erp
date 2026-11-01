/**
 * Safe browser primitives.
 *
 * The ERP runs inside embedded preview iframes and on plain-HTTP forecourt LANs.
 * In those environments `localStorage`/`sessionStorage` can throw SecurityError and
 * `crypto.randomUUID` can be undefined. Reading them unguarded during React render
 * crashed the whole app into the error boundary ("no button works"). These helpers
 * never throw.
 */

function storage(kind: 'localStorage' | 'sessionStorage'): Storage | null {
  try {
    const s = window[kind];
    // Probe access: some browsers return the object but throw on use.
    const probe = '__petroflow_probe__';
    s.setItem(probe, '1');
    s.removeItem(probe);
    return s;
  } catch {
    return null;
  }
}

const memory = new Map<string, string>();

export function readStored(key: string, session = false): string | null {
  const s = storage(session ? 'sessionStorage' : 'localStorage');
  if (s) {
    try {
      return s.getItem(key);
    } catch {
      /* fall through to memory */
    }
  }
  return memory.get((session ? 's:' : 'l:') + key) ?? null;
}

export function writeStored(key: string, value: string, session = false): void {
  memory.set((session ? 's:' : 'l:') + key, value);
  const s = storage(session ? 'sessionStorage' : 'localStorage');
  if (s) {
    try {
      s.setItem(key, value);
    } catch {
      /* memory fallback already set */
    }
  }
}

export function removeStored(key: string, session = false): void {
  memory.delete((session ? 's:' : 'l:') + key);
  const s = storage(session ? 'sessionStorage' : 'localStorage');
  if (s) {
    try {
      s.removeItem(key);
    } catch {
      /* ignore */
    }
  }
}

/** RFC4122-shaped unique id that works on HTTP, in iframes, and in old browsers. */
export function uuid(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') {
    try {
      return c.randomUUID();
    } catch {
      /* fall through */
    }
  }
  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === 'function') {
    c.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 16; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** matchMedia that degrades gracefully when unavailable. */
export function mediaQuery(query: string): MediaQueryList | null {
  try {
    return typeof window.matchMedia === 'function' ? window.matchMedia(query) : null;
  } catch {
    return null;
  }
}
