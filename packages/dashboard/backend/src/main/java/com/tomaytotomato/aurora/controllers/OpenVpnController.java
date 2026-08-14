package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.OpenVpnClient;
import com.tomaytotomato.aurora.domain.OpenVpnConfig;
import com.tomaytotomato.aurora.services.OpenVpnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * {@code /api/vpn/openvpn} — the secondary, de-emphasised server. See
 * {@link OpenVpnService} for why this domain is deliberately smaller
 * than the WireGuard side.
 */
@RestController
@RequestMapping("/api/vpn/openvpn")
public class OpenVpnController {

  private final OpenVpnService openVpn;

  public OpenVpnController(OpenVpnService openVpn) {
    this.openVpn = openVpn;
  }

  @GetMapping("/config")
  public OpenVpnConfig getConfig() {
    return openVpn.getConfig();
  }

  @PutMapping("/config")
  public OpenVpnConfig updateConfig(@RequestBody OpenVpnConfig req) {
    if (req.protocol() == null || (!"udp".equals(req.protocol()) && !"tcp".equals(req.protocol()))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "protocol must be 'udp' or 'tcp', got: " + req.protocol());
    }
    return openVpn.updateConfig(req.enabled(), req.port(), req.protocol());
  }

  @GetMapping("/clients")
  public List<OpenVpnClient> listClients() {
    return openVpn.listClients();
  }

  @PostMapping("/clients")
  public ResponseEntity<Map<String, Object>> addClient(@RequestBody AddClientReq req) {
    if (req.name() == null || req.name().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    var created = openVpn.addClient(req.name());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("client", created.client(), "confText", created.confText()));
  }

  @DeleteMapping("/clients/{id}")
  public ResponseEntity<Void> deleteClient(@PathVariable String id) {
    try {
      openVpn.deleteClient(id);
      return ResponseEntity.noContent().build();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  public record AddClientReq(String name) {}
}
