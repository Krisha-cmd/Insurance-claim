package com.claims.approval.service;

/**
 * Ends the process for a chaos crash. The real one calls {@code Runtime.halt(1)}: no shutdown hooks, no
 * finally blocks, no graceful drain, just like a killed pod. Tests replace it with one that only throws.
 */
@FunctionalInterface
public interface Halter {

    void halt(String reason);
}
