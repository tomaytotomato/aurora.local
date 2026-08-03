import { cva, type VariantProps } from 'class-variance-authority';

// shadcn-vue Alert variants (C1 iter-3).
// "new-york" style base: relative w-full rounded-md border, icon-friendly layout.
// Semantic extensions beyond shadcn's default/destructive: warning, info, success.
// Each variant applies a light color-mix tint (bg-<var>/8) so the alert reads
// as a banner without overwhelming the page.
export const alertVariants = cva(
  'relative w-full rounded-md border px-4 py-3 text-sm ' +
    '[&>svg]:absolute [&>svg]:left-4 [&>svg]:top-3 [&>svg~*]:pl-7',
  {
    variants: {
      variant: {
        default: 'bg-background text-foreground border-border',
        destructive:
          'bg-destructive/8 border-destructive/40 text-destructive [&>svg]:text-destructive',
        warning: 'bg-warning/8 border-warning/40 text-warning [&>svg]:text-warning',
        info: 'bg-info/8 border-info/40 text-info [&>svg]:text-info',
        success: 'bg-success/8 border-success/40 text-success [&>svg]:text-success',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  },
);

export type AlertVariants = VariantProps<typeof alertVariants>;
