/*
 * Copyright 2013 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth.cloudstack;

import com.midokura.midolman.mgmt.auth.AuthException;

/**
 * CloudStackServerException class for bad HTTP response from CloudStack..
 */
public class CloudStackServerException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a CloudStackServerException object with a message.
     *
     * @param message
     *            Error message.
     */
    public CloudStackServerException(String message) {
        super(message);
    }

    /**
     * Create a CloudStackServerException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public CloudStackServerException(Throwable e) {
        super(e);
    }

    /**
     * Create a CloudStackServerException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public CloudStackServerException(String message, Throwable e) {
        super(message, e);
    }
}
