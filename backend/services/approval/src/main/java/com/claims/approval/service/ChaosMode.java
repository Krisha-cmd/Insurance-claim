package com.claims.approval.service;

/** Demo crash points for the coordinator, set through POST /internal/chaos. */
public enum ChaosMode {
    NONE,
    /** All votes are YES, but the coordinator dies before logging PRECOMMITTING. Recovery aborts. */
    CRASH_BEFORE_PRECOMMIT,
    /** PRECOMMITTING is logged and PreCommit acknowledged, then the coordinator dies. Recovery commits. */
    CRASH_AFTER_PRECOMMIT
}
