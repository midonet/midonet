/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.packets.IPv4Addr

/**
 * PoolMember.
 *
 * Placeholder class.
 */
class PoolMember (val id: UUID, val adminStateUp: Boolean,
           val address: IPv4Addr, val protocolPort:Int,
           val weight: Int, val status: String) {
    def isDown = !adminStateUp || status != "UP"
}
