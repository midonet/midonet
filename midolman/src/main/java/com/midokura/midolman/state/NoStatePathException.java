/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

public class NoStatePathException extends StateAccessException {

    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public NoStatePathException() {
        super();
    }

    public NoStatePathException(String message) {
        super(message);
    }

    public NoStatePathException(String message, Throwable cause) {
        super(message, cause);
    }

}
