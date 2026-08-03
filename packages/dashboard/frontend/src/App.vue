<script setup lang="ts">
// Aurora — root. The router owns the actual shell (AppShell / OnboardingShell / LoginView).
// The Toaster overlay hangs off the root so notifications survive route changes
// (useToast queue is module-scoped anyway; the mount just draws the queue).
import { Toaster } from '@/components/ui';
</script>

<template>
  <router-view />
  <Toaster />
</template>
