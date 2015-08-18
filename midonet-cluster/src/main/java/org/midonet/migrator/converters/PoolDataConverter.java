/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.migrator.converters;

import org.midonet.cluster.rest_api.models.Pool;

public class PoolDataConverter {

    public static Pool fromData(org.midonet.cluster.data.l4lb.Pool data) {
        Pool pool = new Pool();
        pool.loadBalancerId = data.getLoadBalancerId();
        pool.healthMonitorId = data.getHealthMonitorId();
        pool.protocol = data.getProtocol();
        pool.lbMethod = data.getLbMethod();
        pool.adminStateUp = data.isAdminStateUp();
        pool.status = data.getStatus();
        pool.id = data.getId();
        return pool;
    }

}
