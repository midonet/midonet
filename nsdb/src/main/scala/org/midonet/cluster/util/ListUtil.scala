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
