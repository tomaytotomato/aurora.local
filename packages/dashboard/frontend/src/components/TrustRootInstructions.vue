<script setup lang="ts">
/**
 * How to trust this box's certificate, per device.
 *
 * <p>One component because there were two copies — the wizard's TLS step
 * and Settings → TLS — with a comment on each saying they must be kept in
 * sync. They had already drifted (the wizard's Linux entry said "Aurora
 * will show you a step-by-step in Settings" and Settings then showed a
 * different thing), which is what a "keep these in sync" comment
 * eventually buys you.
 *
 * <p>Two corrections to the content itself, both cases of instructions
 * someone would follow literally and still end up with a warning:
 *
 * <ul>
 *   <li><b>Linux</b> said `update-ca-certificates` and stopped. That fills
 *       the system store, which Chrome, Chromium and Edge do not read —
 *       they keep their own NSS database. Following it exactly left the
 *       browser still warning, with no hint why.</li>
 *   <li><b>iOS / Android</b> were one entry giving only the iOS path
 *       ("Settings → General → About → Certificate Trust"), which does not
 *       exist on Android. Split, with each platform's real path.</li>
 * </ul>
 *
 * <p>`variant` controls density only: the wizard has room to breathe, the
 * settings card does not.
 */
withDefaults(defineProps<{
  variant?: 'wizard' | 'settings';
}>(), { variant: 'settings' });
</script>

<template>
  <div
    :class="variant === 'wizard' ? 'space-y-4 text-sm text-muted-foreground' : 'space-y-3 text-xs text-muted-foreground'"
    data-test="trust-root-instructions"
  >
    <div>
      <div class="eyebrow mb-1 text-foreground">macOS</div>
      <p>
        Double-click the file, add it to the <em>System</em> keychain, then open it
        and set <em>Always Trust</em>.
      </p>
    </div>

    <div>
      <div class="eyebrow mb-1 text-foreground">Windows</div>
      <p>
        Right-click the file → Install Certificate → Local Machine → place it in
        <em>Trusted Root Certification Authorities</em>.
      </p>
    </div>

    <div>
      <div class="eyebrow mb-1 text-foreground">iPhone / iPad</div>
      <p>
        AirDrop or email the file to the device and open it, then
        Settings → General → VPN &amp; Device Management to install the profile.
        Finally turn it on under Settings → General → About →
        <em>Certificate Trust Settings</em> — it does nothing until you do.
      </p>
    </div>

    <div>
      <div class="eyebrow mb-1 text-foreground">Android</div>
      <p>
        Copy the file to the phone, then Settings → Security → Encryption &amp;
        credentials → <em>Install a certificate</em> → <em>CA certificate</em>.
        Android warns that someone could monitor you; that someone is this box,
        on your own network.
      </p>
    </div>

    <div>
      <div class="eyebrow mb-1 text-foreground">Linux</div>
      <p class="mb-1">
        For the system and most apps:
      </p>
      <p class="mb-1">
        <code class="bg-muted px-1 py-0.5 rounded border border-border">sudo cp caddy-root.crt /usr/local/share/ca-certificates/ &amp;&amp; sudo update-ca-certificates</code>
      </p>
      <p>
        Chrome, Chromium and Edge ignore that store and keep their own, so they
        need one more:
      </p>
      <p>
        <code class="bg-muted px-1 py-0.5 rounded border border-border">certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n aurora -i caddy-root.crt</code>
      </p>
    </div>

    <div>
      <div class="eyebrow mb-1 text-foreground">Firefox (any platform)</div>
      <p>
        Firefox keeps its own trust store too. Settings → Privacy &amp; Security →
        Certificates → <em>View Certificates</em> → <em>Authorities</em> →
        <em>Import</em>, and tick "Trust this CA to identify websites".
      </p>
    </div>
  </div>
</template>
