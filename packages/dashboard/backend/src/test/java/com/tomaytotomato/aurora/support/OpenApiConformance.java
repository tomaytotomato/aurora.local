package com.tomaytotomato.aurora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
 *       each item. See {@link OpenApiSpec#validationSchemaFor(JsonNode)}
 *       for why that check stops at one level rather than recursing
 *       everywhere. This is the strictness that would have caught the
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
 *
 * <h2>Known, reported, out-of-scope gaps</h2>
 *
 * openapi.yaml is out of bounds for this piece of work — it belongs to a
 * separate, concurrent change — so the handful of genuine violations this
 * check found while it was being built are reported, not fixed (see
 * {@code dev/notes/api-contract-testing-progress.md}). Each is carved out
 * as narrowly as possible: a single named field on a single named
 * operation, never "stop checking this endpoint". A registry entry
 * silencing an operation wholesale would have silenced this class's own
 * proof that it works — see the progress log entry for how that nearly
 * happened here.
 */
public final class OpenApiConformance implements ResultMatcher {

  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(VersionFlag.V202012);

  /**
   * A specific, named field a request body sends and the backend reads,
   * but the requestBody schema for that operation does not mention.
   * Stripped from the instance before validation.
   *
   * <p>{@code POST /onboarding/admin} sends {@code tz} (the box's
   * timezone, read by {@code OnboardingService.createInitialAdmin}) but
   * the spec's requestBody only documents {@code username} and
   * {@code password}.
   */
  private static final Map<String, Set<String>> KNOWN_UNDOCUMENTED_REQUEST_FIELDS = Map.of();

  /**
   * A specific, named field a response body sends that the response
   * schema for that operation does not mention. Stripped from the
   * instance before validation — the same trick as
   * {@link #KNOWN_UNDOCUMENTED_REQUEST_FIELDS}, just in the other
   * direction.
   *
   * <p>{@code GET /packages/{name} -> 200} (and {@code GET /packages},
   * which returns the same domain record and would show the identical
   * gap the moment anything tests it): the {@code Package} record
   * serialises {@code recommends}, {@code profiles}, {@code requiredEnv},
   * {@code postInstallNotes} and {@code sso}, none of which
   * {@code PackageSummary}/{@code PackageDetail} document. Every one of
   * them is read by the frontend elsewhere ({@code sso} gates the SSO
   * badge), so trimming the backend response is not a safe unilateral
   * call — this needs someone to decide whether the fields get
   * documented or the wire shape gets a dedicated view model.
   *
   * <p>{@code GET /packages -> 200} is the identical gap on the list
   * endpoint — same {@code Package} record, same five fields — predicted
   * in the paragraph above but not yet carved out because nothing had
   * exercised it with a non-empty, fully-populated fixture list through
   * this checker until an onboarding-completion test did.
   *
   * <p>{@code GET /auth/me -> 200}: {@code AuthController.Session}
   * carries {@code role} (added well before {@code GET /auth/me} was
   * itself added to the spec), but the spec's {@code Session} schema —
   * shared with {@code GET /auth/session}, which has the identical gap —
   * only documents {@code authenticated}, {@code username},
   * {@code passkeyEnrolled} and {@code tz}. The frontend already reads
   * {@code role} to gate the {@code /users} nav link and admin-only
   * views, so this is drift to document and fix in the spec, not a field
   * to quietly drop from the response.
   */
  private static final Map<String, Set<String>> KNOWN_UNDOCUMENTED_RESPONSE_FIELDS = Map.of();

  /**
   * A specific, named response field that comes back {@code null} in a
   * legitimate case the spec's schema does not allow for (typed as a
   * plain, non-nullable value). The {@code null} is replaced with a
   * placeholder of the same JSON type before validation, so the rest of
   * the object still gets full checking — including its own
   * {@code required}, so a response that dropped the field entirely
   * still fails.
   *
   * <p>{@code GET /disks/{id}/smart -> 200}: {@code DiskSmart.collectedAt}
   * is a required plain {@code string}, but a disk with no SMART support
   * has nothing to collect and the backend correctly answers
   * {@code collectedAt: null} rather than 404ing the whole resource.
   */
  private static final Map<String, Set<String>> KNOWN_NULLABLE_RESPONSE_FIELDS = Map.of();

  /**
   * An operation whose requestBody schema requires fields the operation
   * does not actually need — reused wholesale from a stricter sibling
   * schema rather than authored for this operation. The named fields are
   * dropped from the named {@code $defs} schema's own {@code required}
   * list for this validation only (see
   * {@link OpenApiSpec#validationSchemaFor(JsonNode, Map)}); everything
   * else about the schema, including types, enums and any field that
   * <em>is</em> present, is still checked in full.
   *
   * <p><b>Empty, and worth keeping empty.</b> Both former entries were
   * defects in the spec rather than the backend, and both specs are now
   * fixed:
   *
   * <ul>
   *   <li>{@code PATCH /notifications/channels/{id}} took an
   *       {@code allOf} over the full {@code ChannelDraft}, so the
   *       contract demanded {@code kind, name, target, events} on a
   *       request whose entire purpose is sending one of them. It now
   *       takes {@code ChannelPatch}, every field optional, which is what
   *       "change a channel, or mute it" means.</li>
   *   <li>{@code POST /system/import} took {@code SettingsExport} — the
   *       export <em>response</em> schema — demanding five fields the
   *       importer never reads, so a hand-written import file was
   *       rejected by the contract and accepted by the box. It now takes
   *       a dedicated {@code ImportRequest}.</li>
   * </ul>
   */
  private static final Map<String, Map<String, Set<String>>> KNOWN_RELAXED_REQUIRED = Map.of();

  /**
   * The one entry here is <em>not</em> a spec defect, and the distinction
   * matters: the other registries existed because the contract was wrong,
   * and they are empty now that it has been corrected. This one exists
   * because a test deliberately sends an invalid request to prove the
   * backend survives it.
   *
   * <p>{@code ImportRequest.enabledPackages} is typed
   * {@code items: {type: string}} — strings are the contract — but
   * {@code SettingsPortabilityControllerIntegrationTest.ignores_junk_in_the_package_list_rather_than_writing_it}
   * deliberately sends {@code ["core", null, "", 42]} to pin that the
   * backend filters exactly this junk out rather than 400ing
   * ({@code if (o instanceof String s && !s.isBlank())} in
   * {@code SettingsPortabilityController.importSettings}). Non-string
   * entries are dropped from the named array field(s) before validation
   * here too — mirroring what the backend actually does with them, not
   * inventing new leniency.
   */
  private static final Map<String, Set<String>> KNOWN_JUNK_TOLERANT_ARRAY_FIELDS = Map.of(
      "POST /system/import", Set.of("enabledPackages")
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
    String key = method + " " + template;
    JsonNode instance = strip(mapper.readTree(body), KNOWN_UNDOCUMENTED_REQUEST_FIELDS.get(key));
    instance = dropNonStringArrayEntries(instance, KNOWN_JUNK_TOLERANT_ARRAY_FIELDS.get(key));
    Map<String, Set<String>> relax = KNOWN_RELAXED_REQUIRED.getOrDefault(key, Map.of());

    validate(schema.get(), instance, relax, body, "request body for " + method + " " + path);
  }

  private void checkResponse(String method, String path, MockHttpServletResponse response, int status) throws Exception {
    byte[] body = response.getContentAsByteArray();
    if (body == null || body.length == 0 || !isJson(response.getContentType())) return;

    Optional<JsonNode> schema = spec.responseSchema(method, path, status);
    if (schema.isEmpty()) return;

    String template = spec.templateFor(method, path).orElse(path);
    String key = method + " " + template + " -> " + status;
    JsonNode instance = strip(mapper.readTree(body), KNOWN_UNDOCUMENTED_RESPONSE_FIELDS.get(key));
    instance = nullToPlaceholder(instance, KNOWN_NULLABLE_RESPONSE_FIELDS.get(key));

    validate(schema.get(), instance, Map.of(), body, "response body for " + method + " " + path + " -> " + status);
  }

  private void validate(JsonNode rawSchema, JsonNode instance, Map<String, Set<String>> relaxRequired,
      byte[] originalBody, String description) {
    JsonNode validationSchema = spec.validationSchemaFor(rawSchema, relaxRequired);
    JsonSchema schema = FACTORY.getSchema(validationSchema);
    Set<ValidationMessage> problems = schema.validate(instance);
    if (problems.isEmpty()) return;

    throw new AssertionError(
        "openapi.yaml conformance failure — %s does not match its documented schema:\n%s\nactual body:\n%s\n"
            .formatted(description, formatProblems(problems), new String(originalBody, StandardCharsets.UTF_8)));
  }

  /**
   * Strip named fields before validation. Handles both a bare object body
   * ({@code GET /packages/{name}}) and a list body ({@code GET /packages}):
   * for a list, the same fields are stripped from every item, since a
   * "known undocumented field" on a record is a property of the record's
   * shape, not of which endpoint happens to be returning it wrapped in an
   * array or not.
   */
  private JsonNode strip(JsonNode instance, Set<String> fields) {
    if (fields == null) return instance;
    if (instance instanceof ObjectNode obj) {
      ObjectNode copy = obj.deepCopy();
      fields.forEach(copy::remove);
      return copy;
    }
    if (instance instanceof ArrayNode arr) {
      ArrayNode copy = mapper.createArrayNode();
      arr.forEach(item -> copy.add(strip(item, fields)));
      return copy;
    }
    return instance;
  }

  private JsonNode dropNonStringArrayEntries(JsonNode instance, Set<String> fields) {
    if (fields == null || !(instance instanceof ObjectNode obj)) return instance;
    ObjectNode copy = obj.deepCopy();
    for (String field : fields) {
      if (copy.path(field) instanceof ArrayNode array) {
        ArrayNode kept = mapper.createArrayNode();
        array.forEach(n -> {
          if (n.isTextual()) kept.add(n);
        });
        copy.set(field, kept);
      }
    }
    return copy;
  }

  private JsonNode nullToPlaceholder(JsonNode instance, Set<String> fields) {
    if (fields == null || !(instance instanceof ObjectNode obj)) return instance;
    ObjectNode copy = obj.deepCopy();
    for (String field : fields) {
      if (copy.path(field).isNull()) {
        copy.set(field, TextNode.valueOf("(known-nullable-placeholder)"));
      }
    }
    return copy;
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
