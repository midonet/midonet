/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.brain.services.vxgw.monitor;

import java.util.UUID;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.data.Bridge;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

/**
 * This class monitors the bridges of the system, exposing observable streams
 * with creations, deletions and updates.
 *
 * Internally, it coordinates a global entityIdSetMonitor to detect creations
 * and removals, and an entityMonitor to track changes to individual bridges.
 */
public class BridgeMonitor extends DeviceMonitor<UUID, Bridge> {

    public BridgeMonitor(DataClient midoClient,
                         ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitorException {
        super(midoClient, zkConnWatcher);
    }

    @Override
    protected EntityMonitor<UUID, BridgeZkManager.BridgeConfig,
        Bridge> getEntityMonitor() {
        return midoClient.bridgesGetMonitor(zkConnWatcher);
    }

    @Override
    protected EntityIdSetMonitor<UUID> getEntityIdSetMonitor()
        throws StateAccessException {
        return midoClient.bridgesGetUuidSetMonitor(zkConnWatcher);
    }
}

