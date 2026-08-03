package com.tomaytotomato.aurora.security;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.tomaytotomato.aurora.domain.SecurityFinding;
import com.tomaytotomato.aurora.services.DockerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * B4 rule 2: flags every non-Aurora container that has the docker
 * socket bind-mounted read-only or read-write.
 *
 * <p>Any container with {@code /var/run/docker.sock} can list, control,
 * and destroy every other container on the host \u2014 it's an implicit
 * root-on-host. Aurora itself needs the socket to run the wizard,
 * launch stacks, and stream events; everyone else on this box does
 * not. If Sarah copy-pasted a compose file that adds the socket to,
 * say, a homepage tile helper or a docker-explorer webapp, that's a
 * genuine footgun and Aurora should surface it.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Iterates all aurora-managed containers via
 *       {@link DockerService#listProjectContainers()}. Rogue containers
 *       outside the aurora compose project stay invisible (by design;
 *       Aurora doesn't claim ownership over them).</li>
 *   <li>Skips a container whose name is {@code aurora} or starts with
 *       {@code aurora-dashboard} \u2014 the dashboard itself is the
 *       intended holder of the socket.</li>
 *   <li>Emits MEDIUM severity per container. Not HIGH because the
 *       mount alone doesn't prove compromise; a compromised container
 *       can escalate but the check flags exposure, not exploitation.</li>
 * </ul>
 */
@Component
public class DockerSocketExposureRule implements SecurityRule {

  private static final Logger log = LoggerFactory.getLogger(DockerSocketExposureRule.class);

  static final String DOCKER_SOCK = "/var/run/docker.sock";

  /**
   * Aurora's own container name(s). Anything else with the socket
   * mounted gets flagged. Exact match only — we deliberately do NOT
   * treat every {@code aurora-*} compose-project container as an owner,
   * because that would silently exempt {@code aurora-media-sonarr},
   * {@code aurora-portainer}, etc., which is exactly the shape of the
   * footgun we're trying to catch.
   */
  static final java.util.Set<String> AURORA_OWNERS = java.util.Set.of(
      "aurora",
      "aurora-dashboard"
  );

  private final DockerService docker;

  public DockerSocketExposureRule(DockerService docker) {
    this.docker = docker;
  }

  @Override
  public String id() { return "docker_socket_exposure"; }

  @Override
  public List<SecurityFinding> evaluate() {
    List<SecurityFinding> out = new ArrayList<>();
    try {
      for (Container c : docker.listProjectContainers()) {
        String name = firstName(c);
        if (name == null) continue;
        if (isAuroraOwner(name)) continue;

        List<ContainerMount> mounts = c.getMounts();
        if (mounts == null) continue;
        for (ContainerMount m : mounts) {
          if (m == null) continue;
          String src = m.getSource();
          if (DOCKER_SOCK.equals(src)) {
            out.add(new SecurityFinding(
                id() + ":" + name,
                SecurityFinding.MEDIUM,
                "Container " + name + " has access to the docker socket",
                "The container " + name + " is bind-mounted with "
                    + DOCKER_SOCK + ". Any process inside that container "
                    + "can list, control, and destroy other containers on "
                    + "this box \u2014 the same power Aurora itself has. "
                    + "If this container isn't meant to manage docker, "
                    + "remove the mount from its compose file.",
                null
            ));
            break; // one finding per container is enough
          }
        }
      }
    } catch (Exception e) {
      log.debug("docker-socket-exposure rule failed: {}", e.getMessage());
    }
    return out;
  }

  private static String firstName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return null;
    String n = names[0];
    return n.startsWith("/") ? n.substring(1) : n;
  }

  static boolean isAuroraOwner(String name) {
    if (name == null || name.isEmpty()) return false;
    return AURORA_OWNERS.contains(name);
  }
}
