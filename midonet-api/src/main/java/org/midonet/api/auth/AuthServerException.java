/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth;

/**
 * AuthServerException class for bad HTTP response from external auth system..
 */
public class AuthServerException extends AuthException {

    private static final long serialVersionUID = 1L;
    private int status;

    /**
     * Create a AuthServerException object with a message.
     *
     * @param message
     *            Error message.
     * @param status
     *            Server error code
     */
    public AuthServerException(String message, int status) {
        super(message);
        this.status = status;
    }

    /**
     * Create a AuthServerException object with no message and wrap a
     * Throwable object.
     *
     * @param e
     *            Throwable object
     * @param status
     *            Server error code
     */
    public AuthServerException(Throwable e, int status) {
        super(e);
        this.status = status;
    }

    /**
     * Create a AuthServerException object with a message and wrap a
     * Throwable object.
     *
     * @param message
     *            Error message.
     * @param e
     *            Throwable object
     * @param status
     *            Server error code
     */
    public AuthServerException(String message, Throwable e, int status) {
        super(message, e);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
