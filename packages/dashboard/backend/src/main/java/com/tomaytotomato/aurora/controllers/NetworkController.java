package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.PackageNetwork;
import com.tomaytotomato.aurora.services.NetworkService;
import com.tomaytotomato.aurora.services.PackagesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * {@code /packages/{name}/network} — how one app's traffic leaves the box.
 * Previously had no handler at all, so every call 404'd regardless of the
 * package's state. See {@link NetworkService}.
 */
@RestController
@RequestMapping("/api/packages")
public class NetworkController {

  private final NetworkService network;
  private final PackagesService packages;

  public NetworkController(NetworkService network, PackagesService packages) {
    this.network = network;
    this.packages = packages;
  }

  @GetMapping("/{name}/network")
  public ResponseEntity<PackageNetwork> get(@PathVariable String name) {
    return network.get(name)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** The toggle itself isn't built yet — 404 for an unknown package, 409 otherwise. */
  @PutMapping("/{name}/network")
  public ResponseEntity<Void> setMode(@PathVariable String name, @RequestBody Map<String, Object> body) {
    if (packages.find(name).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such package on this box");
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, PackageNetwork.NOT_WIRED_UP_YET);
  }
}
