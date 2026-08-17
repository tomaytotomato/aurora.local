import { describe, it, expect } from 'vitest';
import {
  httpStatusFromError,
  humanCopyForStatus,
  humanCopyForError,
} from './http-error-copy';

describe('http-error-copy helpers', () => {
  describe('httpStatusFromError', () => {
    it('pulls status from a shaped axios error', () => {
      expect(httpStatusFromError({ response: { status: 404 } })).toBe(404);
      expect(httpStatusFromError({ response: { status: 401 } })).toBe(401);
    });

    it('returns undefined when the shape is missing', () => {
      expect(httpStatusFromError(null)).toBeUndefined();
      expect(httpStatusFromError(undefined)).toBeUndefined();
      expect(httpStatusFromError({})).toBeUndefined();
      expect(httpStatusFromError('nope')).toBeUndefined();
      expect(httpStatusFromError(new Error('generic'))).toBeUndefined();
    });

    it('accepts unshaped nested errors without crashing', () => {
      expect(httpStatusFromError({ response: {} })).toBeUndefined();
      expect(httpStatusFromError({ response: null })).toBeUndefined();
    });
  });

  describe('humanCopyForStatus', () => {
    const CTX = {
      subject: 'container logs',
      action: 'load',
    };

    it('401 and 403 map to session-expired copy', () => {
      expect(humanCopyForStatus(401, CTX)).toBe(
        'You need to sign in again to load container logs.',
      );
      expect(humanCopyForStatus(403, CTX)).toBe(
        'You need to sign in again to load container logs.',
      );
    });

    it('400 uses ctx.badRequest when provided, else falls back', () => {
      expect(humanCopyForStatus(400, CTX)).toBe(
        "Aurora couldn't understand that request.",
      );
      expect(humanCopyForStatus(400, { ...CTX, badRequest: 'Container name is malformed.' }))
        .toBe('Container name is malformed.');
    });

    it('404 uses ctx.notFound when provided, else falls back to subject-noun', () => {
      expect(humanCopyForStatus(404, CTX)).toBe(
        "Aurora can't find container logs on this box any more.",
      );
      expect(humanCopyForStatus(404, { ...CTX, notFound: 'That container is gone.' }))
        .toBe('That container is gone.');
    });

    it('404 default reads naturally for subjects that are already a noun phrase', () => {
      expect(humanCopyForStatus(404, { subject: "this app's networking", action: 'load' })).toBe(
        "Aurora can't find this app's networking on this box any more.",
      );
      expect(humanCopyForStatus(404, { subject: "this app's configuration", action: 'load' })).toBe(
        "Aurora can't find this app's configuration on this box any more.",
      );
    });

    it('unrecognised statuses (500, 502, undefined) yield the generic branch', () => {
      const expected = "Aurora couldn't load container logs just now.";
      expect(humanCopyForStatus(500, CTX)).toBe(expected);
      expect(humanCopyForStatus(502, CTX)).toBe(expected);
      expect(humanCopyForStatus(undefined, CTX)).toBe(expected);
      expect(humanCopyForStatus(0, CTX)).toBe(expected);
    });

    it('copy never contains shell substrings', () => {
      // §5 UX contract — no axios / sudo / bash strings in the DOM.
      const all = [
        humanCopyForStatus(401, CTX),
        humanCopyForStatus(400, CTX),
        humanCopyForStatus(404, CTX),
        humanCopyForStatus(500, CTX),
      ].join(' ').toLowerCase();
      expect(all).not.toMatch(/sudo /);
      expect(all).not.toMatch(/bash /);
      expect(all).not.toMatch(/axios/);
      expect(all).not.toMatch(/\.\/scripts\//);
    });
  });

  describe('humanCopyForError', () => {
    it('chains status extraction + copy mapping', () => {
      const err = { response: { status: 401 } };
      expect(humanCopyForError(err, { subject: 'the audit log', action: 'see' })).toBe(
        'You need to sign in again to see the audit log.',
      );
    });

    it('falls back to generic on unshaped errors', () => {
      expect(humanCopyForError(new Error('network'), { subject: 'metrics', action: 'load' }))
        .toBe("Aurora couldn't load metrics just now.");
    });
  });
});
