import { config } from '@vue/test-utils';

// oh-vue-icons' <v-icon> is registered globally in main.ts (see
// src/plugins/icons.ts), not in the per-test app. Stub it so components
// that render it (DockerBadge, AppIcon) mount cleanly. The stub keeps the
// `name` prop as data-icon and lets fallthrough attrs (data-slot, class)
// land on its root, so specs can assert which glyph rendered.
config.global.stubs = {
  'v-icon': {
    name: 'VIconStub',
    props: ['name'],
    template: '<svg :data-icon="name"></svg>',
  },
};
