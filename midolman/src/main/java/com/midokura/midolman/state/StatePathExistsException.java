/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

public class StatePathExistsException extends StateAccessException {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public StatePathExistsException() {
        super();
    }

    public StatePathExistsException(String message) {
        super(message);
    }

    public StatePathExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
