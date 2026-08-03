#!/usr/bin/env bash
# C9a token utility sweep — replace legacy --color-* Tailwind utilities
# with shadcn semantic equivalents across every view/component that
# consumes them. Order matters: longer suffixes first so bare `text-ink`
# doesn't chew off `text-ink-4`. Word boundaries are enforced by [^-a-zA-Z0-9]
# lookahead-ish patterns baked into each rule.
set -euo pipefail

cd "$(dirname "$0")/../frontend"

# List of files to touch — every view + component that grep flagged as
# holding a legacy utility class. src/components/ui/ is EXCLUDED — those
# primitives were already migrated in C1..C8. Spec + variant sidecars
# under ui/ are also skipped for the same reason.
FILES=$(grep -rlE '\b(text|bg|border|placeholder|ring|hover:text|hover:bg|hover:border|divide|from|to|via)-(canvas|surface|surface-2|ink|ink-2|ink-3|ink-4|line|line-2|accent|accent-hover|on-ink|on-accent|ink-hover|ok-bg|ok-fg|warn-bg|warn-fg|err-bg|err-fg|info-bg|info-fg)\b' src/views src/components 2>/dev/null | grep -v '/components/ui/')

for f in $FILES; do
  # Order: longest suffix first so we don't chew `text-ink-4` into
  # `text-foreground-4`. `sed -E` for extended regex + `\b` word
  # boundaries so we don't touch e.g. `bg-canvas-something-else`.
  sed -i -E '
    s/\btext-ink-4\b/text-muted-foreground/g;
    s/\btext-ink-3\b/text-muted-foreground/g;
    s/\btext-ink-2\b/text-foreground/g;
    s/\bhover:text-ink-2\b/hover:text-foreground/g;
    s/\bhover:text-ink\b/hover:text-foreground/g;
    s/\btext-ink\b/text-foreground/g;
    s/\bhover:border-ink-4\b/hover:border-muted-foreground/g;
    s/\bborder-ink-2\b/border-muted-foreground/g;
    s/\bborder-line-2\b/border-border/g;
    s/\bborder-line\b/border-border/g;
    s/\bdivide-line\b/divide-border/g;
    s/\bhover:bg-surface-2\b/hover:bg-muted/g;
    s/\bhover:bg-surface\b/hover:bg-card/g;
    s/\bbg-surface-2\b/bg-muted/g;
    s/\bbg-surface\b/bg-card/g;
    s/\bbg-canvas\b/bg-background/g;
    s/\btext-accent\b/text-[var(--color-accent)]/g;
    s/\bborder-accent\b/border-[var(--color-accent)]/g;
  ' "$f"
done

echo "Swept $(echo "$FILES" | wc -l) files."
