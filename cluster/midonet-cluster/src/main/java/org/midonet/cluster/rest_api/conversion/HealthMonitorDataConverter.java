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

import org.midonet.cluster.rest_api.models.HealthMonitor;
import org.midonet.midolman.state.l4lb.HealthMonitorType;
import org.midonet.midolman.state.l4lb.LBStatus;

public class HealthMonitorDataConverter {

    public static HealthMonitor fromData(
            org.midonet.cluster.data.l4lb.HealthMonitor healthMonitor,
            URI baseUri) throws IllegalAccessException {
        HealthMonitor hm = new HealthMonitor();
        hm.id = healthMonitor.getId();
        hm.type = healthMonitor.getType().toString();
        hm.delay = healthMonitor.getDelay();
        hm.timeout = healthMonitor.getTimeout();
        hm.maxRetries = healthMonitor.getMaxRetries();
        hm.adminStateUp = healthMonitor.isAdminStateUp();
        hm.status = healthMonitor.getStatus().toString();
        hm.setBaseUri(baseUri);
        return hm;
    }

    public static org.midonet.cluster.data.l4lb.HealthMonitor
    toData(HealthMonitor hm) {
        return new org.midonet.cluster.data.l4lb.HealthMonitor()
            .setId(hm.id)
            .setType(HealthMonitorType.valueOf(hm.type))
            .setDelay(hm.delay)
            .setTimeout(hm.timeout)
            .setMaxRetries(hm.maxRetries)
            .setAdminStateUp(hm.adminStateUp)
            .setStatus(Enum.valueOf(LBStatus.class, hm.status));
    }
}
