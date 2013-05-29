/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone;

import org.midonet.api.auth.AuthException;

/**
 * KeystoneBadCredsException class.
 */
public class KeystoneBadCredsException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create an KeystoneBadCredsException object with a message.
     *
     * @param message
     *            Error message.
     */
    public KeystoneBadCredsException(String message) {
        super(message);
    }

    /**
     * Create an KeystoneBadCredsException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public KeystoneBadCredsException(Throwable e) {
        super(e);
    }

    /**
     * Create an KeystoneBadCredsException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public KeystoneBadCredsException(String message, Throwable e) {
        super(message, e);
    }
}
