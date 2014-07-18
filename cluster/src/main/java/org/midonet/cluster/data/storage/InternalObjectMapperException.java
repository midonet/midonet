/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

/**
 * Catch-all wrapper for any non-runtime exception occurring in the
 * ZookeeperObjectMapper or below that is not due to caller error. This
 * indicates either data corruption or a bug. See cause for details.
 */
public class InternalObjectMapperException extends RuntimeException {
    private static final long serialVersionUID = -9030640056326251845L;

    public InternalObjectMapperException(Throwable cause) {
        super(cause);
    }

    public InternalObjectMapperException(String message, Throwable cause) {
        super(message, cause);
    }
}
