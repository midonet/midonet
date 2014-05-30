/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.serialization;

/**
 * Exception class to indicate serialization error
 */
public class SerializationException extends Exception {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("rawtypes")
    private Class clazz;

    public <T> SerializationException(String msg, Throwable e,
                                        Class<T> clazz) {
        super(msg, e);
        this.clazz = clazz;
    }

    public <T> SerializationException(String msg, Throwable e) {
        super(msg, e);
    }

    @Override
    public String getMessage() {
        return this.clazz + " could not be (de)serialized. " +
                super.getMessage();
    }
}
