package com.tomaytotomato.aurora.services;

/**
 * Thrown by {@link CommandRunner#stream} when the process produces no
 * output for longer than the implementation's inactivity ceiling.
 *
 * <p>By the time this is thrown the process and any descendants it
 * spawned have already been killed — a caller does not need to do
 * anything further to stop the work, only to decide what to tell the
 * operator. This is deliberately a distinct type from a generic
 * {@link java.io.IOException} or non-zero exit: "this wedged and Aurora
 * gave up on it" is a different fact from "this ran and failed", and
 * callers that want to say so (rather than showing a raw stack trace or a
 * guessed failure classification) need to be able to tell them apart.
 */
public class CommandTimeoutException extends RuntimeException {

  public CommandTimeoutException(String message) {
    super(message);
  }
}
