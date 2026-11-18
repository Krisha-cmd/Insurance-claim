package com.claims.gateway;

/** What the simulated gateway does wrong. */
public enum FailureMode {
    /** Everything works. */
    NONE,
    /** /authorize answers 503 (Payment votes NO). */
    FAIL_AUTHORIZE,
    /** /capture answers 503 (Payment retries, then its sweep keeps trying). */
    FAIL_CAPTURE,
    /** Every call waits delayMs before answering (to demo timeouts). */
    SLOW
}
