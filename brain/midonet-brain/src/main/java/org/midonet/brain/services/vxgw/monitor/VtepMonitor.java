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

import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityMonitor;
import org.midonet.cluster.EntityIdSetMonitor;
import org.midonet.cluster.data.VTEP;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.packets.IPv4Addr;

/**
 * Monitors all VTEPS of the system, exposing observable streams with creation,
 * deletion and update notifications.
 */
public class VtepMonitor extends DeviceMonitor<IPv4Addr, VTEP> {

    public VtepMonitor(DataClient midoClient,
                       ZookeeperConnectionWatcher zkConnWatcher)
        throws DeviceMonitorException {
        super(midoClient, zkConnWatcher);
    }

    @Override
    protected EntityMonitor<IPv4Addr, VtepZkManager.VtepConfig,
        VTEP> getEntityMonitor() {
        return midoClient.vtepsGetMonitor(zkConnWatcher);
    }

    @Override
    protected EntityIdSetMonitor<IPv4Addr> getEntityIdSetMonitor()
        throws StateAccessException {
        return midoClient.vtepsGetAllSetMonitor(zkConnWatcher);
    }
}
