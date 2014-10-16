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
import org.midonet.packets.IPAddr
import scala.collection.JavaConversions

class IPAddrGroup(val id: UUID, val addrs: Set[IPAddr]) {
    def contains(addr: IPAddr) = addrs.contains(addr)
    override def toString =
        "IPAddrGroup[id=%s, addrs=[%s]]".format(id, addrs.mkString(", "))
}

object IPAddrGroup {
    /**
     * Added because initializing an immutable set from Java is a pain.
     */
    def fromAddrs(id: UUID, addrs: Array[IPAddr]): IPAddrGroup = {
        new IPAddrGroup(id, addrs.toSet)
    }
}
