/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb


/**
  * Represents a health monitor object local to the host.  The host that acts
  * as a health monitor only needs to know minimal amount of data to run the
  * service.
  */
class HealthMonitorConfig(val adminStateUp: Boolean, val delay: Int,
                          val timeout: Int, val maxRetries: Int) {
    def isConfigurable = adminStateUp && delay > 0 && timeout > 0 && maxRetries > 0

    override def equals(other: Any) = other match {
        case that: HealthMonitorConfig =>
            this.adminStateUp == that.adminStateUp &&
            this.delay == that.delay &&
            this.timeout == that.timeout &&
            this.maxRetries == that.maxRetries
        case _ => false
    }
}
