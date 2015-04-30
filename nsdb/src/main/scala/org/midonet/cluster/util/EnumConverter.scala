/*
 * Copyright 2015 Midokura SARL
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

import scala.collection.mutable

import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.ProtocolMessageEnum

import org.midonet.cluster.data.ZoomConvert.{ConvertException, Converter}

class EnumConverter[T, U <: ProtocolMessageEnum]
    extends Converter[T, EnumValueDescriptor] {
    private val toProtoMap = new mutable.HashMap[T, EnumValueDescriptor]
    private val fromProtoMap = new mutable.HashMap[EnumValueDescriptor, T]

    override def toProto(value: T, clazz: Type): EnumValueDescriptor = {
        toProtoMap.getOrElse(value,
                             throw new ConvertException(s"Unknown enum $value"))
    }
    override def fromProto(value: EnumValueDescriptor, clazz: Type): T = {
        fromProtoMap.getOrElse(value,
                               throw new ConvertException(s"Unknown enum $value"))
    }

    protected def add(pojo: T, proto: U): Unit = {
        toProtoMap += pojo -> proto.getValueDescriptor
        fromProtoMap += proto.getValueDescriptor -> pojo
    }
}
