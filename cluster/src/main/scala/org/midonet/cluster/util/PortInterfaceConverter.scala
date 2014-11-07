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
import java.util.UUID
import org.midonet.cluster.models.Topology.PortInterface
import org.midonet.cluster.util.UUIDUtil._

import org.midonet.cluster.data.ZoomConvert

/**
 * This class does the conversion between PortInterface protos and
 * (portId: UUID, interface: String) tuples.
 */
class PortInterfaceConverter extends ZoomConvert.Converter[(UUID, String),
                                                           PortInterface] {

        override def toProto(value: (UUID, String), clazz: Type): PortInterface = {
            PortInterface.newBuilder
                .setId(value._1.asProto)
                .setInterfaceName(value._2)
                .build()
        }

        override def fromProto(value: PortInterface,
                               clazz: Type): (UUID, String) = {
            (value.getId.asJava, value.getInterfaceName)
        }
}
