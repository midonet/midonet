/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data

import java.lang.reflect.Field

import com.google.protobuf.MessageOrBuilder

/**
 * Represents the base class for an object stored in the ZOOM storage.
 */
abstract class ZoomObject {

    def this(proto: MessageOrBuilder) {
        this()
        ZoomConvert.fromProto(this, proto)
    }

    def toProto[T <: MessageOrBuilder](clazz: Class[T]): T = {
        ZoomConvert.toProto(this, clazz)
    }

    protected[data] def getField(field: Field): Any = {
        field setAccessible true
        field get this
    }

    protected[data] def setField(field: Field, value: Any): Unit = {
        field setAccessible true
        field.set(this, value)
    }
}
