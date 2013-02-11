/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone;

import org.midonet.api.auth.AuthException;

/**
 * KeystoneInvalidFormatException class for bad JSON response from Keystone.
 */
public class KeystoneInvalidFormatException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a KeystoneInvalidFormatException object with a message.
     *
     * @param message
     *            Error message.
     */
    public KeystoneInvalidFormatException(String message) {
        super(message);
    }

    /**
     * Create a KeystoneInvalidFormatException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public KeystoneInvalidFormatException(Throwable e) {
        super(e);
    }

    /**
     * Create a KeystoneInvalidFormatException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public KeystoneInvalidFormatException(String message, Throwable e) {
        super(message, e);
    }
}
