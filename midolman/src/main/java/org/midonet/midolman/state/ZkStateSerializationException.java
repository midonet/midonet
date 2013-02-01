/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

/**
 * Exception class to indicate serialization error for any ZK data.
 */
public class ZkStateSerializationException extends StateAccessException {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("rawtypes")
    private Class clazz;

    public <T> ZkStateSerializationException(String msg, Throwable e,
            Class<T> clazz) {
        super(msg, e);
        this.clazz = clazz;
    }

    public <T> ZkStateSerializationException(String msg, Throwable e) {
        super(msg, e);
    }

    @Override
    public String getMessage() {
        return this.clazz + " could not be (de)serialized.";
    }
}
