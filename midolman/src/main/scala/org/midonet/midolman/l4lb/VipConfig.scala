/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb

import java.util.UUID
import org.midonet.midolman.state.l4lb.VipSessionPersistence

/**
  * Represents a pool member object local to the host.  The host that acts as a
  * health monitor only needs to know minimal amount of pool member data to
  * run the service.
  */
class VipConfig(val adminStateUp: Boolean, val id: UUID, val ip: String,
                val port: Int, val sessionPersistence: VipSessionPersistence) {
    def isConfigurable = adminStateUp && id != null && ip != null && port > 0

    override def equals(other: Any) = other match {
        case that: VipConfig =>
            this.adminStateUp == that.adminStateUp &&
            this.id == that.id &&
            this.ip == that.ip &&
            this.port == that.port &&
            this.sessionPersistence == that.sessionPersistence
        case _ => false
    }
}
