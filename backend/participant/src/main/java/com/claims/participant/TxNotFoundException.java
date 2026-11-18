package com.claims.participant;

import java.util.UUID;

/** PreCommit/DoCommit for a tx this participant never saw (maps to gRPC NOT_FOUND). */
public class TxNotFoundException extends RuntimeException {

    public TxNotFoundException(UUID txId) {
        super("Unknown settlement " + txId);
    }
}
