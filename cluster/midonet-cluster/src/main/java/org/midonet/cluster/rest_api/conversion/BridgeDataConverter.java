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
package org.midonet.cluster.rest_api.conversion;

import org.midonet.cluster.data.Bridge.Property;
import org.midonet.cluster.rest_api.models.Bridge;

public class BridgeDataConverter {

    public static Bridge fromData(org.midonet.cluster.data.Bridge bridgeData) {
        Bridge b = new Bridge();
        b.setId(bridgeData.getId());
        b.setName(bridgeData.getName());
        b.setTenantId(bridgeData.getProperty(Property.tenant_id));
        b.setAdminStateUp(bridgeData.isAdminStateUp());
        b.setInboundFilterId(bridgeData.getInboundFilter());
        b.setOutboundFilterId(bridgeData.getOutboundFilter());
        b.setVxLanPortId(bridgeData.getVxLanPortId());
        b.setVxLanPortIds(bridgeData.getVxLanPortIds());
        return b;
    }

    public static org.midonet.cluster.data.Bridge toData(Bridge b) {
        return new org.midonet.cluster.data.Bridge()
            .setId(b.getId())
            .setName(b.getName())
            .setAdminStateUp(b.isAdminStateUp())
            .setInboundFilter(b.getInboundFilterId())
            .setOutboundFilter(b.getOutboundFilterId())
            .setVxLanPortId(b.getVxLanPortId())
            .setVxLanPortIds(b.getVxLanPortIds())
            .setProperty(Property.tenant_id, b.getTenantId());
    }

}
