/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

public class OpenFlowError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OpenFlowError(String reason) {
        super(reason);
    }

}
