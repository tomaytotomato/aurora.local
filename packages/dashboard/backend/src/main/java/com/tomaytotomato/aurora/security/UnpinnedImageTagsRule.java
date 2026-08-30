package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * B4 rule 3: flags containers whose image reference isn't digest-pinned.
 *
 * <p>An image reference such as {@code linuxserver/sonarr:latest} or
 * {@code postgres:16} is a floating pointer \u2014 the underlying content
 * can change without the operator's knowledge, silently rolling out an
 * upgrade that may break config, revoke API contracts, or (in a supply
 * chain attack) ship malicious code. The safe form is
 * {@code postgres:16@sha256:abc123\u2026}: tag for discoverability, digest
 * for reproducibility.
 *
 * <p>What we flag:
 * <ul>
 *   <li>Image ref ending in {@code :latest} \u2014 the classic footgun.
 *       HIGH severity.</li>
 *   <li>Image ref without an {@code @sha256:} digest \u2014 covers
 *       tag-only refs (e.g. {@code :16}, no tag = defaults to latest).
 *       MEDIUM severity because a pinned tag is meaningfully better
 *       than {@code :latest}, just not perfect.</li>
 * </ul>
 *
 * <p>Aurora's own image is exempt \u2014 the operator can't pin
 * "the currently-running Aurora" without a chicken-and-egg problem
 * during the wizard. Users self-hosting off {@code ghcr.io/tomaytotomato/aurora:main}
 * see this called out in the release notes instead.
 */
@Component
public class UnpinnedImageTagsRule implements SecurityRule {

  private static final Logger log = LoggerFactory.getLogger(UnpinnedImageTagsRule.class);

  private final DockerService docker;

  public UnpinnedImageTagsRule(DockerService docker) {
    this.docker = docker;
  }

  @Override
  public String id() { return "unpinned_image_tags"; }

  @Override
  public List<SecurityFinding> evaluate() {
    List<SecurityFinding> out = new ArrayList<>();
    try {
      for (Container c : docker.listProjectContainers()) {
        String name = firstName(c);
        if (name == null) continue;
        if (DockerSocketExposureRule.isAuroraOwner(name)) continue;
        String image = c.getImage();
        if (image == null || image.isBlank()) continue;

        Verdict v = classify(image);
        if (v == Verdict.PINNED) continue;

        // Severity is about what the owner should DO, not about how much
        // an engineer dislikes a floating tag. These images are the ones
        // Aurora itself ships: a box was in this state the moment it
        // finished installing, nobody did anything wrong, and there is no
        // action available to a person who has never opened a terminal.
        // Reporting three of those as HIGH on a twenty-minute-old box
        // (which is what shipped) teaches exactly one lesson — that the
        // red things on the security page are to be dismissed.
        String severity = v == Verdict.LATEST_TAG ? SecurityFinding.MEDIUM : SecurityFinding.LOW;
        String reason = v == Verdict.LATEST_TAG
            ? "follows the moving `latest` tag"
            : "follows a tag that can move";
        out.add(new SecurityFinding(
            id() + ":" + name,
            severity,
            prettyName(name) + " updates to whatever version is newest",
            prettyName(name) + " " + reason + ", so a restart can quietly pick up "
                + "a different version of `" + image + "` than the one running now. "
                + "Nothing is broken and there is nothing for you to do: Aurora "
                + "pins these versions in its own releases, and this entry "
                + "disappears when it does.",
            null
        ));
      }
    } catch (Exception e) {
      log.debug("unpinned-image-tags rule failed: {}", e.getMessage());
    }
    return out;
  }

  /** Container names are how docker refers to it; this is how a person would. */
  private static String prettyName(String name) {
    if (name == null || name.isBlank()) return "This app";
    String base = name.replace('_', ' ').replace('-', ' ').trim();
    return Character.toUpperCase(base.charAt(0)) + base.substring(1);
  }

  private static String firstName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return null;
    String n = names[0];
    return n.startsWith("/") ? n.substring(1) : n;
  }

  /**
   * Package-private for tests. Fold the reference into one of three
   * categories.
   */
  // Public because HardeningService needs the same judgement about a
  // compose file's image reference that this rule makes about a running
  // container's. One definition of "pinned", not two.
  public static Verdict classify(String image) {
    if (image == null) return Verdict.PINNED;
    // Digest present ⇒ pinned. Digest may follow a tag ("postgres:16@sha256:…")
    // or replace it ("postgres@sha256:…").
    if (image.contains("@sha256:")) return Verdict.PINNED;

    // No digest. Now inspect the tag: strip the registry+path (up to the
    // last '/') so a port number like "registry.example.com:5000/foo" isn't
    // mistaken for a tag.
    int lastSlash = image.lastIndexOf('/');
    String namePart = lastSlash >= 0 ? image.substring(lastSlash + 1) : image;
    int colon = namePart.indexOf(':');
    String tag = colon >= 0 ? namePart.substring(colon + 1) : "";
    // No explicit tag = implicit ":latest" on the daemon side.
    if (tag.isEmpty() || "latest".equalsIgnoreCase(tag)) return Verdict.LATEST_TAG;
    return Verdict.FLOATING_TAG;
  }

  public enum Verdict { PINNED, LATEST_TAG, FLOATING_TAG }
}
