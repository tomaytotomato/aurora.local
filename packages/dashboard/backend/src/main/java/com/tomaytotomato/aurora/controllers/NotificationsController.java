package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.NotificationsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/notifications} — ntfy, Discord webhook, or a generic
 * webhook, with per-event toggles and an honest test-send result. See
 * {@link NotificationsService} for what "honest" means here.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationsController {

  private final NotificationsService notifications;

  public NotificationsController(NotificationsService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/channels")
  public List<Map<String, Object>> channels() {
    return notifications.list();
  }

  @PostMapping("/channels")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(@RequestBody Map<String, Object> draft) {
    return notifications.create(draft);
  }

  @PatchMapping("/channels/{id}")
  public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
    return notifications.update(id, patch)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel."));
  }

  @DeleteMapping("/channels/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    notifications.delete(id);
  }

  @PostMapping("/channels/{id}/test")
  public Map<String, Object> test(@PathVariable String id) {
    return notifications.test(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel."));
  }

  @GetMapping("/history")
  public List<Map<String, Object>> history() {
    return notifications.history();
  }
}
