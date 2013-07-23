// Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
package org.midonet.midolman.state;

public class VlanPathExistsException extends StateAccessException {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public VlanPathExistsException() {
        super();
    }

    public VlanPathExistsException(String message) {
        super(message);
    }

    public VlanPathExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
