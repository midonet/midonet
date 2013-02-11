/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone;

import org.midonet.api.auth.AuthServerException;

/**
 * KeystoneServerException class for bad JSON response from Keystone.
 */
public class KeystoneServerException extends AuthServerException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a KeystoneServerException object with a message.
     *
     * @param message
     *            Error message.
     * @param status
     *            HTTP status code
     */
    public KeystoneServerException(String message, int status) {
        super(message, status);
    }

    /**
     * Create a KeystoneServerException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     * @param status
     *            HTTP status code
     */
    public KeystoneServerException(Throwable e, int status) {
        super(e, status);
    }

    /**
     * Create a KeystoneServerException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     * @param status
     *            HTTP status code
     */
    public KeystoneServerException(String message, Throwable e, int status) {
        super(message, e, status);
    }
}
