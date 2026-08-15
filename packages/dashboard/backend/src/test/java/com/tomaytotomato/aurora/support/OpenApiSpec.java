package com.tomaytotomato.aurora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A queryable, ref-resolved view of {@code openapi.yaml}, built once and
 * shared by every test that validates a body against the spec.
 *
 * <p>The spec is loaded with SnakeYAML (already a test dependency) and
 * converted to a Jackson tree so it can be fed straight to a JSON Schema
 * validator. Every {@code $ref: '#/components/schemas/X'} anywhere in the
 * document is rewritten to {@code '#/$defs/X'} up front, so any subschema
 * pulled out of the tree stays resolvable on its own — the alternative,
 * keeping the whole document as the schema root for every validation
 * call, would work too, but would mean re-parsing the 2,800-line spec
 * into a fresh {@code JsonSchema} per assertion instead of once per JVM.
 */
final class OpenApiSpec {

  private static final Path SPEC = Path.of("../openapi.yaml");
  private static final OpenApiSpec INSTANCE = new OpenApiSpec();

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonNode defs;
  private final List<Operation> operations;

  private record Operation(PathPattern pattern, Map<String, JsonNode> byVerb) {
  }

  static OpenApiSpec instance() {
    return INSTANCE;
  }

  private OpenApiSpec() {
    try {
      JsonNode doc = mapper.valueToTree(new Yaml().load(Files.readString(SPEC, StandardCharsets.UTF_8)));
      rewriteSchemaRefs(doc);

      this.defs = doc.path("components").path("schemas").deepCopy();

      PathPatternParser parser = new PathPatternParser();
      List<Operation> parsed = new ArrayList<>();
      Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").fields();
      while (paths.hasNext()) {
        Map.Entry<String, JsonNode> entry = paths.next();
        Map<String, JsonNode> byVerb = new java.util.HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> verbs = entry.getValue().fields();
        while (verbs.hasNext()) {
          Map.Entry<String, JsonNode> verb = verbs.next();
          byVerb.put(verb.getKey().toLowerCase(Locale.ROOT), verb.getValue());
        }
        parsed.add(new Operation(parser.parse(entry.getKey()), byVerb));
      }
      // Literal paths before templated ones, so an exact match always wins
      // over a coincidental template match.
      parsed.sort((a, b) -> {
        boolean at = a.pattern().getPatternString().contains("{");
        boolean bt = b.pattern().getPatternString().contains("{");
        if (at == bt) return 0;
        return at ? 1 : -1;
      });
      this.operations = List.copyOf(parsed);
    } catch (IOException e) {
      throw new UncheckedIOException("could not load " + SPEC.toAbsolutePath(), e);
    }
  }

  /** The spec's requestBody JSON schema for {@code method path}, if documented. */
  Optional<JsonNode> requestSchema(String method, String path) {
    return operation(method, path).map(op -> op.path("requestBody").path("content")
            .path("application/json").path("schema"))
        .filter(n -> !n.isMissingNode());
  }

  /** The spec's response JSON schema for {@code method path -> status}, if documented. */
  Optional<JsonNode> responseSchema(String method, String path, int status) {
    return operation(method, path).map(op -> op.path("responses").path(String.valueOf(status))
            .path("content").path("application/json").path("schema"))
        .filter(n -> !n.isMissingNode());
  }

  private Optional<JsonNode> operation(String method, String path) {
    PathContainer container = PathContainer.parsePath(path);
    for (Operation op : operations) {
      if (op.pattern().matches(container)) {
        JsonNode found = op.byVerb().get(method.toLowerCase(Locale.ROOT));
        if (found != null) return Optional.of(found);
      }
    }
    return Optional.empty();
  }

  /**
   * The spec's own path template for a concrete request path (e.g.
   * {@code /packages/nginx} → {@code /packages/{name}}), so a caller can
   * key things (a known-gaps registry, a log line) by the operation
   * rather than by whichever concrete id happened to be in one test.
   */
  Optional<String> templateFor(String method, String path) {
    PathContainer container = PathContainer.parsePath(path);
    for (Operation op : operations) {
      if (op.pattern().matches(container) && op.byVerb().containsKey(method.toLowerCase(Locale.ROOT))) {
        return Optional.of(op.pattern().getPatternString());
      }
    }
    return Optional.empty();
  }

  /**
   * Wraps {@code rawSchema} (as found in the spec, refs already pointing
   * at {@code $defs}) into a self-contained JSON Schema document ready
   * for {@code JsonSchemaFactory.getSchema(...)}.
   *
   * <p>Also adds "no properties beyond what the spec describes" at the
   * outermost object shape (or, for a list response, at the shape of each
   * item) — see the class Javadoc on {@link OpenApiConformance} for why
   * that is a deliberate strictness decision rather than the JSON Schema
   * default. {@code unevaluatedProperties}, not {@code additionalProperties},
   * because several response schemas (e.g. {@code PackageDetail}) are an
   * {@code allOf} of a shared base schema plus extra fields:
   * {@code additionalProperties: false} on each branch independently
   * would have every branch reject the other branch's fields;
   * {@code unevaluatedProperties} is evaluated once the whole {@code allOf}
   * has been considered, which is the only one of the two that actually
   * composes.
   *
   * <p>Deliberately not recursive beyond one level (root, or list item):
   * going further would mean injecting the keyword into schemas that are
   * {@code $ref}-shared between a standalone use and a nested use inside
   * another {@code allOf} — the exact trap described above, just one
   * level deeper. Nested objects (e.g. {@code PackageDetail.backup}) still
   * get full type/required/enum checking; they just don't get the extra
   * "nothing undocumented" check at that depth.
   */
  JsonNode validationSchemaFor(JsonNode rawSchema) {
    return validationSchemaFor(rawSchema, Map.of());
  }

  /**
   * As {@link #validationSchemaFor(JsonNode)}, but first drops the named
   * fields from the {@code required} list of specific named {@code $defs}
   * entries (e.g. {@code {"ChannelDraft": {"kind", "name"}}}). Used only
   * for the handful of catalogued, reported gaps in
   * {@link OpenApiConformance#KNOWN_RELAXED_REQUIRED} where the spec's
   * schema is a stricter fit than the operation actually is — never for
   * new checking, always scoped to one named schema at a time so it
   * cannot quietly loosen an unrelated schema that happens to require a
   * field with the same name.
   */
  JsonNode validationSchemaFor(JsonNode rawSchema, Map<String, Set<String>> relaxRequiredBySchemaName) {
    JsonNode defsForThisCall = defs;
    if (!relaxRequiredBySchemaName.isEmpty()) {
      ObjectNode defsCopy = defs.deepCopy();
      relaxRequiredBySchemaName.forEach((schemaName, fields) -> {
        JsonNode target = defsCopy.get(schemaName);
        if (target instanceof ObjectNode obj && obj.get("required") instanceof ArrayNode required) {
          ArrayNode kept = mapper.createArrayNode();
          required.forEach(n -> {
            if (!fields.contains(n.asText())) kept.add(n);
          });
          obj.set("required", kept);
        }
      });
      defsForThisCall = defsCopy;
    }
    return validationSchemaFor(rawSchema, defsForThisCall);
  }

  private JsonNode validationSchemaFor(JsonNode rawSchema, JsonNode defsForThisCall) {
    ObjectNode root = mapper.createObjectNode();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.set("$defs", defsForThisCall);

    JsonNode resolved = resolveOneRefHop(rawSchema, defsForThisCall);
    JsonNode items = resolved.path("items");
    boolean isArray = isArrayTyped(resolved) || !items.isMissingNode();

    ArrayNode allOf = root.putArray("allOf");
    allOf.add(rawSchema.deepCopy());

    if (isArray) {
      if (!items.isMissingNode() && !hasOwnAdditionalProperties(resolveOneRefHop(items, defsForThisCall))) {
        ObjectNode itemsConstraint = mapper.createObjectNode();
        ObjectNode itemsShape = mapper.createObjectNode();
        ArrayNode itemsAllOf = itemsShape.putArray("allOf");
        itemsAllOf.add(items.deepCopy());
        itemsShape.put("unevaluatedProperties", false);
        itemsConstraint.set("items", itemsShape);
        allOf.add(itemsConstraint);
      }
    } else if (hasKnownObjectShape(resolved) && !hasOwnAdditionalProperties(resolved)) {
      root.put("unevaluatedProperties", false);
    }

    return root;
  }

  // ------------------------------------------------------------------

  /** Follows a single, bare {@code {"$ref": "..."}} hop into {@code defsForThisCall}, for shape sniffing only. */
  private JsonNode resolveOneRefHop(JsonNode node, JsonNode defsForThisCall) {
    JsonNode current = node;
    int hops = 0;
    while (current.has("$ref") && current.size() == 1 && hops++ < 10) {
      String ref = current.get("$ref").asText();
      String name = ref.substring(ref.lastIndexOf('/') + 1);
      JsonNode next = defsForThisCall.get(name);
      if (next == null) break;
      current = next;
    }
    return current;
  }

  private static boolean isArrayTyped(JsonNode schema) {
    JsonNode type = schema.path("type");
    if (type.isTextual()) return "array".equals(type.asText());
    if (type.isArray()) {
      for (JsonNode t : type) {
        if ("array".equals(t.asText())) return true;
      }
    }
    return false;
  }

  private static boolean hasOwnAdditionalProperties(JsonNode schema) {
    return schema.has("additionalProperties");
  }

  private static boolean hasKnownObjectShape(JsonNode schema) {
    return schema.has("properties") || schema.has("allOf");
  }

  /** Rewrites every {@code #/components/schemas/X} ref anywhere in the tree to {@code #/$defs/X}. */
  private static void rewriteSchemaRefs(JsonNode node) {
    if (node.isObject()) {
      ObjectNode obj = (ObjectNode) node;
      JsonNode ref = obj.get("$ref");
      if (ref != null && ref.isTextual() && ref.asText().startsWith("#/components/schemas/")) {
        String name = ref.asText().substring("#/components/schemas/".length());
        obj.put("$ref", "#/$defs/" + name);
      }
      obj.fields().forEachRemaining(e -> rewriteSchemaRefs(e.getValue()));
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        rewriteSchemaRefs(child);
      }
    }
  }
}
