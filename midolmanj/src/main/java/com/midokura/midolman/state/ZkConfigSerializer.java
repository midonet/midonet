/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

import java.io.IOException;

import com.midokura.midolman.util.Serializer;

/**
 * Serializer class for ZK configs.
 */
public class ZkConfigSerializer {

    private final Serializer serializer;

    public ZkConfigSerializer(Serializer serializer) {
        this.serializer = serializer;
    }

    public <T, Derived extends T> Derived deserialize(byte[] data, Class<T> clazz)
            throws ZkStateSerializationException {
        try {
            return serializer.bytesToObj(data, clazz);
        } catch (IOException e) {
            throw new ZkStateSerializationException(
                    "Could not deserialize the class.", e, clazz);
        }
    }

    public <T> byte[] serialize(T config) throws ZkStateSerializationException {
        try {
            return serializer.objToBytes(config);
        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize.", e);
        }
    }

}
