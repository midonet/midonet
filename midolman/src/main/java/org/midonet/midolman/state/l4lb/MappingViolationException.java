/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.l4lb;

/**
 * An exception thrown when users try to associate a pool with a health monitor
 * even though the pool is already associated with another health monitor.
 */
public class MappingViolationException extends L4LBException {
    private static final long serialVersionUID = 1L;

    public MappingViolationException() {
        super();
    }

    public MappingViolationException(Throwable cause) {
        super(cause);
    }
}
