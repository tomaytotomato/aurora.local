package com.tomaytotomato.aurora.controllers;

import com.github.dockerjava.api.model.Container;
import com.tomaytotomato.aurora.services.DockerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/containers")
public class ContainersController {

  private final DockerService docker;

  public ContainersController(DockerService docker) {
    this.docker = docker;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Container c : docker.listProjectContainers()) {
      out.add(Map.of(
          "id", c.getId(),
          "names", c.getNames() == null ? new String[0] : c.getNames(),
          "image", c.getImage(),
          "state", c.getState(),
          "status", c.getStatus(),
          "service", DockerService.composeService(c) == null ? "" : DockerService.composeService(c),
          "labels", c.getLabels() == null ? Map.of() : c.getLabels()
      ));
    }
    return out;
  }
}
