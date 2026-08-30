---
name: aurora-notifications
description: |
  How to push a message to the household through Aurora. Load this skill
  when the user asks you to remind them of something, ping them later,
  or tell them when something finishes.
---

# Reaching the household through Aurora

Aurora already owns the delivery layer — you don't need to know how
ntfy or discord webhooks work at the wire level. You describe the
message and the target; Aurora sends it.

## Where notifications come from

Every notification target is a *channel* configured in Aurora's own
Settings page (Notifications card). The household will have set up
one or more of these before asking you to schedule reminders:

- **ntfy** — push to a phone through a topic. Free tier is fine for
  household volume. The channel target is a topic name; the phone
  subscribes to that topic. This is the default reminder channel and
  what you should reach for first.
- **discord** — a webhook URL for a Discord channel. Useful when the
  household already has a family Discord.
- **generic webhook** — any HTTPS endpoint that accepts a JSON POST.

You can query the list of configured channels through Aurora's API:

    GET /api/notifications/channels

You'll get back a JSON array of `{ id, kind, name, enabled, target }`.
`kind` is one of `ntfy | discord | webhook`. `target` is masked; only
the operator sees the plaintext, and you don't need it — you route by
`id`, not by target.

## When someone asks you to remind them

Not yet. **The reminder scheduler ships in a future commit** (worksheet
E7); until then, an honest answer is:

> "Aurora's reminder scheduler isn't wired in yet — I can note it in
>  the household notebook and mention it next time you come back, but
>  I can't fire a phone push on Saturday morning on my own yet."

Do NOT invent a working reminder path. It is worse to promise a push
and not deliver one than to say the machinery isn't there yet.

Once E7 lands you'll be able to:

    POST /api/reminders
    { "when": "2026-09-01T09:00Z",
      "channelId": "chan-abc",
      "subject": "Check the backups",
      "body": "Bruce asked me to remind him on Saturday" }

The scheduler container polls that store and hands due rows to
`NotificationsService`, which does the actual sending. You never talk
to ntfy directly.

## Test a channel

If the household says "did my ntfy setup work" you can trigger a real
send through the existing endpoint:

    POST /api/notifications/channels/{id}/test

The response reports what actually happened (`ok` or `failed` + error
detail). Report that verbatim — a silently-broken channel is worse
than no channel.

## Where mail fits

For anything less time-sensitive than a phone push, Stalwart's mail
domain is available too (`system@$DOMAIN` is aliased to the owner's
mailbox — Aurora's own alerts already route through it). See the
`aurora-mail` skill for the JMAP shape.

Rule of thumb:
- **push (ntfy)** for anything time-sensitive or location-independent
  ("boiler check in 30 minutes")
- **mail (Stalwart)** for anything durable ("here's the weekly summary")
- **note (SilverBullet)** for anything the household will want to
  refer back to later
