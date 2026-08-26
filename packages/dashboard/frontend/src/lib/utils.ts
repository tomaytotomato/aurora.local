import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

// shadcn-style class merge helper.
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

// Deterministic strong-password generator.
// 20 chars, ~118 bits entropy. Excludes ambiguous glyphs (I l 1 O 0).
const ALPHABET =
  'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%^&*-_=+';
export function generatePassword(len = 20): string {
  const bytes = new Uint32Array(len);
  crypto.getRandomValues(bytes);
  let out = '';
  for (let i = 0; i < len; i++) {
    out += ALPHABET[bytes[i] % ALPHABET.length];
  }
  return out;
}

export async function copyToClipboard(text: string): Promise<boolean> {
  // Preferred path: Clipboard API. Only available in secure contexts
  // (HTTPS or localhost). Aurora's onboarding runs on plain HTTP at
  // admin.aurora.local before TLS is set up, so we must fall back.
  if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // fall through to legacy path
    }
  }

  // Legacy fallback: hidden textarea + execCommand('copy'). Deprecated but
  // still works in every current browser and does not require a secure context.
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '0';
    ta.style.left = '0';
    ta.style.opacity = '0';
    ta.style.pointerEvents = 'none';
    document.body.appendChild(ta);
    const prevActive = document.activeElement as HTMLElement | null;
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, text.length);
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    prevActive?.focus?.();
    return ok;
  } catch {
    return false;
  }
}

// Format bytes to human-readable. Renders an em-dash for missing/NaN inputs
// so the dashboard never emits "NaN KB".
export function humanBytes(n: number | null | undefined): string {
  if (n === null || n === undefined || !Number.isFinite(n) || n < 0) return '—';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(1)} ${units[i]}`;
}

// Human-friendly uptime. Renders em-dash for missing/NaN inputs.
// <60s -> "just booted", <1h -> "12m", <48h -> "6h 42m", ≥2d -> "12d 5h".
// "uptime NaNh" is banned; the empty state is "—".
export function humanUptime(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || !Number.isFinite(sec) || sec < 0) return '—';
  const s = Math.floor(sec);
  if (s < 60) return 'just booted';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 48) {
    const mm = m - h * 60;
    return mm > 0 ? `${h}h ${mm}m` : `${h}h`;
  }
  const d = Math.floor(h / 24);
  const hh = h - d * 24;
  return hh > 0 ? `${d}d ${hh}h` : `${d}d`;
}

// Integer percentage of a/b, clamped [0, 100]. Returns null for missing
// inputs so the caller can render an em-dash instead of `NaN%`.
export function safePercent(
  used: number | null | undefined,
  total: number | null | undefined,
): number | null {
  if (used === null || used === undefined || total === null || total === undefined) return null;
  if (!Number.isFinite(used) || !Number.isFinite(total) || total <= 0) return null;
  const pct = Math.round((used / total) * 100);
  return Math.max(0, Math.min(100, pct));
}

// Relative time — small, always past.
export function relTime(iso: string | Date): string {
  const t = typeof iso === 'string' ? new Date(iso).getTime() : iso.getTime();
  const s = Math.floor((Date.now() - t) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}
