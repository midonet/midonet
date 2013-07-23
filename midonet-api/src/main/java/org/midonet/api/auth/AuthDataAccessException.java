/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * AuthException class for error while accessing database.
 */
public class AuthDataAccessException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a AuthDataAccessException object with a message.
     *
     * @param message
     *            Error message.
     */
    public AuthDataAccessException(String message) {
        super(message);
    }

    /**
     * Create a AuthDataAccessException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     */
    public AuthDataAccessException(Throwable e) {
        super(e);
    }

    /**
     * Create a AuthDataAccessException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     */
    public AuthDataAccessException(String message, Throwable e) {
        super(message, e);
    }
}
