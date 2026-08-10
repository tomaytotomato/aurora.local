# Backup page — design spec

Aurora has shipped a backup package (Kopia) since early on. Nothing about
it appears in the dashboard. To find out whether your data is safe you
log into a second web UI on port 51515, which means in practice nobody
looks, and a backup nobody looks at is a backup that quietly stopped
working in March.

This page is not a second Kopia UI. Kopia keeps the parts it is good at
(repository management, the snapshot browser, policy editing). Aurora
answers one question on its own: **is my data safe, and how do I get it
back?**

## 0. What this page is for

Three jobs, in order of how often they matter:

1. **Reassurance.** Last snapshot succeeded, when, how big, what is next.
2. **Alarm.** Nothing has succeeded in a fortnight, or last night failed.
3. **Restore.** Something is gone and you want it back, without learning
   Kopia's CLI first.

Everything else (repository creation, retention tuning beyond the basics,
per-directory exclude rules) stays in Kopia's own UI, and this page links
out to it rather than reimplementing it.

## 1. Where it lives

`/backup`, in the main sidebar between Apps and VPN. Gated on
`SystemCapabilities.backup`, which is false until the backend can talk to
Kopia's server API. While false the nav entry is hidden, in line with the
`securityScanner` precedent: a page that cannot be honest should not be
reachable.

The Overview page also carries a small backup-health tile, because point
2 above should not require anyone to navigate anywhere.

## 2. Page states

| State | When | What it shows |
|---|---|---|
| `not-installed` | the `backup` package is not enabled | what Kopia is, what it would protect, an Add button |
| `not-configured` | package enabled, repository never initialised | the first-run explanation and a link into Kopia to create the repository |
| `unreachable` | repository configured but Aurora cannot reach it | honest error, last known good snapshot, retry |
| `healthy` | last run succeeded within the staleness window | the reassurance view |
| `stale` | no successful snapshot within `stalenessWarnDays` | same layout, warning tone, days since |
| `failed` | the most recent run failed | failure banner above everything, with the job log |

`stale` and `failed` are separate on purpose. A run that fails loudly last
night is a different problem from a schedule that silently stopped three
weeks ago, and the second one is the one that actually loses data.

## 3. Layout

### 3.1 Header

Standard `on-photo` header: eyebrow "Data", `h1` "Backup", one sentence.
A `Back up now` button sits top right; it creates a job and streams it
through `JobLogPanel`, the same component the app-update flow uses.

### 3.2 Tabs

`Overview` · `What's protected` · `Restore` · `Schedule`, using the
shared `Tabs` component with `on-photo-tabs`.

**Overview.** Four facts in a row of cards: last run (state, when, how
long), repository (kind, location, encrypted yes/no), size (total vs
unique after dedup, which is the number that surprises people), and next
run. Below that, the last five runs as a table.

**What's protected.** One row per source path, with the aurora package it
belongs to when known, its size, file count, and last snapshot. This is
where per-source **before-snapshot actions** appear: a row backing up
Immich shows "dumps immich-postgres first", because a Postgres data
directory copied while the server is running is not a backup, it is a
corrupted file with a timestamp. A source with a database and *no*
declared action gets a warning row saying so. That warning is the whole
reason this tab exists.

A source can be toggled off. Nothing else about it is editable here;
adding paths is Kopia's job.

**Restore.** Pick a source, pick a snapshot from its history, confirm.
The confirm dialog is explicit about what will be overwritten and uses
the danger button variant, same as removing an app. Restoring streams
through `JobLogPanel`.

Restore is deliberately the plainest possible flow: whole source, whole
snapshot, back to its original path. Partial restore (single file, or
restore-to-elsewhere) is out of scope and links into Kopia, which already
does it well.

**Schedule.** The cron expression rendered in English ("Every day at
02:00"), the three retention counts, and the staleness threshold that
drives the warning on Overview. Editable, saved as one form.

## 4. Honesty rules

- Never show "protected" for a source whose last snapshot failed.
- Never show a size for a repository Aurora could not reach; show the
  last known figure with its timestamp, or an em dash.
- "Encrypted" reflects what Kopia reports, not what the package README
  hopes.
- A source with a database and no before-action is called out, not
  quietly listed as fine.

## 5. API surface

All under `/api`. Schemas in `openapi.yaml`; types in
`src/api/backup.ts`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/backup/status` | the Overview facts, and what drives the Home tile |
| GET | `/backup/sources` | one row per protected path |
| PATCH | `/backup/sources/{id}` | toggle a source on or off |
| GET | `/backup/snapshots` | snapshot history, optionally `?sourceId=` |
| POST | `/backup/snapshots` | back up now → `JobRef` |
| POST | `/backup/snapshots/{id}/restore` | restore → `JobRef` |
| GET | `/backup/policy` | schedule + retention + staleness threshold |
| PUT | `/backup/policy` | save it |

Both job-returning endpoints hand back a `jobId` for `JobLogPanel`, the
same contract as `POST /packages/{name}/upgrade`.

## 6. What the manifest contributes

The `backup:` block in a package manifest declares which paths that
package owns and what has to happen before they can be snapshotted
consistently. The dashboard renders this read-only on the app detail page
and on the What's protected tab. Writing the block, and running the
actions, is backend work that has not landed yet; this spec fixes the
shape so the frontend and the manifest schema agree when it does.

```yaml
backup:
  paths:
    - data/photos/library
  before:
    - kind: postgres-dump
      container: immich-postgres
      description: Dumps the Immich database so the snapshot is consistent
```

## 7. Out of scope

Repository creation and re-pointing (Kopia's UI), per-file restore,
restore-to-alternate-path, multi-repository setups, and off-box
verification of a restore. The last one is the honest gap: this page can
tell you a snapshot exists, not that it would restore cleanly. Kopia's
own `verify` is the answer and it belongs on the Schedule tab as a
periodic job once the backend can drive it.
