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
package org.midonet.cluster.data

import java.lang.reflect.Field

import com.google.protobuf.GeneratedMessage.Builder
import com.google.protobuf.MessageOrBuilder

/**
 * Represents the base class for an object stored in the ZOOM storage.
 */
abstract class ZoomObject {

    /**
     * Initializes the objects fields annotated with the [[ZoomField]]
     * annotation from the specified Protocol Buffers message.
     */
    def this(proto: MessageOrBuilder) {
        this()
        ZoomConvert.fromProto(this, proto)
    }

    /**
     * Converts the current instance to a Protocol Buffers message of the
     * specified type.
     * @param clazz The message class.
     * @return The object instance.
     */
    final def toProto[T <: MessageOrBuilder](clazz: Class[T]): T = {
        ZoomConvert.toProto(this, clazz)
    }

    /**
     * Converts the current instance to a Protocol Buffers message builder of
     * the specified type.
     * @param clazz The message class.
     * @return The builder.
     */
    final def toProtoBuilder[T <: MessageOrBuilder](clazz: Class[T]): Builder[_] = {
        ZoomConvert.toProtoBuilder(this, clazz)
    }

    /**
     * When overridden in a derived class, allows the execution of custom tasks
     * after the conversion of the object from a Protocol Buffers message.
     */
    protected[data] def afterFromProto(): Unit = {
    }

    /**
     * When overridden in a derived class, allows the execution of custom tasks
     * before the conversion of the object to a Protocol Buffers message.
     */
    protected[data] def beforeToProto(): Unit = {
    }

    final protected[data] def getField(field: Field): Any = {
        field setAccessible true
        field get this
    }

    final protected[data] def setField(field: Field, value: Any): Unit = {
        field setAccessible true
        field.set(this, value)
    }
}
