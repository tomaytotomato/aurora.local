# Migrating from home.local to aurora.local

<!--
  TODO(bruce): this document describes the rename executed on branch
  `rename/aurora` (commit c899434 + follow-up) as recorded in the session
  memory. The worktree HEAD (5d0b081) predates the v0.2 dashboard refactor,
  so specific command paths under `packages/dashboard/` reflect what was
  intended, not what is committed on this branch. Verify against the
  dirty working tree on `rename/aurora` before publishing.
-->

The `home.local` → `aurora.local` rename touches the compose project name,
the docker network, the primary env var, and every Caddy vhost. It is a
destructive rename: existing containers must come down and named volumes
must be re-attached to services under new project labels. Take snapshots
before you touch anything.

- Compose project name: `home` → `aurora`
- Docker network: `home_net` → `aurora_net`
- Env var: `HOME_DOMAIN` → `DOMAIN`
- Vhosts: `*.home.local` → `*.$DOMAIN` (default `aurora.local`)
- Dashboard package: no admin vhost → `admin.$DOMAIN`
- On-disk repo: `~/home.local` → `~/aurora.local` (convention only; not enforced)

If you keep the domain name `home.local`, you still need to do the rename;
the code no longer knows the string `HOME_DOMAIN` and the compose project
label has changed. You will simply set `DOMAIN=home.local` in
`packages/core/.env` after the migration.

## Before you start

Do these in order. Everything after step 3 is irreversible without the
snapshot from step 2.

1. Note your current apex domain and enabled packages:

   ```
   grep -E '^(hostname|domain):' .state.yml
   grep -A1 '^enabled:' .state.yml
   ```

2. Snapshot the parts that carry state. Named volumes are the only
   things that cost real time to recover; everything else is derivable
   from git.

   ```
   mkdir -p ~/backups/pre-aurora
   cd ~/home.local

   # config files
   cp .state.yml ~/backups/pre-aurora/state.yml.bak
   tar czf ~/backups/pre-aurora/envs.tar.gz packages/*/.env 2>/dev/null

   # named volumes (largest first; skip if you have a Kopia backup already)
   docker volume ls --format '{{.Name}}' \
     | grep -E '^home[_-]' \
     | while read v; do
         docker run --rm -v "$v":/src -v ~/backups/pre-aurora:/dst alpine \
           tar czf "/dst/vol-$v.tgz" -C /src . ;
       done
   ```

3. Confirm the snapshot is readable and non-empty:

   ```
   ls -lh ~/backups/pre-aurora/
   tar tzf ~/backups/pre-aurora/envs.tar.gz | head
   ```

4. Note down any custom Caddy snippets you have edited under
   `packages/*/caddy.snippet`. These are content-tracked in git but the
   rename does not preserve local edits that were never committed:

   ```
   git status packages/*/caddy.snippet
   ```

## Bring services down cleanly

Down the *old* project name explicitly. If you forget the `-p home` the
compose CLI will target no project and stop nothing:

```
cd ~/home.local
docker compose -p home down --remove-orphans
```

Confirm nothing from the old project is still up:

```
docker ps --filter label=com.docker.compose.project=home
# expected: empty
```

Do **not** run `docker volume prune` at this point. The named volumes
still carry the `home_` prefix and you'll need them shortly.

## Rename on disk

```
mv ~/home.local ~/aurora.local
cd ~/aurora.local
```

Check out the `rename/aurora` branch (or whichever branch carries the
rebrand, once merged to `productionize`):

```
git fetch origin
git checkout rename/aurora
git pull --ff-only
```

## Update env references

The rename replaces `HOME_DOMAIN` with `DOMAIN` in every checked-in
file. Your local `.env` files are gitignored and will still reference
the old name.

```
# see what's affected first
grep -l HOME_DOMAIN packages/*/.env

# rewrite in place
sed -i.bak 's/\bHOME_DOMAIN\b/DOMAIN/g' packages/*/.env

# spot-check
grep -E '^(DOMAIN|HOME_DOMAIN)=' packages/*/.env
```

`packages/core/.env` is a special case: from v0.2 forward it is
managed by Aurora's onboarding wizard (the `POST /admin` / `PATCH
/domain` flow). If you have hand-edited values in it that aren't just
`DOMAIN=`, note them; the wizard will rewrite the file when you set
your domain on step 3.

Update `.state.yml` to record the new domain if you want a custom apex:

```
sed -i.bak 's/^domain: .*/domain: aurora.local/' .state.yml
```

## Migrate the named volumes

Compose ties volumes to the project name. Renaming the project makes
the old volumes orphaned. Two options:

**Option A: rename the volumes** (fast, reuses data in place). Docker
does not support volume rename natively, so we clone into new names
and drop the old ones:

```
for old in $(docker volume ls --format '{{.Name}}' | grep -E '^home[_-]'); do
  new="aurora${old#home}"   # home_foo -> aurora_foo, home-foo -> aurora-foo
  docker volume create "$new" >/dev/null
  docker run --rm \
    -v "$old":/from -v "$new":/to alpine \
    sh -c 'cd /from && tar cf - . | (cd /to && tar xf -)'
done
```

Verify the new volumes look right, then remove the old ones:

```
docker volume ls | grep aurora
# … looks good?
docker volume ls --format '{{.Name}}' | grep -E '^home[_-]' \
  | xargs -r docker volume rm
```

**Option B: restore from snapshot** into fresh volumes:

```
for f in ~/backups/pre-aurora/vol-home*.tgz; do
  old=$(basename "$f" .tgz | sed 's/^vol-//')
  new="aurora${old#home}"
  docker volume create "$new" >/dev/null
  docker run --rm -v "$new":/to -v ~/backups/pre-aurora:/from alpine \
    sh -c "cd /to && tar xzf /from/$(basename "$f")"
done
```

## Rebring up

```
./scripts/up.sh
```

This starts under the new compose project (`aurora`) and creates
`aurora_net`. Confirm:

```
docker ps --filter label=com.docker.compose.project=aurora
docker network ls | grep aurora_net
```

The Aurora dashboard is now at `http://admin.$DOMAIN`. On first hit
you land in the onboarding wizard; walk through it, keeping the same
username you used before if you had an admin account under the old
scheme (v0.1 dashboards did not — you're creating one for the first
time).

## DNS and AdGuard rewrites

If you kept `DOMAIN=aurora.local`, AdGuard picks up `*.aurora.local`
rewrites automatically from `packages/privacy/adguard/rewrites.yml`.

If you chose a custom apex (e.g. `home.example.com`), update the
rewrite rules:

```
# in the AdGuard web UI (adguard.$DOMAIN, port 3000):
#   Filters -> DNS rewrites
#   add a wildcard: *.your.domain  ->  <LAN IP of this box>
#
# or seed it from the CLI:
./scripts/seed-adguard.sh
```

If your router (not AdGuard) does DNS for the LAN, add a wildcard `A`
record `*.your.domain` → `<box LAN IP>`. UniFi, pfSense, OPNsense, and
OpenWRT support this; most consumer routers do not and you'll be
adding one A record per service by hand.

## Rollback

If something goes wrong before you have new services up, the fastest
route back is:

```
# stop the new project
cd ~/aurora.local
docker compose -p aurora down --remove-orphans

# rename the tree back
mv ~/aurora.local ~/home.local
cd ~/home.local

# check out the pre-rename branch
git checkout productionize      # or whichever branch you were on

# restore the pre-migration state file + envs
cp ~/backups/pre-aurora/state.yml.bak .state.yml
tar xzf ~/backups/pre-aurora/envs.tar.gz

# restore volumes: reverse the migration script, cloning aurora_* back to home_*
# (only needed if you already ran Option A above)
for new in $(docker volume ls --format '{{.Name}}' | grep -E '^aurora[_-]'); do
  old="home${new#aurora}"
  docker volume create "$old" >/dev/null
  docker run --rm -v "$new":/from -v "$old":/to alpine \
    sh -c 'cd /from && tar cf - . | (cd /to && tar xf -)'
done

# bring the old project back up
docker compose -p home up -d
```

If you already onboarded through Aurora's wizard and it wrote a new
`packages/core/.env`, the rollback restores your pre-migration copy —
verify with `grep DOMAIN packages/core/.env` before starting anything.

## Post-migration checklist

- `docker ps --filter label=com.docker.compose.project=aurora` shows
  the expected container set
- `./scripts/health.sh` reports all vhosts responding
- `./scripts/get-caddy-root-cert.sh` still produces the same root CA
  (unchanged by the rename)
- Client devices reach `admin.$DOMAIN` and land on the dashboard
- Onboarding shows `complete: true` at `GET /api/onboarding/status`

<!--
  TODO(bruce): sanity-check the volume-rename commands. Some named
  volumes on your box might already be prefixed by a mix of `_` and `-`
  depending on compose version; the `home[_-]` regex covers both but
  hasn't been tested against your exact volume set. Consider a dry-run:
    docker volume ls --format '{{.Name}}' | grep -E '^home[_-]'
  before running the loop.
-->
