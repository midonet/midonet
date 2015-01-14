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

package org.midonet.cluster.data.vtep.model

import java.util.{UUID, Objects}

import org.midonet.packets.IPv4Addr

/**
 * Represents a VTEP's physical locator info.
 * @param uuid is UUID of this locator in OVSDB
 * @param encapsulation is the encapsulation type
 * @param dstIp is the destination ip
 */
final class PhysicalLocator(val uuid: UUID, val dstIp: IPv4Addr,
                            val encapsulation: String) {
    private val str: String = "PhysicalLocator{" +
                                  "uuid=" + uuid + ", " +
                                  "dstIp=" + dstIp + ", " +
                                  "encapsulation='" + encapsulation + "'}"
    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: PhysicalLocator =>
            Objects.equals(dstIp, that.dstIp) &&
            Objects.equals(encapsulation, that.encapsulation)
        case other => false
    }

    override def hashCode: Int =
        if (uuid == null) Objects.hash(dstIp, encapsulation) else uuid.hashCode
}

object PhysicalLocator {
    val DEFAULT_ENCAPSULATION: String = "vxlan_over_ipv4"

    def apply(id: UUID, dst: IPv4Addr, enc: String)
        : PhysicalLocator = new PhysicalLocator(id, dst, enc)

    def apply(id: UUID, dst: String, enc: String)
        : PhysicalLocator = apply(id,
        if (dst == null || dst.isEmpty) null else IPv4Addr.fromString(dst), enc)

    def apply(dst: String, enc: String = DEFAULT_ENCAPSULATION)
        : PhysicalLocator = apply(null, dst, enc)
    def apply(dst: IPv4Addr, enc: String)
        : PhysicalLocator = apply(null, dst, enc)
    def apply(dst: IPv4Addr)
        : PhysicalLocator = apply(null, dst, DEFAULT_ENCAPSULATION)
}

