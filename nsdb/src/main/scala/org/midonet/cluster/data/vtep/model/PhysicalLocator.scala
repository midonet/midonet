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

package org.midonet.cluster.data.vtep.model

import java.util.{Objects, UUID}

import org.midonet.packets.IPv4Addr

/**
 * Represents a VTEP's physical locator info. Note that specs do not prevent
 * dstIp from being null. The dstIpString value is provided for convenience,
 * to facilitate integration with the low-level ovsdb library using strings
 * to represent IPs.
 *
 * @param uuid is UUID of this locator in OVSDB
 * @param dstIp is the destination ip
 */
final class PhysicalLocator(override val uuid: UUID,
                            val dstIp: IPv4Addr) extends VtepEntry {

    val encapsulation: String = "vxlan_over_ipv4"
    val dstIpString: String = if (dstIp == null) null else dstIp.toString

    private val str: String = "PhysicalLocator{" +
                                  "uuid=" + uuid + ", " +
                                  "dstIp=" + dstIp + ", " +
                                  "encapsulation='" + encapsulation + "'}"
    override def toString = str

    override def equals(o: Any): Boolean = o match {
        case that: PhysicalLocator =>
            Objects.equals(dstIp, that.dstIp) &&
            Objects.equals(encapsulation, that.encapsulation)
        case other => false
    }
}

object PhysicalLocator {
    def apply(id: UUID, dst: IPv4Addr)
    : PhysicalLocator = new PhysicalLocator(id, dst)

    def apply(dst: IPv4Addr): PhysicalLocator = apply(null, dst)
}

