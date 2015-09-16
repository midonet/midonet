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

package org.midonet.cluster;

import java.util.UUID;

import org.midonet.cluster.client.BGPListBuilder;
import org.midonet.cluster.client.HealthMonitorBuilder;
import org.midonet.cluster.client.PoolHealthMonitorMapBuilder;
import org.midonet.cluster.client.RouterBuilder;
import org.midonet.cluster.client.TunnelZones;


public interface Client {

    enum PortType {
        InteriorBridge, ExteriorBridge, InteriorRouter, ExteriorRouter
    }

    void getRouter(UUID routerID, RouterBuilder builder);

    void getTunnelZones(UUID uuid, TunnelZones.BuildersProvider builders);

    void getHealthMonitor(UUID uuid, HealthMonitorBuilder builder);

}
