#!/usr/bin/env bash
# C9b inline var(--color-*) sweep — migrate the remaining explicit
# arbitrary-value tokens and raw var() refs in .vue/.ts/main.css to
# their shadcn semantic equivalents. Brand-amber (--color-accent,
# --color-accent-hover, --color-on-accent) stays intact because
# shadcn's `accent` slot is intentionally unmapped for Aurora — see
# main.css @theme comment.
#
# After this step, C9c can safely delete the legacy --color-canvas /
# --color-surface / --color-ink / --color-line / --color-ok-bg / etc.
# declarations from the @theme block, plus the hand-rolled
# .text-ink / .bg-canvas / .border-line utility classes in
# @layer utilities.
set -euo pipefail

cd "$(dirname "$0")/../frontend"

# Files to touch: every source file with a var(--color-<legacy>) or
# arbitrary-value token reference, EXCLUDING the ui/*.vue primitives
# and their variant sidecars (they were migrated in C1..C8 and only
# retain --color-accent brand refs) and buttonVariants which keeps
# brand-amber refs on purpose.
FILES=$(grep -rl 'var(--color-\(canvas\|surface\|surface-2\|ink\|ink-2\|ink-3\|ink-4\|line\|line-2\|on-ink\|ink-hover\|ok-bg\|ok-fg\|warn-bg\|warn-fg\|err-bg\|err-fg\|info-bg\|info-fg\)' src 2>/dev/null | grep -v 'buttonVariants\|alertVariants\|badgeVariants')

for f in $FILES; do
  # ── Arbitrary-value tint utilities (bg-[var(--color-X-bg)] → bg-<sem>/10) ──
  sed -i -E '
    s#bg-\[var\(--color-ok-bg\)\]#bg-success/10#g;
    s#bg-\[var\(--color-warn-bg\)\]#bg-warning/10#g;
    s#bg-\[var\(--color-err-bg\)\]#bg-destructive/10#g;
    s#bg-\[var\(--color-info-bg\)\]#bg-info/10#g;
    s#text-\[var\(--color-ok-fg\)\]#text-success#g;
    s#text-\[var\(--color-warn-fg\)\]#text-warning#g;
    s#text-\[var\(--color-err-fg\)\]#text-destructive#g;
    s#text-\[var\(--color-info-fg\)\]#text-info#g;
    s#border-\[var\(--color-ok-fg\)\]#border-success#g;
    s#border-\[var\(--color-warn-fg\)\]#border-warning#g;
    s#border-\[var\(--color-err-fg\)\]#border-destructive#g;
    s#border-\[var\(--color-info-fg\)\]#border-info#g;
    s#hover:bg-\[var\(--color-ok-fg\)\]#hover:bg-success#g;
    s#hover:bg-\[var\(--color-err-fg\)\]#hover:bg-destructive#g;
    s@text-\[var\(--color-err,#c33\)\]@text-destructive@g;
  ' "$f"

  # ── Monochrome arbitrary-value utilities ──
  sed -i -E '
    s#bg-\[var\(--color-ink\)\]#bg-foreground#g;
    s#bg-\[var\(--color-canvas\)\]#bg-background#g;
    s#bg-\[var\(--color-surface-2\)\]#bg-muted#g;
    s#bg-\[var\(--color-surface\)\]#bg-card#g;
    s#bg-\[var\(--color-line-2\)\]#bg-secondary#g;
    s#border-\[var\(--color-ink\)\]#border-foreground#g;
    s#border-\[var\(--color-line-2\)\]#border-border#g;
    s#border-\[var\(--color-line\)\]#border-border#g;
    s#hover:border-\[var\(--color-ink-4\)\]#hover:border-muted-foreground#g;
    s#divide-\[var\(--color-line-2\)\]#divide-border#g;
    s#divide-\[var\(--color-line\)\]#divide-border#g;
    s#text-\[var\(--color-ink-2\)\]#text-foreground#g;
    s#text-\[var\(--color-ink-3\)\]#text-muted-foreground#g;
    s#text-\[var\(--color-ink-4\)\]#text-muted-foreground#g;
    s#text-\[var\(--color-canvas\)\]#text-background#g;
  ' "$f"

  # ── Raw var(--color-<legacy>) refs inside style="" / <style> / .ts strings ──
  sed -i -E '
    s#var\(--color-ink-2\)#var(--color-foreground)#g;
    s#var\(--color-ink-3\)#var(--color-muted-foreground)#g;
    s#var\(--color-ink-4\)#var(--color-muted-foreground)#g;
    s#var\(--color-on-ink\)#var(--color-primary-foreground)#g;
    s#var\(--color-canvas\)#var(--color-background)#g;
    s#var\(--color-surface-2\)#var(--color-muted)#g;
    s#var\(--color-surface\)#var(--color-card)#g;
    s#var\(--color-line-2\)#var(--color-border)#g;
    s#var\(--color-line\)#var(--color-border)#g;
    s#var\(--color-err-fg\)#var(--color-destructive)#g;
    s#var\(--color-warn-fg\)#var(--color-warning)#g;
    s#var\(--color-ok-fg\)#var(--color-success)#g;
    s#var\(--color-info-fg\)#var(--color-info)#g;
    s#var\(--color-ink,([^)]+)\)#var(--color-foreground, \1)#g;
    s#var\(--color-ink\)#var(--color-foreground)#g;
  ' "$f"
done

echo "Swept $(echo "$FILES" | wc -l) files."
