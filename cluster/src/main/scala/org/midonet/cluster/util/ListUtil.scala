/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.util

import java.util.{List => JList}

import scala.collection.JavaConversions._

import com.google.protobuf.MessageOrBuilder

import org.midonet.cluster.data.{ZoomConvert, ZoomObject}

/**
 * Utility method for list of Protocol Buffer messages.
 */
object ListUtil {

    def fromProto[T >: Null <: ZoomObject,
                  U <: MessageOrBuilder](list: JList[U],
                                         clazz: Class[T]): JList[T] = {
        bufferAsJavaList(list.map(el => ZoomConvert.fromProto(el, clazz)))
    }

    implicit def richProtoList[U <: MessageOrBuilder](list: JList[U]) = new {
        def asJava[T >: Null <: ZoomObject](clazz: Class[T]): JList[T] =
            fromProto(list, clazz)
    }
}
