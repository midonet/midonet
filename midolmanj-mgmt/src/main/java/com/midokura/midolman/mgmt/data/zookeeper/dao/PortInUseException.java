/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import com.midokura.midolman.state.InvalidStateOperationException;

/**
 * Exception thrown when an illegal operation is done on a port that is being
 * used.
 */
public class PortInUseException extends InvalidStateOperationException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param msg
     *            Error message.
     */
    public PortInUseException(String msg) {
        super(msg);
    }

}
