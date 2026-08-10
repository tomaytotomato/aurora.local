package com.tomaytotomato.aurora;

import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the controllers against {@code openapi.yaml}.
 *
 * <p>Aurora's whole frontend was written against that spec before any of
 * these endpoints existed, so the spec is not documentation — it is the
 * contract, and the frontend already believes it. The CI job added
 * earlier checks the spec is well-formed; nothing until now checked that
 * the backend agrees with it.
 *
 * <p>The two directions are deliberately not symmetric:
 *
 * <ul>
 *   <li><b>An implemented endpoint missing from the spec fails the
 *       test.</b> That is drift: the frontend cannot know about it, and
 *       the next person to read the spec will be misled.</li>
 *   <li><b>A specified endpoint with no implementation is reported, not
 *       failed.</b> Roughly forty of those exist on purpose — stages 2 to
 *       4 are written down and not yet built — so failing on them would
 *       mean a permanently red test nobody looks at.</li>
 * </ul>
 */
@DisplayName("the controllers against openapi.yaml")
class OpenApiConformanceTest extends AuroraIntegrationTest {

  /** Spec lives beside the backend module, not inside it. */
  private static final Path SPEC = Path.of("../openapi.yaml");

  /**
   * Endpoints that exist on purpose and are not part of the public
   * contract, so their absence from the spec is correct.
   */
  private static final Set<String> NOT_PUBLIC_API = Set.of(
      // E2E-only rewind hook, guarded by aurora.e2e-mode and 404 in prod.
      "POST /onboarding/reset",
      // Spring Boot's own actuator surface.
      "GET /actuator", "GET /actuator/health", "GET /actuator/info"
  );

  // Actuator contributes a second RequestMappingHandlerMapping, and it is
  // not the one describing Aurora's controllers.
  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping handlerMapping;

  @Test
  void every_implemented_endpoint_is_in_the_spec() throws IOException {
    Set<String> implemented = implementedEndpoints();
    Set<String> specified = specifiedEndpoints();

    Set<String> undocumented = new TreeSet<>(implemented);
    undocumented.removeAll(specified);
    undocumented.removeAll(NOT_PUBLIC_API);

    assertThat(undocumented)
        .as("""
            These endpoints exist in the backend but not in openapi.yaml. \
            The frontend is written against that spec, so an endpoint \
            missing from it is one nobody can use and nobody knows about. \
            Add it to the spec, or add it to NOT_PUBLIC_API if it is \
            deliberately private.""")
        .isEmpty();
  }

  @Test
  void reports_what_is_specified_but_not_yet_built() throws IOException {
    Set<String> implemented = implementedEndpoints();
    Set<String> specified = specifiedEndpoints();

    Set<String> outstanding = new TreeSet<>(specified);
    outstanding.removeAll(implemented);

    // Informational on purpose. The frontend was built ahead of the
    // backend by design, so this number starts high and should only ever
    // fall. Printed rather than asserted so it stays useful instead of
    // becoming a permanently red test.
    System.out.println("openapi endpoints not yet implemented (" + outstanding.size() + "):");
    outstanding.forEach(e -> System.out.println("  " + e));

    assertThat(specified)
        .as("the spec should describe considerably more than is built, "
            + "since the frontend was written against it first")
        .isNotEmpty();
  }

  // ------------------------------------------------------------------

  /** {@code "GET /jobs/{}"} for every mapped handler under /api. */
  private Set<String> implementedEndpoints() {
    Set<String> out = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      var methods = info.getMethodsCondition().getMethods();
      var patterns = info.getPathPatternsCondition() == null
          ? Set.<String>of()
          : info.getPathPatternsCondition().getPatternValues();

      for (String pattern : patterns) {
        if (!pattern.startsWith("/api/")) continue;
        String path = normalise(pattern.substring("/api".length()));
        if (methods.isEmpty()) {
          // A mapping with no verb answers all of them; record the ones
          // the spec could plausibly describe.
          out.add("GET " + path);
        } else {
          methods.forEach(m -> out.add(m.name() + " " + path));
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Set<String> specifiedEndpoints() throws IOException {
    assertThat(Files.isRegularFile(SPEC))
        .as("openapi.yaml should sit beside the backend module at %s", SPEC.toAbsolutePath())
        .isTrue();

    Map<String, Object> doc = new Yaml().load(Files.readString(SPEC, StandardCharsets.UTF_8));
    Map<String, Object> paths = (Map<String, Object>) doc.getOrDefault("paths", new LinkedHashMap<>());

    Set<String> verbs = Set.of("get", "put", "post", "delete", "patch", "head", "options");
    Set<String> out = new TreeSet<>();
    for (var entry : paths.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?> operations)) continue;
      String path = normalise(entry.getKey());
      for (Object rawVerb : operations.keySet()) {
        String verb = String.valueOf(rawVerb).toLowerCase();
        if (verbs.contains(verb)) {
          out.add(verb.toUpperCase() + " " + path);
        }
      }
    }
    return out;
  }

  /**
   * Path variables are named differently on each side — {@code {name}}
   * against {@code {package}}, {@code {id}} against {@code {vhost}} — and
   * the name is not part of the contract. Only the shape is.
   */
  private static String normalise(String path) {
    return path.replaceAll("\\{[^}]*}", "{}");
  }
}
