// Copyright 2013 Midokura Inc.

package org.midonet.util.throttling;

public interface ThrottlingCounter {
    /** A new token went into the system.
      *
      * @return The number of tokens after this token went in.
      */
    int tokenIn();

    /** An token just went out of the system.
      *
      * @return The number of tokens after this token went out.
      */
    int tokenOut();

    /** @return The number of tokens in the system
      */
    int get();
}
