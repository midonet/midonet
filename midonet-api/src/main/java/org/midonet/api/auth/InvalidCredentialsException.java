/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * AuthException class to represent no token.
 */
public class InvalidCredentialsException extends AuthException {

    private static final long serialVersionUID = 1L;

    /**
     * Create a InvalidCredentialsException object with a message.
     *
     * @param message
     *            Error message.
     */
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
