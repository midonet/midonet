/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.auth.keystone;

import com.midokura.midonet.api.auth.AuthException;

/**
 * KeystoneInvalidJsonException class for bad JSON response from Keystone.
 */
public class KeystoneInvalidJsonException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a KeystoneInvalidJsonException object with a message.
     *
     * @param message
     *            Error message.
     */
    public KeystoneInvalidJsonException(String message) {
        super(message);
    }

    /**
     * Create a KeystoneInvalidJsonException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public KeystoneInvalidJsonException(Throwable e) {
        super(e);
    }

    /**
     * Create a KeystoneInvalidJsonException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public KeystoneInvalidJsonException(String message, Throwable e) {
        super(message, e);
    }
}
