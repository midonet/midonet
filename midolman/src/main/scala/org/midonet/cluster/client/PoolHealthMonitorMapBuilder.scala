/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.cluster.client

import java.util.UUID
import scala.collection.immutable.Map
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig

trait PoolHealthMonitorMapBuilder extends Builder[PoolHealthMonitorMapBuilder] {
    /**
     * Sets global mappings of pool IDs to health monitor configs.
     *
     * @param mappings Map of pool IDs to health montitor configs.
     */
     def set(mappings: Map[UUID, PoolHealthMonitorConfig])
}
