/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
    <T> byte[] serialize(T obj) throws SerializationException;

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
    <T> T deserialize(byte[] data, Class<T> clazz)
            throws SerializationException;

}
