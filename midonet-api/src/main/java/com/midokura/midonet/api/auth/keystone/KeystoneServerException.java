/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth.keystone;

import com.midokura.midonet.api.auth.AuthException;

/**
 * KeystoneServerException class for bad JSON response from Keystone.
 */
public class KeystoneServerException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a KeystoneServerException object with a message.
     *
     * @param message
     *            Error message.
     */
    public KeystoneServerException(String message) {
        super(message);
    }

    /**
     * Create a KeystoneServerException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public KeystoneServerException(Throwable e) {
        super(e);
    }

    /**
     * Create a KeystoneServerException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public KeystoneServerException(String message, Throwable e) {
        super(message, e);
    }
}
