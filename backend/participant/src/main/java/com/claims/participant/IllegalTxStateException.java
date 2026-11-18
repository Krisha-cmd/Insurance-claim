package com.claims.participant;

/** A message that contradicts what already happened, e.g. Abort after COMMITTED (maps to gRPC FAILED_PRECONDITION). */
public class IllegalTxStateException extends RuntimeException {

    public IllegalTxStateException(String message) {
        super(message);
    }
}
