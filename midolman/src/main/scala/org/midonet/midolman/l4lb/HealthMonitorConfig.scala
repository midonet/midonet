/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb


/**
  * Represents a health monitor object local to the host.  The host that acts
  * as a health monitor only needs to know minimal amount of data to run the
  * service.
  */
class HealthMonitorConfig(val delay: Int, val timeout: Int,
                          val maxRetries: Int) {
    def isConfigurable = delay > 0 && timeout > 0 && maxRetries > 0
}
