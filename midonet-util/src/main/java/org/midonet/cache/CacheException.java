/**
 * CacheException.java - Cache class related exception.
 *
 * Copyright (c) 2012 Midokura KK. All rights reserved.
 */

package org.midonet.cache;

public class CacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CacheException(String message) {
        super(message);
    }

    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
