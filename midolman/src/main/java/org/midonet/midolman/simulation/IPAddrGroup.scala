/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
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
