package com.tomaytotomato.aurora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wired into every {@code AuroraIntegrationTest} MockMvc instance as a
 * default expectation, so every request and response body the
 * integration suite already exercises is checked against
 * {@code openapi.yaml}'s JSON Schema without any test author having to
 * remember to ask for it.
 *
 * <p>That "automatic" part is the point. {@code OpenApiConformanceTest}
 * already checks that every implemented path is documented, but it says
 * nothing about the shape of what comes back. {@code PackagesController.get()}
 * returned {@code {package: {...}, env_example: ""}} against a spec, a
 * frontend and a set of MSW mocks that all expected a flat
 * {@code PackageDetail} — every field the app detail page read came back
 * {@code undefined} — and the path-only conformance test reported that
 * endpoint as fully documented throughout. An opt-in assertion helper
 * would have needed the author of every future controller test to
 * remember to call it; this needs nothing.
 *
 * <h2>How strict</h2>
 * <ul>
 *   <li><b>Required properties, types, enums, nullability</b> — full
 *       JSON Schema draft 2020-12 checking, since openapi.yaml is
 *       {@code openapi: 3.1.0} and already uses 3.1's {@code type: [X, 'null']}
 *       union style rather than the 3.0 {@code nullable: true} flag.</li>
 *   <li><b>Extra properties the spec never mentions</b> — rejected, at
 *       the root of the body and (for a list response) at the shape of
 *       each item. See {@link OpenApiSpec#validationSchemaFor} for why
 *       that check stops at one level rather than recursing everywhere.
 *       This is the strictness that would have caught the
 *       {@code PackagesController} bug even if, hypothetically, it had
 *       kept every required field present alongside the wrapper.</li>
 *   <li><b>A schema explicitly marked permissive</b> (an
 *       {@code additionalProperties} of its own — {@code true}, or a
 *       typed dictionary) — left alone. Several endpoints deliberately
 *       return free-form maps (env vars, labels, per-container resource
 *       stats); the spec says so explicitly, and this check respects
 *       that rather than fighting it.</li>
 *   <li><b>A request body on a call that got rejected</b> (a non-2xx
 *       response) — not checked. A test that deliberately POSTs an
 *       invalid enum or a malformed file to prove the backend returns
 *       400 is not describing drift; the body is invalid on purpose.
 *       Only a body the backend actually <em>accepted</em> is compared to
 *       what the spec says an acceptable body looks like.</li>
 *   <li><b>An undocumented status code, or a documented one with no
 *       schema</b> (most of this spec's 4xx responses are description-only —
 *       "Unknown package.", nothing more) — skipped, not failed. There is
 *       nothing to validate a body shape against, and failing on it would
 *       be a demand that every error response gets a schema, which is a
 *       larger and separate piece of work from the one this class does.</li>
 *   <li><b>An endpoint the backend implements but the spec never
 *       mentions at all</b> — skipped here; {@code OpenApiConformanceTest}
 *       already fails the build for that, and duplicating the failure
 *       from two places would only make it harder to find the one that
 *       explains what is actually wrong.</li>
 * </ul>
 *
 * <p>Format assertions (e.g. {@code format: date-time}) are deliberately
 * left as annotations only, not enforced — this class is about shape
 * drift, not a general-purpose spec linter, and enforcing format would
 * risk failing on a value that is a perfectly good timestamp just not
 * bit-for-bit the dialect the validator prefers.
 */
public final class OpenApiConformance implements ResultMatcher {

  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(VersionFlag.V202012);

  /**
   * A specific, named field that a request body sends and the backend
   * reads, but openapi.yaml's requestBody schema for that operation does
   * not mention. Stripped from the instance before validation so the rest
   * of the body still gets full checking; not a blanket exemption for the
   * operation.
   *
   * <p>openapi.yaml is out of bounds for this piece of work — it belongs
   * to a separate, concurrent change — so this is reported rather than
   * fixed. See {@code dev/notes/api-contract-testing-progress.md}.
   *
   * <p>{@code POST /onboarding/admin} sends {@code tz} (the box's
   * timezone, read by {@code OnboardingService.createInitialAdmin}) but
   * the spec's requestBody only documents {@code username} and
   * {@code password}.
   */
  private static final Map<String, Set<String>> KNOWN_UNDOCUMENTED_REQUEST_FIELDS = Map.of(
      "POST /onboarding/admin", Set.of("tz")
  );

  /**
   * An operation + direction where the spec's schema is a structurally
   * wrong fit for what the backend actually and reasonably does — not a
   * one-field typo, so not something {@link #KNOWN_UNDOCUMENTED_REQUEST_FIELDS}
   * can express. Logged loudly (stderr, visible in CI output) rather than
   * silently accepted, but does not fail the build: openapi.yaml is out of
   * bounds here, and each of these needs an authoring decision this piece
   * of work is not positioned to make unilaterally. See
   * {@code dev/notes/api-contract-testing-progress.md} for the report to
   * the owner.
   *
   * <ul>
   *   <li>{@code REQUEST PATCH /notifications/channels/{id}} — the
   *       requestBody is an {@code allOf} of the full {@code ChannelDraft}
   *       (which requires {@code kind, name, target, events}), but the
   *       operation is a partial update ("Change a channel, or mute it")
   *       and the backend correctly accepts e.g. {@code {"enabled": false}}
   *       alone.</li>
   *   <li>{@code REQUEST POST /system/import} — the requestBody is
   *       {@code SettingsExport}, the same schema as the export response,
   *       requiring {@code exportedAt, hostname, domain, profiles, dnsMode,
   *       settings}. {@code SettingsPortabilityController.importSettings}
   *       only ever reads {@code version}, {@code enabledPackages} and
   *       {@code domain} from the payload — everything else is genuinely
   *       optional in practice.</li>
   *   <li>{@code RESPONSE GET /disks/{id}/smart -> 200} — {@code DiskSmart.collectedAt}
   *       is typed as a required plain {@code string}, but a disk with no
   *       SMART support has nothing to collect and the backend correctly
   *       answers {@code collectedAt: null} rather than 404ing the whole
   *       resource.</li>
   *   <li>{@code RESPONSE GET /packages/{name} -> 200} — the domain record
   *       {@code Package} (also returned as-is by {@code GET /packages},
   *       so the same gap applies there, just with no test hitting it yet
   *       to surface it) serialises {@code recommends}, {@code profiles},
   *       {@code requiredEnv}, {@code postInstallNotes} and {@code sso},
   *       none of which {@code PackageSummary}/{@code PackageDetail}
   *       document. Every one of them is read by the frontend elsewhere
   *       ({@code sso} in particular gates the SSO badge), so trimming the
   *       backend response is not a safe unilateral call either — this
   *       needs someone to decide whether the fields get documented or
   *       the wire shape gets a dedicated view model.</li>
   * </ul>
   */
  private static final Set<String> KNOWN_GAPS = Set.of(
      "REQUEST PATCH /notifications/channels/{id}",
      "REQUEST POST /system/import",
      "RESPONSE GET /disks/{id}/smart -> 200",
      "RESPONSE GET /packages/{name} -> 200"
  );

  private final ObjectMapper mapper = new ObjectMapper();
  private final OpenApiSpec spec = OpenApiSpec.instance();

  public static OpenApiConformance conformsToSpec() {
    return new OpenApiConformance();
  }

  private OpenApiConformance() {
  }

  @Override
  public void match(MvcResult result) throws Exception {
    MockHttpServletRequest request = result.getRequest();
    Optional<String> specPath = specPath(request.getRequestURI());
    if (specPath.isEmpty()) return;

    String method = request.getMethod();
    String path = specPath.get();
    MockHttpServletResponse response = result.getResponse();
    int status = response.getStatus();

    checkRequest(method, path, request, status);
    checkResponse(method, path, response, status);
  }

  /**
   * Only a request the backend actually accepted (a 2xx) is compared to
   * the spec. A body a test sent to prove the backend rejects it (an
   * unknown enum value, a file from the wrong schema version) is invalid
   * on purpose — that is the test passing, not the contract drifting.
   */
  private void checkRequest(String method, String path, MockHttpServletRequest request, int status) throws Exception {
    if (status < 200 || status >= 300) return;

    byte[] body = request.getContentAsByteArray();
    if (body == null || body.length == 0 || !isJson(request.getContentType())) return;

    Optional<JsonNode> schema = spec.requestSchema(method, path);
    if (schema.isEmpty()) return;

    String template = spec.templateFor(method, path).orElse(path);
    JsonNode instance = mapper.readTree(body);
    Set<String> known = KNOWN_UNDOCUMENTED_REQUEST_FIELDS.get(method + " " + template);
    if (known != null && instance instanceof ObjectNode obj) {
      ObjectNode copy = obj.deepCopy();
      known.forEach(copy::remove);
      instance = copy;
    }

    validate(schema.get(), instance, body, "REQUEST " + method + " " + template,
        "request body for " + method + " " + path);
  }

  private void checkResponse(String method, String path, MockHttpServletResponse response, int status) throws Exception {
    byte[] body = response.getContentAsByteArray();
    if (body == null || body.length == 0 || !isJson(response.getContentType())) return;

    Optional<JsonNode> schema = spec.responseSchema(method, path, status);
    if (schema.isEmpty()) return;

    String template = spec.templateFor(method, path).orElse(path);
    validate(schema.get(), mapper.readTree(body), body, "RESPONSE " + method + " " + template + " -> " + status,
        "response body for " + method + " " + path + " -> " + status);
  }

  private void validate(JsonNode rawSchema, JsonNode instance, byte[] originalBody, String gapKey, String description) {
    JsonNode validationSchema = spec.validationSchemaFor(rawSchema);
    JsonSchema schema = FACTORY.getSchema(validationSchema);
    Set<ValidationMessage> problems = schema.validate(instance);
    if (problems.isEmpty()) return;

    String message = "openapi.yaml conformance failure — %s does not match its documented schema:\n%s\nactual body:\n%s\n"
        .formatted(description, formatProblems(problems), new String(originalBody, StandardCharsets.UTF_8));

    if (KNOWN_GAPS.contains(gapKey)) {
      System.err.println("[openapi conformance — known gap, see dev/notes/api-contract-testing-progress.md]\n" + message);
      return;
    }
    throw new AssertionError(message);
  }

  private static String formatProblems(Set<ValidationMessage> problems) {
    StringBuilder sb = new StringBuilder();
    for (ValidationMessage m : problems) {
      sb.append("  - ").append(m.getMessage()).append('\n');
    }
    return sb.toString();
  }

  private static boolean isJson(String contentType) {
    return contentType != null && contentType.toLowerCase().startsWith("application/json");
  }

  /** Strips the {@code /api} prefix the spec's {@code servers} entry describes; empty if outside it. */
  private static Optional<String> specPath(String requestUri) {
    if (requestUri == null || !requestUri.startsWith("/api")) return Optional.empty();
    String rest = requestUri.substring("/api".length());
    return Optional.of(rest.isEmpty() ? "/" : rest);
  }
}
