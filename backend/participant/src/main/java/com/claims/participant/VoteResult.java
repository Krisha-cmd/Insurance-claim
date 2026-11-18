package com.claims.participant;

/** The answer to CanCommit. A NO carries a reason the adjuster can read. */
public record VoteResult(boolean yes, String reason) {

    public static final VoteResult YES = new VoteResult(true, null);

    public static VoteResult no(String reason) {
        return new VoteResult(false, reason == null || reason.isBlank() ? "Voted NO" : reason);
    }
}
