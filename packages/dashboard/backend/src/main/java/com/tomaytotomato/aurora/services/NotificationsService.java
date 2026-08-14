package com.tomaytotomato.aurora.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomaytotomato.aurora.persistence.NotificationChannelRepo;
import com.tomaytotomato.aurora.persistence.NotificationDeliveryRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /api/notifications} — ntfy, Discord webhook, or a generic
 * webhook, each firing on a chosen set of events.
 *
 * <p>Aurora already detects service failures, security findings, backup
 * failures and drive trouble; until this domain existed it told nobody.
 * Email is deliberately not a channel kind — a working MTA on a home box
 * is a multi-hour yak shave that ends in a spam folder (see
 * openapi.yaml's comment on the channels path).
 *
 * <p>A "test send" is not a ping the frontend simulates — it makes the
 * same real outbound HTTP call a live event would, and reports what
 * actually happened. A channel that has silently stopped working is
 * worse than no channel, because you believe you are covered.
 */
@Service
public class NotificationsService {

  private static final Logger log = LoggerFactory.getLogger(NotificationsService.class);

  static final Set<String> VALID_KINDS = Set.of("ntfy", "discord", "webhook");
  static final Set<String> VALID_EVENTS = Set.of(
      "service-down", "security-finding", "backup-failed", "backup-stale",
      "disk-health", "update-available", "job-failed");

  /** Test-send subject and event — matches the frontend fixture's own test-send fixture. */
  static final String TEST_EVENT = "job-failed";
  static final String TEST_SUBJECT = "Test message from Aurora";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final NotificationChannelRepo channels;
  private final NotificationDeliveryRepo deliveries;
  private final HttpClient http;

  public NotificationsService(NotificationChannelRepo channels, NotificationDeliveryRepo deliveries) {
    this(channels, deliveries, HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .build());
  }

  /** Test-visible constructor allowing an injected HttpClient. */
  NotificationsService(NotificationChannelRepo channels, NotificationDeliveryRepo deliveries, HttpClient http) {
    this.channels = channels;
    this.deliveries = deliveries;
    this.http = http;
  }

  // ─── reads ────────────────────────────────────────────────────────────

  public List<Map<String, Object>> list() {
    return channels.findAll().stream().map(NotificationsService::channelJson).toList();
  }

  public List<Map<String, Object>> history() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var d : deliveries.findAll()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", d.id());
      m.put("channelId", d.channelId());
      m.put("event", d.event());
      m.put("subject", d.subject());
      m.put("sentAt", d.sentAt());
      m.put("result", d.result());
      m.put("error", d.error());
      out.add(m);
    }
    return out;
  }

  // ─── channel CRUD ─────────────────────────────────────────────────────

  public Map<String, Object> create(Map<String, Object> draft) {
    String kind = requireKind(str(draft, "kind"));
    String name = requireNonBlank(str(draft, "name"), "name");
    String target = requireUrl(str(draft, "target"));
    List<String> events = requireEvents(listOf(draft, "events"));

    var row = channels.insert("chan-" + UUID.randomUUID(), kind, name, target, events);
    return channelJson(row);
  }

  /** Empty when {@code id} names no channel. Any of the patch fields may be absent — a mute-only patch sends just {@code enabled}. */
  public java.util.Optional<Map<String, Object>> update(String id, Map<String, Object> patch) {
    String kind = patch.containsKey("kind") ? requireKind(str(patch, "kind")) : null;
    String name = patch.containsKey("name") ? requireNonBlank(str(patch, "name"), "name") : null;
    String target = patch.containsKey("target") ? requireUrl(str(patch, "target")) : null;
    List<String> events = patch.containsKey("events") ? requireEvents(listOf(patch, "events")) : null;
    Boolean enabled = patch.get("enabled") instanceof Boolean b ? b : null;

    return channels.update(id, kind, name, target, events, enabled).map(NotificationsService::channelJson);
  }

  public boolean delete(String id) {
    return channels.delete(id);
  }

  // ─── test send ────────────────────────────────────────────────────────

  public java.util.Optional<Map<String, Object>> test(String id) {
    var channel = channels.findById(id).orElse(null);
    if (channel == null) return java.util.Optional.empty();

    Outcome outcome = send(channel, TEST_EVENT, TEST_SUBJECT, null);
    if ("failed".equals(outcome.result())) {
      log.debug("test-send to channel {} ({}) failed: {}", id, channel.kind(), outcome.error());
    }
    String sentAt = Instant.now().toString();
    channels.recordTestResult(id, sentAt, outcome.result(), outcome.error());
    deliveries.insert("del-" + UUID.randomUUID(), id, TEST_EVENT, TEST_SUBJECT, sentAt, outcome.result(), outcome.error());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("result", outcome.result());
    out.put("error", outcome.error());
    return java.util.Optional.of(out);
  }

  // ─── outbound HTTP ────────────────────────────────────────────────────

  private record Outcome(String result, String error) {
    static Outcome ok() { return new Outcome("ok", null); }
    static Outcome failed(String error) { return new Outcome("failed", error); }
  }

  private Outcome send(NotificationChannelRepo.Row channel, String event, String subject, String detail) {
    URI uri;
    try {
      uri = URI.create(channel.target());
    } catch (IllegalArgumentException e) {
      return Outcome.failed("The channel's target is not a usable URL.");
    }
    String host = uri.getHost() == null ? channel.target() : uri.getHost();

    HttpRequest request;
    try {
      request = buildRequest(channel.kind(), uri, event, subject, detail);
    } catch (RuntimeException e) {
      return Outcome.failed("Could not build the request: " + e.getMessage());
    }

    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return Outcome.ok();
      }
      return Outcome.failed(describeFailure(status, response.body()));
    } catch (HttpTimeoutException e) {
      return Outcome.failed("Timed out waiting for a response from " + host + ".");
    } catch (ConnectException e) {
      return Outcome.failed("Could not reach " + host + ": connection refused.");
    } catch (UnknownHostException e) {
      return Outcome.failed("Could not reach " + host + ": unknown host.");
    } catch (java.io.IOException e) {
      return Outcome.failed("Could not reach " + host + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.failed("Interrupted while waiting for " + host + ".");
    }
  }

  private static HttpRequest buildRequest(String kind, URI uri, String event, String subject, String detail) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
    return switch (kind) {
      case "ntfy" -> builder
          .header("Title", "Aurora")
          .header("Content-Type", "text/plain; charset=utf-8")
          .POST(HttpRequest.BodyPublishers.ofString(detail == null ? subject : subject + "\n" + detail))
          .build();
      case "discord" -> builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(discordBody(subject, detail)))
          .build();
      default -> builder // "webhook" and anything unrecognised fall back to the generic shape
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(webhookBody(event, subject, detail)))
          .build();
    };
  }

  private static String discordBody(String subject, String detail) {
    String content = detail == null ? subject : subject + "\n" + detail;
    return "{\"content\":" + JSON.valueToTree(content) + "}";
  }

  private static String webhookBody(String event, String subject, String detail) {
    var node = JSON.createObjectNode();
    node.put("event", event);
    node.put("subject", subject);
    node.put("detail", detail);
    node.put("timestamp", Instant.now().toString());
    return node.toString();
  }

  /**
   * Turn a non-2xx response into copy worth reading. Discord's own API
   * (and plenty of webhook receivers) answer failures with a small JSON
   * body carrying a human-readable {@code message} or {@code error}
   * field — when one is there, lead with it (e.g. "404 Unknown Webhook",
   * exactly what a deleted Discord webhook returns). Falls back to the
   * bare status when the body is empty, not JSON, or shaped differently.
   */
  static String describeFailure(int status, String body) {
    String detail = extractMessage(body);
    return detail != null ? status + " " + detail : "Request failed with HTTP " + status + ".";
  }

  private static String extractMessage(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      JsonNode node = JSON.readTree(body);
      if (node.hasNonNull("message")) return node.get("message").asText();
      if (node.hasNonNull("error")) return node.get("error").asText();
    } catch (Exception ignore) {
      // Not JSON, or not an object — no message worth extracting.
    }
    return null;
  }

  // ─── json + validation helpers ────────────────────────────────────────

  private static Map<String, Object> channelJson(NotificationChannelRepo.Row row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", row.id());
    m.put("kind", row.kind());
    m.put("name", row.name());
    m.put("target", row.target());
    m.put("events", row.events());
    m.put("enabled", row.enabled());
    m.put("lastSentAt", row.lastSentAt());
    m.put("lastResult", row.lastResult());
    m.put("lastError", row.lastError());
    return m;
  }

  private static String str(Map<String, Object> body, String key) {
    Object v = body == null ? null : body.get(key);
    return v == null ? null : v.toString();
  }

  @SuppressWarnings("unchecked")
  private static List<String> listOf(Map<String, Object> body, String key) {
    Object v = body == null ? null : body.get(key);
    if (!(v instanceof List<?> list)) return null;
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o != null) out.add(o.toString());
    }
    return out;
  }

  private static String requireKind(String kind) {
    if (kind == null || !VALID_KINDS.contains(kind)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "kind must be one of " + VALID_KINDS + ".");
    }
    return kind;
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required.");
    }
    return value;
  }

  private static String requireUrl(String target) {
    requireNonBlank(target, "target");
    if (!target.matches("(?i)^https?://.+")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "target must be a full URL starting with http:// or https://.");
    }
    return target;
  }

  private static List<String> requireEvents(List<String> events) {
    if (events == null || events.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one event.");
    }
    for (String e : events) {
      if (!VALID_EVENTS.contains(e)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown event: " + e);
      }
    }
    return events;
  }
}
