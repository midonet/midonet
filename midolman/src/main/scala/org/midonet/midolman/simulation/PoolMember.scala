/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.packets.IPv4Addr
import org.midonet.util.collection.HasWeight

class PoolMember (val id: UUID, val adminStateUp: Boolean,
                  val address: IPv4Addr, val protocolPort:Int,
                  val weight: Int, val status: String) extends HasWeight {
    def isUp = adminStateUp && status == "UP" && weight > 0

    override def toString =
        s"PoolMember[id=$id, adminStateUp=$adminStateUp, address=$address, " +
        s"protocolPort=$protocolPort, weight=$weight, status=$status]"
}
