package com.claims.approval.support;

/** Thrown instead of Runtime.halt(1): stops the settlement thread at the crash point, leaving the log as is. */
public class SimulatedCrash extends RuntimeException {

    public SimulatedCrash(String reason) {
        super(reason);
    }
}
