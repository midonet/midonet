/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.cluster.client

import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager._

/**
 * A builder for a health monitor cluster data object.
 */
trait HealthMonitorBuilder extends Builder[HealthMonitorBuilder]{

    /**
     * Sets the ZK configuration for health monitor in the builder.
     *
     * @param healthMonitor Health monitor configuration from ZK
     */
    def setHealthMonitor(healthMonitor: HealthMonitorConfig)

}
