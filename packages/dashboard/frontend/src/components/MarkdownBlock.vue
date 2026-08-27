<script setup lang="ts">
import { computed, type HTMLAttributes } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { cn } from '@/lib/utils';

// Renders trusted-source markdown (package READMEs, help text) as HTML.
//
// Two guards against the "we just added v-html to the app" foot-gun:
//   1. `marked` runs in its default sync + GFM mode. We don't want raw
//      HTML pass-through — a manifest author dropping a <script> should
//      not become a stored XSS — so `mangle` and header IDs are off and
//      DOMPurify sanitises the output regardless.
//   2. DOMPurify with the defaults strips <script>, on* handlers, and
//      javascript: URLs. External links get target=_blank + rel=noopener
//      via an afterSanitizeAttributes hook so a click doesn't hand the
//      opener window to whatever the manifest linked to.
//
// The `prose` CSS class picks up the Tailwind typography styling in
// app.css so headings, code blocks, lists etc. look like the rest of
// the dashboard rather than a browser default.

const props = defineProps<{
  source: string;
  class?: HTMLAttributes['class'];
}>();

// One-time: any external anchor gets target=_blank + rel=noopener.
// DOMPurify hooks are idempotent so registering it on every render is
// fine, but doing it once keeps the hot path tight.
let hookInstalled = false;
function installLinkHook() {
  if (hookInstalled) return;
  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (node.tagName === 'A') {
      const el = node as HTMLAnchorElement;
      const href = el.getAttribute('href') ?? '';
      if (/^https?:/i.test(href)) {
        el.setAttribute('target', '_blank');
        el.setAttribute('rel', 'noopener noreferrer');
      }
    }
  });
  hookInstalled = true;
}
installLinkHook();

marked.setOptions({ gfm: true, breaks: false });

const html = computed(() => {
  const raw = marked.parse(props.source ?? '', { async: false }) as string;
  return DOMPurify.sanitize(raw);
});
</script>

<template>
  <div :class="cn('prose prose-sm max-w-none', props.class)" v-html="html" />
</template>
