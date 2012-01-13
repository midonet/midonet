/*
 * @(#)InvalidStateOperationException        1.6 11/09/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

public class InvalidStateOperationException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public InvalidStateOperationException() {
        super();
    }

    public InvalidStateOperationException(String message) {
        super(message);
    }

    public InvalidStateOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
