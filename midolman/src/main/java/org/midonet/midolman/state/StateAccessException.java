// Copyright 2011 Midokura Inc.

package org.midonet.midolman.state;

public class StateAccessException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public StateAccessException() {
        super();
    }

    public StateAccessException(String message) {
        super(message);
    }

    public StateAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public StateAccessException(Throwable cause) {
        super(cause);
    }
}
