# The VPN setup screen was a one-way door — progress log

## Reported

"If no VPN exists and I go on the VPN page, it shows a dialogue to setup
a VPN, however if I exit and navigate to another page without setting up
a VPN, it then shows a VPN that is configured."

## What was actually true

Read off the testbed's database rather than inferred. A `vpn_config` row
existed, created at 11:53:07 BST with a **real keypair**, which narrows
the culprit to `POST /vpn/config/init` — the lazy `PUT` path inserts null
keys. Zero peers, `endpoint_host` empty, `updated_at` identical to
`created_at`. The container log agreed: one
`vpn: server configuration generated` line at 11:53:07.769, and from
11:53:10 a `wg show wg0 dump` failure every five seconds thereafter
(the page's poll, now that a config existed for `status()` to look past).

`initConfig` has exactly one caller in the frontend: the "Generate
configuration" button. No automatic path exists, so the trigger was a
click — on the only control on that card, sitting centre-stage where a
dismiss would be, with no confirmation.

## The actual defect

Not the click. **There was no way back.** No `DELETE /vpn/config` in the
controller and none in `openapi.yaml` either; the only DELETE anywhere in
the VPN surface was for individual peers. One click permanently converted
the box to "configured" and the setup screen could never return short of
editing SQLite.

Three things fell out alongside it:

- **The page called a stub "configured".** Empty endpoint, no `wg0`, no
  peers — rendered as three tabs of a working VPN. Same class of
  dishonesty as a box that believes it is backed up.
- **Nothing was audited.** Generating a server keypair wrote no
  `audit_event`, while publishing an mDNS alias has all along. That is
  why answering "when did this box get a VPN?" needed a copy of the
  database and a query.
- **The ready state was a bare `v-else`**, so any state the page had not
  anticipated rendered as configured.

## Fixed

`DELETE /vpn/config` (spec, controller, service, repos) → 204, or 404
when there is nothing to remove. Peers are deleted with it: each one's
issued `.conf` authenticates against the key being discarded, so keeping
the rows would leave a list of devices that look valid and can connect to
nothing. Behind a confirm dialog that says exactly that.

The page now lists what a configuration still needs — endpoint, keypair,
a peer, the interface up — instead of implying it works. `v-else` became
`v-else-if="pageState === 'ready'"`. Init, rotate and remove all write
audit events; remove records the peer count, since that is the part the
operator cannot get back.

## Tests

9 new backend integration tests (33 in `VpnControllerIntegrationTest`),
7 new frontend tests in a new `VpnView.spec.ts`. Written before the fix:
the backend nine failed 405/no-audit, the frontend four failed on absent
controls.

Two include the negative case explicitly — removing when nothing is
configured is a 404 rather than a silent success, and it records no audit
event — plus one proving the door swings both ways (`init` works again
afterwards, with a *different* keypair).

Two of my own test assumptions were wrong and the tests, not the code,
were corrected: "Regenerate server key" lives in the Overview tab's
Server card, not on Advanced (which is OpenVPN), and `Dialog` teleports
to `<body>` so its buttons are not inside the mounted wrapper.

Backend 799/799, frontend 530/530, typecheck clean,
`scripts/check-openapi.py` reports 0 problems.

## Worth knowing

The stray config is still on the testbed VM. It was left deliberately:
once the rebuilt image is on there it is the obvious first thing to click
Remove on, which exercises the fix against a real box rather than a
fixture.
