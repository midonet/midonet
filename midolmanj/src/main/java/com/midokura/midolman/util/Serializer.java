/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.util;

import java.io.IOException;

/**
 * Interface for serializers.
 */
public interface Serializer {

    /**
     * Convert an object of type T to an array of bytes.
     *
     * @param obj
     * @return
     * @throws IOException
     */
    public <T> byte[] objToBytes(T obj) throws IOException;

    /**
     * Convert an array of bytes to an object of type T.
     *
     * @param data
     *            Array of bytes
     * @param clazz
     *            Class to convert the bytes to
     * @return An object of class T.
     * @throws IOException
     *             IO error.
     */
    public <T> T bytesToObj(byte[] data, Class<T> clazz) throws IOException;

}
