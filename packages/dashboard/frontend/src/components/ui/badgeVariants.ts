import { cva, type VariantProps } from 'class-variance-authority';

// shadcn-vue Badge variants (C4 iter-6).
//
// Prop is called `tone` (not `variant`) to preserve the existing Aurora
// public API — six caller files use `tone="ok|warn|err|info|neutral"`.
// This is a TOKEN migration, not an API rename.
//
// All colours flow through the shadcn semantic tokens declared in
// src/assets/main.css @theme:
//   --color-success / --color-warning / --color-info / --color-destructive
//   plus --color-muted / --color-muted-foreground for the neutral pill.
//
// Tint density: Badges are small (10pt uppercase pills) so we go a step
// stronger than Alert's /8 tint — /12 gives enough contrast against the
// warm-off-white canvas without competing with the surrounding copy.
export const badgeVariants = cva(
  'inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full ' +
    'text-[0.6875rem] font-medium uppercase tracking-[0.08em] ' +
    'transition-colors',
  {
    variants: {
      tone: {
        ok: 'bg-success/12 text-success',
        warn: 'bg-warning/12 text-warning',
        err: 'bg-destructive/12 text-destructive',
        info: 'bg-info/12 text-info',
        neutral: 'bg-muted text-muted-foreground',
      },
    },
    defaultVariants: {
      tone: 'neutral',
    },
  },
);

export type BadgeVariants = VariantProps<typeof badgeVariants>;
