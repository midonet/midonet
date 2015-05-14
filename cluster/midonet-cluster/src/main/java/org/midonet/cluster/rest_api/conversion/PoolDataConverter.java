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
package org.midonet.cluster.rest_api.conversion;

import java.net.URI;

import org.midonet.cluster.rest_api.models.Pool;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;

public class PoolDataConverter {

    public static Pool fromData(org.midonet.cluster.data.l4lb.Pool data,
                                URI baseUri) throws IllegalAccessException {
        Pool pool = new Pool();
        pool.loadBalancerId = data.getLoadBalancerId();
        pool.healthMonitorId = data.getHealthMonitorId();
        pool.protocol = data.getProtocol() != null ?
                        data.getProtocol().toString()
                        : null;
        pool.lbMethod = data.getLbMethod() != null ?
                        data.getLbMethod().toString()
                        : null;
        pool.adminStateUp = data.isAdminStateUp();
        pool.status = (data.getStatus() != null ? data.getStatus().toString()
                                                : null);
        pool.id = data.getId();
        pool.setBaseUri(baseUri);
        return pool;
    }

    public static org.midonet.cluster.data.l4lb.Pool toData(Pool pool) {
        return new org.midonet.cluster.data.l4lb.Pool()
                .setId(pool.id)
                .setLoadBalancerId(pool.loadBalancerId)
                .setHealthMonitorId(pool.healthMonitorId)
                .setProtocol(PoolProtocol.valueOf(pool.protocol))
                .setLbMethod(PoolLBMethod.valueOf(pool.lbMethod))
                .setAdminStateUp(pool.adminStateUp)
                .setStatus(LBStatus.valueOf(pool.status));
    }

}
