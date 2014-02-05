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
class PoolMemberConfig(val id: UUID, val address: String, val port: Int) {
    def isConfigurable = port > 0 && address != null
}
