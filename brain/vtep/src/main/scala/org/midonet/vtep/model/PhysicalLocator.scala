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

package org.midonet.vtep.model

import java.util.Objects

import org.midonet.packets.IPv4Addr
import org.opendaylight.ovsdb.lib.notation.UUID

/**
 * Represents a VTEP's physical locator info.
 * @param uuid is UUID of this locator in OVSDB
 * @param encapsulation is the encapsulation type
 * @param dstIp is the destination ip
 */
final class PhysicalLocator(val uuid: UUID, val encapsulation: String,
                           val dstIp: IPv4Addr) {
    override def toString: String = "PhysicalLocator{" +
                                    "uuid=" + uuid + ", " +
                                    "encapsulation=" + encapsulation + ", " +
                                    "dstIp=" + dstIp + "}"

    override def equals(o: Any): Boolean = (this == o) || (o match {
        case null => false
        case pl: PhysicalLocator =>
            Objects.equals(uuid, pl.uuid) &&
            Objects.equals(encapsulation, pl.encapsulation) &&
            Objects.equals(dstIp, pl.dstIp)
        case other => false
    })

    override def hashCode: Int = uuid.hashCode
}
