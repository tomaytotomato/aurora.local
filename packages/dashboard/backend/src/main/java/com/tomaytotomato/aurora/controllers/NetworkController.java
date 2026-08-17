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
 * See {@link NetworkService} for what "how" means today versus what
 * docs/SPLIT_TUNNEL.md still lists as planned.
 *
 * <p>Real-box bug this closes: before this controller existed, every call
 * to {@code GET /packages/{name}/network} 404'd (no handler at all, not a
 * "no data for this package yet" 404), and the frontend's generic 404 copy
 * read as "this app's networking is not on this box any more" — flatly
 * contradicting a page that, one screen up, was showing the same app as
 * enabled and running. The endpoint now actually answers.
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

  /**
   * Moving an app on or off the gateway is still docs/SPLIT_TUNNEL.md's
   * "Planned" section — no compose rewrite, port move, or Caddy vhost
   * update exists yet. 404 if the package itself doesn't exist (consistent
   * with every other {@code /packages/{name}/*} verb); 409 otherwise,
   * matching openapi.yaml's documented "cannot be changed" response rather
   * than a bare unimplemented-endpoint 404 or 501.
   */
  @PutMapping("/{name}/network")
  public ResponseEntity<Void> setMode(@PathVariable String name, @RequestBody Map<String, Object> body) {
    if (packages.find(name).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such package on this box");
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, PackageNetwork.NOT_WIRED_UP_YET);
  }
}
