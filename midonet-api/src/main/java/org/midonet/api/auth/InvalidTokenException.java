/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * AuthException class to represent no token.
 */
public class InvalidTokenException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a InvalidTokenException object with a message.
     *
     * @param message
     *            Error message.
     */
    public InvalidTokenException(String message) {
        super(message);
    }
}
