/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.cloudstack;

import org.midonet.api.auth.AuthServerException;

/**
 * CloudStackServerException class for bad HTTP response from CloudStack..
 */
public class CloudStackServerException extends AuthServerException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a CloudStackServerException object with a message.
     *
     * @param message
     *            Error message.
     * @param status
     *            HTTP status code
     */
    public CloudStackServerException(String message, int status) {
        super(message, status);
    }

    /**
     * Create a CloudStackServerException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     * @param status
     *            HTTP status code
     */
    public CloudStackServerException(Throwable e, int status) {
        super(e, status);
    }

    /**
     * Create a CloudStackServerException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     * @param status
     *            HTTP status code
     */
    public CloudStackServerException(String message, Throwable e, int status) {
        super(message, e, status);
    }
}
