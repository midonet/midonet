/*
 * @(#)ZkStateSerializationException        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

/**
 * Exception class to indicate serialization error for any ZK data.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
// TODO(pino, ryu): why not parameterize this class and use that parameter as
// the parameter to Class<> clazz?
public class ZkStateSerializationException extends StateAccessException {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("rawtypes")
    private Class clazz;

    public ZkStateSerializationException(String msg, Throwable e,
            @SuppressWarnings("rawtypes") Class clazz) {
        super(msg, e);
        this.clazz = clazz;
    }

    @Override
    public String getMessage() {
        return this.clazz + " could not be (de)serialized.";
    }
}
