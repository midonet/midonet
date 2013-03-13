// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

public interface ThrottlingGuard {
    /** A new token goes into the system through the throttling guard and this
      * fact is recorded by the token guard.
      *
      * @return true if the token should be let through, false otherwise.
      */
    boolean tokenInIfAllowed();

    /** A new token enters the system through the throttling guard
      * unconditionally, and the token guard records it.
      */
    void tokenIn();

    /** Return a verdict for a new token, without altering internal state.
     *
     * @return true if the token should be let through, false otherwise.
     */
    boolean allowed();

    /** An token leaves the system throttling guard
      *
      * @return true if the token should be let through, false otherwise.
      */
    void tokenOut();

    /**
     * @return the number of tokens currently in the system.
     */
    int numTokens();
}
