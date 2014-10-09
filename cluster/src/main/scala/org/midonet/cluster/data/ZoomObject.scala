/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
