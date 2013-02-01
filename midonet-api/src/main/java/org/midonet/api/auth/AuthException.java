/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * AuthException class.
 */
public abstract class AuthException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Create an AuthException object with a message.
     *
     * @param message
     *            Error message.
     */
    public AuthException(String message) {
        super(message);
    }

    /**
     * Create an AuthException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public AuthException(Throwable e) {
        super(e);
    }

    /**
     * Create an AuthException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public AuthException(String message, Throwable e) {
        super(message, e);
    }
}
