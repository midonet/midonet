/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb

import java.util.UUID

/**
  * Represents a pool member object local to the host.  The host that acts as a
  * health monitor only needs to know minimal amount of pool member data to
  * run the service.
  */
class PoolMemberConfig(val admingStateUp: Boolean, val id: UUID,
                       val weight: Int, val address: String, val port: Int) {
    def isConfigurable = id != null && port > 0 && address != null

    override def equals(other: Any) = other match {
        case that: PoolMemberConfig =>
            this.weight == that.weight &&
            this.admingStateUp == that.admingStateUp &&
            this.id == that.id &&
            this.address == that.address &&
            this.port == that.port
        case _ => false
    }
}
