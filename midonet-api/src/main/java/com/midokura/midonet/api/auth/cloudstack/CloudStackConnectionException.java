/*
 * Copyright 2013 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth.cloudstack;

import com.midokura.midonet.api.auth.AuthException;

/**
 * CloudStackConnectionException class for bad JSON response from Keystone.
 */
public class CloudStackConnectionException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a CloudStackConnectionException object with a message.
     *
     * @param message
     *            Error message.
     */
    public CloudStackConnectionException(String message) {
        super(message);
    }

    /**
     * Create a CloudStackConnectionException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public CloudStackConnectionException(Throwable e) {
        super(e);
    }

    /**
     * Create a CloudStackConnectionException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public CloudStackConnectionException(String message, Throwable e) {
        super(message, e);
    }
}
