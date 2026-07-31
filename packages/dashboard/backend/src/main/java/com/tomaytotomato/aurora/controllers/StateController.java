package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.RepoState;
import com.tomaytotomato.aurora.services.StateFileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/state")
public class StateController {
  private final StateFileService state;

  public StateController(StateFileService state) {
    this.state = state;
  }

  @GetMapping
  public RepoState get() {
    return state.readState();
  }
}
