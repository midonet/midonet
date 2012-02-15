/*
 * @(#)NxmIOException        1.6 12/1/25
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.io.IOException;

public class NxmIOException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public NxmIOException() {
        super();
    }

    public NxmIOException(String message) {
        super(message);
    }

    public NxmIOException(String message, Throwable cause) {
        super(message, cause);
    }
}
