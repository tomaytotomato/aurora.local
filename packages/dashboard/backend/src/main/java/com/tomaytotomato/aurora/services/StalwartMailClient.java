package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Thin client for Stalwart's JMAP management API — the supported way to
 * create a mail domain and the first mailbox on a box, so a fresh install
 * never lands the operator in Stalwart's setup wizard OR its raw admin UI.
 *
 * <p><b>Why JMAP, and why these exact shapes.</b> Stalwart v0.16 removed
 * the old REST {@code /api/principal} surface; principal management now
 * lives under JMAP ({@code POST /jmap/} with the {@code urn:stalwart:jmap}
 * capability). The object model here was verified against a live v0.16.19:
 * a domain is {@code x:Domain/set create}; an account is
 * {@code x:Account/set create} with {@code @type: User}, a bare
 * {@code name} (the email local-part — the address is derived as
 * {@code name@domain}), a {@code domainId}, and a {@code credentials} map
 * (NOT array) of {@code {"0": {"@type":"Password","secret": ...}}}.
 * Getting the credentials shape wrong returns {@code invalidPatch}; an
 * array returns the same. This is the shape the server actually accepts.
 *
 * <p><b>Auth.</b> HTTP Basic with the recovery-admin credential
 * ({@code admin:$STALWART_ADMIN_SECRET}), read from
 * {@link StalwartAdminService}. That credential already gates the admin
 * console behind Authelia; reusing it here means no second secret to
 * manage and no OAuth token dance (Stalwart accepts Basic on the JMAP
 * endpoint for the admin principal).
 *
 * <p><b>Idempotency.</b> Creating a domain that already exists returns
 * {@code primaryKeyViolation}; {@link #ensureDomain} treats that as
 * success (the domain is there, which is all the caller wanted).
 *
 * <p><b>Testability.</b> The single HTTP seam is {@link #jmapPost(String)};
 * tests override it to return canned JMAP responses, so every method's
 * request-building and response-parsing is exercised without a live
 * server.
 */
@Service
public class StalwartMailClient {

  private static final Logger log = LoggerFactory.getLogger(StalwartMailClient.class);

  private static final List<String> USING =
      List.of("urn:ietf:params:jmap:core", "urn:stalwart:jmap");

  private final StalwartAdminService admin;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * JMAP endpoint, reachable on aurora_net. Defaults to the in-network
   * container address; overridable for tests / non-default topologies.
   */
  private final String jmapUrl;

  public StalwartMailClient(StalwartAdminService admin,
                            @Value("${aurora.stalwart.jmap-url:http://stalwart:8080/jmap/}") String jmapUrl) {
    this.admin = admin;
    this.jmapUrl = jmapUrl;
  }

  // ─── public API ─────────────────────────────────────────────────────

  /**
   * Ensure a mail domain exists. Returns true when it created the domain,
   * false when it already existed. Throws {@link StalwartApiException} on
   * any other failure.
   */
  public boolean ensureDomain(String name) {
    String body = jmapCall("x:Domain/set",
        "{\"create\":{\"d1\":{\"@type\":\"Domain\",\"name\":" + quote(name) + "}}}");
    JsonNode resp = post(body);
    JsonNode args = methodArgs(resp);
    if (args.path("created").has("d1")) {
      log.info("stalwart: created mail domain {}", name);
      return true;
    }
    JsonNode notCreated = args.path("notCreated").path("d1");
    if ("primaryKeyViolation".equals(notCreated.path("type").asText())) {
      log.debug("stalwart: mail domain {} already exists", name);
      return false;
    }
    throw new StalwartApiException("could not create domain " + name + ": " + notCreated);
  }

  /**
   * Create a mailbox {@code localPart@domainName} with the given password.
   * The domain must already exist ({@link #ensureDomain}). Returns the
   * created account id. Throws {@link StalwartApiException} on failure
   * (including a weak password, which Stalwart rejects with
   * {@code invalidProperties}).
   */
  public String createMailbox(String localPart, String domainName, String password) {
    String domainId = domainIdFor(domainName);
    if (domainId == null) {
      throw new StalwartApiException("domain " + domainName + " does not exist; create it first");
    }
    String create = "{\"create\":{\"a1\":{\"@type\":\"User\","
        + "\"name\":" + quote(localPart) + ","
        + "\"domainId\":" + quote(domainId) + ","
        + "\"credentials\":{\"0\":{\"@type\":\"Password\",\"secret\":" + quote(password) + "}},"
        + "\"roles\":{\"@type\":\"User\"}}}}";
    JsonNode resp = post(jmapCall("x:Account/set", create));
    JsonNode args = methodArgs(resp);
    JsonNode created = args.path("created").path("a1");
    if (created.has("id")) {
      log.info("stalwart: created mailbox {}@{}", localPart, domainName);
      return created.path("id").asText();
    }
    JsonNode notCreated = args.path("notCreated").path("a1");
    throw new StalwartApiException("could not create mailbox " + localPart + "@" + domainName
        + ": " + notCreated);
  }

  /** Every domain id currently defined. */
  public List<String> listDomainIds() {
    JsonNode resp = post(jmapCall("x:Domain/query", "{}"));
    return ids(methodArgs(resp));
  }

  /**
   * The domain id for a domain name, or null when absent. Uses a filtered
   * query; falls back to matching {@code name} on the fetched objects
   * because the query filter shape is not load-bearing here (few domains).
   */
  public String domainIdFor(String name) {
    JsonNode resp = post(jmapCall("x:Domain/get", "{\"ids\":null}"));
    for (JsonNode d : methodArgs(resp).path("list")) {
      if (name.equalsIgnoreCase(d.path("name").asText())) {
        return d.path("id").asText();
      }
    }
    return null;
  }

  /** Whether the JMAP endpoint answers at all (used to gate provisioning). */
  public boolean reachable() {
    try {
      post(jmapCall("x:Domain/query", "{}"));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ─── HTTP seam (overridden in tests) ────────────────────────────────

  /**
   * POST a JMAP request body and return the raw response body. The one
   * place that touches the network; tests override this.
   */
  protected String jmapPost(String requestBody) {
    StalwartAdminService.AdminCredential cred = admin.currentCredential();
    String basic = Base64.getEncoder().encodeToString(
        (cred.username() + ":" + cred.secret()).getBytes(StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder(URI.create(jmapUrl))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Basic " + basic)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        throw new StalwartApiException("JMAP HTTP " + resp.statusCode() + ": " + resp.body());
      }
      return resp.body();
    } catch (StalwartApiException e) {
      throw e;
    } catch (Exception e) {
      throw new StalwartApiException("JMAP request failed: " + e.getMessage(), e);
    }
  }

  // ─── helpers ────────────────────────────────────────────────────────

  private JsonNode post(String body) {
    try {
      return mapper.readTree(jmapPost(body));
    } catch (StalwartApiException e) {
      throw e;
    } catch (Exception e) {
      throw new StalwartApiException("could not parse JMAP response: " + e.getMessage(), e);
    }
  }

  /** Args object of the first methodResponse: {@code [name, ARGS, callId]}. */
  private JsonNode methodArgs(JsonNode resp) {
    JsonNode responses = resp.path("methodResponses");
    if (!responses.isArray() || responses.isEmpty()) {
      throw new StalwartApiException("malformed JMAP response: " + resp);
    }
    JsonNode first = responses.get(0);
    if (!first.isArray() || first.size() < 2) {
      throw new StalwartApiException("malformed methodResponse: " + first);
    }
    if ("error".equals(first.get(0).asText())) {
      throw new StalwartApiException("JMAP method error: " + first.get(1));
    }
    return first.get(1);
  }

  private static List<String> ids(JsonNode args) {
    List<String> out = new ArrayList<>();
    for (JsonNode id : args.path("ids")) out.add(id.asText());
    return out;
  }

  private static String jmapCall(String method, String argsJson) {
    return "{\"using\":[\"urn:ietf:params:jmap:core\",\"urn:stalwart:jmap\"],"
        + "\"methodCalls\":[[" + quote(method) + "," + argsJson + ",\"c1\"]]}";
  }

  /** Minimal JSON string escaping for the values we interpolate. */
  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.append('"').toString();
  }

  /** Unchecked so callers that just want best-effort provisioning can ignore it. */
  public static class StalwartApiException extends RuntimeException {
    public StalwartApiException(String message) {
      super(message);
    }

    public StalwartApiException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
