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
package org.midonet.midolman.simulation

import java.util.UUID
import java.util.{Map => JMap, Set => JSet}

import org.midonet.packets.IPAddr
import org.midonet.midolman.topology.VirtualTopology.Device

import scala.collection.JavaConversions._

class IPAddrGroup(val id: UUID,
                  val addrPorts: Map[IPAddr, Set[UUID]]) extends Device {
    def contains(addr: IPAddr, portId: UUID) = {
        // If portID is null, just match on IP address
        addrPorts.contains(addr) && (portId == null || addrPorts.get(addr).get.contains(portId))
    }
    override def toString =
        "IPAddrGroup[id=%s, addrs=[%s]]".format(id, addrPorts.mkString(", "))
}

object IPAddrGroup {
    /**
     *
     * Added because initializing an immutable map/set from Java is a pain.
     */
    def fromAddrs(id: UUID, addrs: JMap[IPAddr, JSet[UUID]]): IPAddrGroup = {
        val sAddrs = addrs.map{item => (item._1, item._2.toSet)}.toMap
        new IPAddrGroup(id, sAddrs)
    }
}
