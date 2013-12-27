/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.cluster.data.l4lb
import org.midonet.packets.IPv4Addr

/**
 * Vip.
 *
 * Placeholder class.
 */
class VIP (val id: UUID, val adminStateUp: Boolean, val poolId: UUID,
           val address: IPv4Addr, val protocolPort:Int,
           val sessionPersistence: String) {

    def this(dataVip: l4lb.VIP) = this(
        dataVip.getId,
        dataVip.getAdminStateUp,
        dataVip.getPoolId,
        IPv4Addr(dataVip.getAddress),
        dataVip.getProtocolPort,
        dataVip.getSessionPersistence)
}
