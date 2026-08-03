import { cva, type VariantProps } from 'class-variance-authority';

// shadcn-vue Button variants (C3 iter-5).
//
// Variant names are Aurora-flavoured (primary/secondary/ghost/link/danger/
// accent) rather than raw shadcn (default/destructive/outline/secondary/
// ghost/link) so existing callers keep working — the migration swap here
// is TOKEN-LEVEL, not API-level.
//
// All colour references now flow through the shadcn semantic tokens
// declared in src/assets/main.css @theme (--color-primary,
// --color-secondary, --color-muted, --color-border, --color-destructive,
// --color-foreground/-muted-foreground). The one exception is `accent`,
// which is the amber brand CTA; shadcn's `accent` token is intentionally
// unmapped (see main.css comment), so we address the brand amber via
// arbitrary values against --color-accent / --color-on-accent.
export const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap font-medium ' +
    'transition-all duration-150 active:scale-[0.99] ' +
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background ' +
    'disabled:pointer-events-none disabled:opacity-40',
  {
    variants: {
      variant: {
        primary: 'bg-primary text-primary-foreground hover:bg-primary/90 rounded-md',
        secondary:
          'bg-secondary text-secondary-foreground border border-border hover:bg-muted rounded-md',
        ghost: 'bg-transparent text-muted-foreground hover:bg-muted hover:text-foreground rounded-md',
        link: 'bg-transparent text-foreground underline-offset-4 hover:underline p-0',
        danger:
          'bg-transparent border border-border text-destructive hover:bg-destructive/10 rounded-md',
        accent:
          'bg-[var(--color-accent)] text-[var(--color-on-accent)] hover:bg-[var(--color-accent-hover)] rounded-md',
      },
      size: {
        sm: 'h-8 px-3 text-xs',
        md: 'h-10 px-4 text-sm',
        lg: 'h-11 px-5 text-sm',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'md',
    },
  },
);

export type ButtonVariants = VariantProps<typeof buttonVariants>;
