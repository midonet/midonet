/*
 * Copyright 2013 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth.cloudstack;

import com.midokura.midonet.api.auth.AuthException;

/**
 * CloudStackClientException class for bad HTTP response from CloudStack..
 */
public class CloudStackClientException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a CloudStackClientException object with a message.
     *
     * @param message
     *            Error message.
     */
    public CloudStackClientException(String message) {
        super(message);
    }

    /**
     * Create a CloudStackClientException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public CloudStackClientException(Throwable e) {
        super(e);
    }

    /**
     * Create a CloudStackClientException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public CloudStackClientException(String message, Throwable e) {
        super(message, e);
    }
}
