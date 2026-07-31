package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final SystemService system;

  public SystemController(SystemService system) {
    this.system = system;
  }

  @GetMapping
  public Map<String, Object> get() {
    return system.snapshot();
  }
}
