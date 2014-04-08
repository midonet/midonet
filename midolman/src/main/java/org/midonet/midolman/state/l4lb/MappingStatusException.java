/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.l4lb;

/**
 * An exception
 */
public class MappingStatusException extends Exception {
    private static final long serialVersionUID = 1L;

    public MappingStatusException() {
        super();
    }

    public MappingStatusException(String message) {
        super(message);
    }

    public MappingStatusException(String message, Throwable cause) {
        super(message, cause);
    }

    public MappingStatusException(Throwable cause) {
        super(cause);
    }
}
