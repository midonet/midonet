/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

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
