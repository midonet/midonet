/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.l4lb;

/**
 * An exception thrown when the mapping status in PENDING_* and users tried to
 * change the mapping before the health monitor changes it.
 */
public class MappingStatusException extends L4LBException {
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
