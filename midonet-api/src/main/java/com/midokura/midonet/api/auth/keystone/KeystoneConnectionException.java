/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth.keystone;

import com.midokura.midonet.api.auth.AuthException;

/**
 * KeystoneConnectionException class for bad JSON response from Keystone.
 */
public class KeystoneConnectionException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a KeystoneConnectionException object with a message.
     *
     * @param message
     *            Error message.
     */
    public KeystoneConnectionException(String message) {
        super(message);
    }

    /**
     * Create a KeystoneConnectionException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public KeystoneConnectionException(Throwable e) {
        super(e);
    }

    /**
     * Create a KeystoneConnectionException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public KeystoneConnectionException(String message, Throwable e) {
        super(message, e);
    }
}
