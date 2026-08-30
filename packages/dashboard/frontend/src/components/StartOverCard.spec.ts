import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// The card is the only footgun surface in Settings. What matters is that:
//   - the confirm button stays disabled until the operator types RESET
//     exactly (case-exact),
//   - a wrong-word attempt never calls ResetApi,
//   - the happy path swaps to the "disconnecting" splash (i.e. the router
//     does not keep polling after acceptance),
//   - a helper-failed error is surfaced with a "nothing has been changed"
//     framing rather than silently pretending it worked.
//
// The Dialog is teleported to <body>, so its content is reached via
// document.querySelector, not the component wrapper.

vi.mock('@/api/reset', () => ({
  ResetApi: {
    start: vi.fn(),
  },
}));

import { ResetApi } from '@/api/reset';
import StartOverCard from './StartOverCard.vue';

/**
 * The Dialog focuses its first focusable inside a Teleport target on a
 * watch(props.open) callback that awaits nextTick before running — so we
 * need a couple of ticks plus a raf-shaped flush before the panel and
 * its slots are queryable via document.querySelector. Same helper the
 * UsersView.spec uses.
 */
async function settle() {
  await flushPromises();
  await new Promise((r) => setTimeout(r, 0));
  await flushPromises();
}

function inputEl(): HTMLInputElement {
  return document.querySelector<HTMLInputElement>(
    '[data-test="start-over-confirm-input"]',
  )!;
}
function confirmBtn(): HTMLButtonElement {
  return document.querySelector<HTMLButtonElement>(
    '[data-test="start-over-confirm"]',
  )!;
}
function errorEl(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-test="start-over-error"]');
}
function goodbyeEl(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-test="reset-goodbye"]');
}

async function typeConfirm(value: string): Promise<void> {
  const el = inputEl();
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
}

describe('StartOverCard', () => {
  beforeEach(() => {
    (ResetApi.start as ReturnType<typeof vi.fn>).mockReset();
  });
  afterEach(() => {
    document.body.innerHTML = '';
    document.body.style.overflow = '';
  });

  it('shows the card and no goodbye splash at rest', async () => {
    const w = mount(StartOverCard, { attachTo: document.body });
    expect(w.find('[data-card="start-over"]').exists()).toBe(true);
    expect(goodbyeEl()).toBeNull();
    w.unmount();
  });

  it('opens the modal only when the operator clicks the card button', async () => {
    const w = mount(StartOverCard, { attachTo: document.body });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
    await w.get('[data-test="start-over-open"]').trigger('click');
    await settle();
    expect(document.querySelector('[role="dialog"]')).toBeTruthy();
    w.unmount();
  });

  it('keeps the confirm button disabled until RESET is typed verbatim', async () => {
    const w = mount(StartOverCard, { attachTo: document.body });
    await w.get('[data-test="start-over-open"]').trigger('click');
    await settle();

    // Empty: disabled.
    expect(confirmBtn().disabled).toBe(true);

    // Lowercase: still disabled. Case-exact matches the backend, which
    // is case-exact too — accepting "reset" here would send a body the
    // backend would 400 and the user would blame the button.
    await typeConfirm('reset');
    expect(confirmBtn().disabled).toBe(true);

    // Padded: still disabled. Whitespace around the word is a common
    // paste artefact; the backend does not trim.
    await typeConfirm(' RESET ');
    expect(confirmBtn().disabled).toBe(true);

    // Exact: enabled.
    await typeConfirm('RESET');
    expect(confirmBtn().disabled).toBe(false);
    w.unmount();
  });

  it('does not call the API when the word is wrong', async () => {
    const w = mount(StartOverCard, { attachTo: document.body });
    await w.get('[data-test="start-over-open"]').trigger('click');
    await settle();
    await typeConfirm('please');
    confirmBtn().click();
    await settle();

    expect(ResetApi.start).not.toHaveBeenCalled();
    w.unmount();
  });

  it('sends RESET verbatim on the happy path and swaps to the goodbye splash', async () => {
    (ResetApi.start as ReturnType<typeof vi.fn>).mockResolvedValue({
      helperId: 'abc123def',
    });

    const w = mount(StartOverCard, { attachTo: document.body });
    await w.get('[data-test="start-over-open"]').trigger('click');
    await settle();
    await typeConfirm('RESET');
    confirmBtn().click();
    await settle();

    expect(ResetApi.start).toHaveBeenCalledWith('RESET');
    // Splash visible.
    const splash = goodbyeEl();
    expect(splash).toBeTruthy();
    // The splash tells the operator how to bring Aurora back — that's
    // the whole point of the splash existing.
    expect(splash!.textContent).toContain('bash bootstrap.sh install');
    w.unmount();
  });

  it('surfaces a helper failure with a "nothing changed" framing', async () => {
    (ResetApi.start as ReturnType<typeof vi.fn>).mockRejectedValue({
      response: { status: 500, data: { message: 'docker refused' } },
    });

    const w = mount(StartOverCard, { attachTo: document.body });
    await w.get('[data-test="start-over-open"]').trigger('click');
    await settle();
    await typeConfirm('RESET');
    confirmBtn().click();
    await settle();

    const err = errorEl();
    expect(err).toBeTruthy();
    // The framing has to say the box is untouched — otherwise a failed
    // reset reads as "did it half-wipe?" and the operator panics.
    expect(err!.textContent).toContain('Nothing on this box has changed');
    // And no splash on failure.
    expect(goodbyeEl()).toBeNull();
    w.unmount();
  });
});
