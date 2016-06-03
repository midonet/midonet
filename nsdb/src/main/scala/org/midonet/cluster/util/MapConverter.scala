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

import java.lang.reflect.Type
import java.util.{List => JList, Map => JMap}

import scala.collection.JavaConverters._
import scala.collection.breakOut

import com.google.protobuf.MessageOrBuilder

import org.midonet.cluster.data.ZoomConvert

/**
 * Converter trait for map. Implementing classes must provide definitions for
 * the following methods:
 * - toKey: Specifies the conversion from the Protocol Buffers message to the
 *          map key.
 * - toValue: Specifies the conversion from the Protocol Buffers message to the
 *            map value.
 * - toProto: Specifies the conversion from the map key and value to the
 *            Protocol Buffers message.
 * @tparam K The map key type.
 * @tparam V The map value type.
 * @tparam U The Protocol Buffers message type.
 */
trait MapConverter[K, V, U <: MessageOrBuilder]
    extends ZoomConvert.Converter[Map[K, V], JList[U]] {

    def toKey(proto: U): K

    def toValue(proto: U): V

    def toProto(key: K, value: V): U

    override def toProto(value: Map[K, V], clazz: Type): JList[U] = {
        val l: List[U] = value.toList.map(el => toProto(el._1, el._2))(breakOut)
        l.asJava
    }

    override def fromProto(value: JList[U], clazz: Type): Map[K, V] = {
        value.asScala.map(el => (toKey(el), toValue(el)))(breakOut)
    }
}

trait JMapConverter[K, V, U <: MessageOrBuilder]
    extends ZoomConvert.Converter[JMap[K, V], JList[U]] {

    def toKey(proto: U): K
    def toValue(proto: U): V
    def toProto(key: K, value: V): U

    override def toProto(value: JMap[K, V], clazz: Type): JList[U] = {
        val l: List[U] = value.asScala.toList.map {
            case (k, v) => toProto(k, v)
        }(breakOut)
        l.asJava
    }

    override def fromProto(value: JList[U], clazz: Type): JMap[K, V] = {
        val m: Map[K, V] = value.asScala.map {
            el => (toKey(el), toValue(el))
        }(breakOut)
        m.asJava
    }
}
