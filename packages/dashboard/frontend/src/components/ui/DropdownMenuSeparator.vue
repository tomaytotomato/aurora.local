<script setup lang="ts">
// Aurora DropdownMenuSeparator — hair-line between menu groups.
// Non-focusable; excluded from arrow-key nav because it lacks
// data-menu-item.
</script>

<template>
  <div role="separator" class="my-1 h-px bg-border" data-slot="dropdown-separator" />
</template>
