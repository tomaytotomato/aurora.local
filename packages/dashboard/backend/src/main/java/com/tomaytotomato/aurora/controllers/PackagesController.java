package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.services.PackagesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/packages")
public class PackagesController {

  private final PackagesService packages;

  public PackagesController(PackagesService packages) {
    this.packages = packages;
  }

  @GetMapping
  public List<Package> list() {
    return packages.list();
  }

  @GetMapping("/{name}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable String name) {
    return packages.find(name)
        .map(pkg -> {
          Map<String, Object> body = new HashMap<>();
          body.put("package", pkg);
          body.put("env_example", packages.readEnvExample(name).orElse(""));
          return ResponseEntity.ok(body);
        })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
