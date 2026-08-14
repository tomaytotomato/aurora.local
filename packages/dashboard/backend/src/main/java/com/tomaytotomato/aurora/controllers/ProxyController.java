package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.ProxyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/proxy} — exposing a container at a friendly address without
 * hand-editing {@code caddy.snippet}. See {@link ProxyService} for what
 * "managed" vs "hand-added" means and how the file gets written.
 */
@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

  private final ProxyService proxy;

  public ProxyController(ProxyService proxy) {
    this.proxy = proxy;
  }

  @GetMapping("/routes")
  public List<Map<String, Object>> routes() {
    return proxy.routes();
  }

  @PostMapping("/routes")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(@RequestBody Map<String, Object> body) {
    return proxy.create(str(body, "subdomain"), str(body, "target"));
  }

  @DeleteMapping("/routes/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    proxy.delete(id);
  }

  @GetMapping("/targets")
  public List<Map<String, Object>> targets() {
    return proxy.targets();
  }

  @PostMapping("/preview")
  public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
    return proxy.preview(str(body, "subdomain"), str(body, "target"));
  }

  private static String str(Map<String, Object> body, String key) {
    Object v = body == null ? null : body.get(key);
    return v == null ? null : v.toString();
  }
}
