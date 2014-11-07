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

import java.util.UUID

import org.midonet.cluster.models.Topology.PortInterface
import org.midonet.cluster.util.UUIDUtil._

/**
 * This class does implements the MapConverter trait to do the conversion between
 * tuples of type (UUID, String) and PortInterface protos.
 */
class PortInterfaceConverter extends MapConverter[UUID, String, PortInterface] {

    override def toKey(proto: PortInterface): UUID = {
        proto.getId.asJava
    }

    def toValue(proto: PortInterface): String = {
        proto.getInterfaceName
    }

    def toProto(key: UUID, value: String): PortInterface = {
        PortInterface.newBuilder
            .setId(key.asProto)
            .setInterfaceName(value)
            .build()
    }
}
