# Custom stacks — design spec

Aurora ships a fixed, curated catalogue. That is the product, not a
limitation: a vetted set of packages that are known to work together is
the whole reason the dependency graph and the port map can be trusted.

But eighteen packages is not everything anyone will ever want, and the
current answer to "I'd like to run Calibre-Web" is *edit the repo*. That
is a bad answer. It puts a first-time user into a git checkout, and it
means their change is the thing that breaks the next `git pull`.

So: a way to run your own compose file, clearly marked as your own
problem, that does not pretend to be part of the catalogue.

## 0. The line this must not cross

The guided path stays the default and the only *recommended* one. This
flow is reached deliberately, warns plainly, and never appears alongside
catalogue apps as though it were one of them. Custom stacks live under
`/apps/custom` and are listed separately everywhere they appear.

The failure mode to design against is not "user writes bad YAML". It is
"user pastes something off the internet that quietly takes the box
down". Hence the validation below is about *consequences*, not syntax.

## 1. Where it lives

`/apps/custom`, reached from the Apps section nav. Gated on
`SystemCapabilities.customStacks`.

## 2. The flow

1. **Paste or upload** a compose file.
2. **Validate.** Aurora reports what it found and what it thinks of it,
   split into things that block and things that merely warrant knowing.
3. **Name it** and save. Nothing has run yet.
4. **Deploy.** Streams through `JobLogPanel`, same as everything else.

Saving and deploying are separate steps on purpose. A stack that exists
but has never run is a useful state: it is how you keep a draft while you
work out what is wrong with it.

## 3. Validation

Two tiers, and the distinction matters more than the individual checks.

**Blocking.** The stack will not deploy until these are fixed:

| Check | Why |
|---|---|
| does not parse | nothing else can be trusted |
| no services | nothing to run |
| port already taken | it will fail to start and may take the incumbent with it |
| name collides with a package | Aurora's own containers are not negotiable |
| binds a privileged port under 1024 | almost always a mistake in a homelab compose file |

**Advisory.** Deployable, with the consequence stated:

| Check | What we say |
|---|---|
| `:latest` or no tag | it will change under you on the next pull, and you will not know why it broke |
| `privileged: true` | this container can do anything the host can |
| mounts `docker.sock` | this container can start other containers as root; it is root |
| `network_mode: host` | it ignores Docker's network isolation entirely |
| no `restart:` policy | it will not come back after a reboot |
| no memory limit | one runaway process takes the whole box down, and this one is not capped |

The advisory list is the point of the whole screen. Anyone pasting a
compose file from a forum post is, statistically, pasting all six.

## 4. What Aurora will not do

- Merge a custom stack into the dependency graph. It cannot be a
  `depends_on` target for a catalogue package, and it never appears in
  the onboarding plan.
- Manage its data. No backup paths are inferred; the Backup page will
  not claim to protect it.
- Fix its compose file. Warnings are stated once, plainly, and the
  operator decides.
- Publish an mDNS alias or write a Caddy vhost automatically. Use the
  Addresses card for that, deliberately, once the stack is running.

## 5. API surface

| Method | Path | Purpose |
|---|---|---|
| GET | `/custom/stacks` | list |
| POST | `/custom/stacks/validate` | dry run, returns the two-tier report |
| POST | `/custom/stacks` | save (does not run) |
| PUT | `/custom/stacks/{id}` | edit |
| POST | `/custom/stacks/{id}/deploy` | run it → `JobRef` |
| POST | `/custom/stacks/{id}/stop` | stop it → `JobRef` |
| DELETE | `/custom/stacks/{id}` | remove (stops first) |

## 6. Out of scope

Editing catalogue packages through this route, importing from a URL,
compose file version conversion, and any attempt to guess a health probe.
A custom stack shows Docker's own container state and nothing more,
because anything else would be Aurora inventing confidence it has not
earned.
