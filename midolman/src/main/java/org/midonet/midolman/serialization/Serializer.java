/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.serialization;

/**
 * Interface for ZooKeeper metadata serializers.
 */
public interface Serializer {

    /**
     * Convert an object of type T to an array of bytes.
     *
     * @param obj - the object to be serialized
     * @return the serialized representation of obj
     * @throws SerializationException
     */
    public <T> byte[] serialize(T obj) throws SerializationException;

    /**
     * Convert an array of bytes to an object of type T.
     *
     *
     * @param data
     *            Array of bytes
     * @param clazz
     *            Class to convert the bytes to
     * @param <T>
     *            The base type of the object serialized in data
     *
     * @return The deserialized object.
     *
     * @throws SerializationException
     *             IO error.
     */
    public <T> T deserialize(byte[] data, Class<T> clazz)
            throws SerializationException;

}
