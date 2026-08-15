package com.tomaytotomato.aurora.services;

/**
 * Thrown by {@link CommandRunner#stream} when its {@link CommandRunner.CancelToken}
 * was cancelled while the process was running.
 *
 * <p>By the time this is thrown the process and any descendants it
 * spawned have already been killed. Distinct from {@link CommandTimeoutException}
 * so a caller can tell "the operator asked for this to stop" apart from
 * "this stopped responding on its own" — the two deserve different copy.
 */
public class CommandCancelledException extends RuntimeException {

  public CommandCancelledException(String message) {
    super(message);
  }
}
