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

import org.midonet.cluster.rest_api.models.VIP;

import static org.midonet.midolman.state.l4lb.VipSessionPersistence.valueOf;

public class VIPDataConverter {

    public static VIP fromData(org.midonet.cluster.data.l4lb.VIP vipData,
                               URI baseUri) throws IllegalAccessException {
        VIP vip = new VIP();
        vip.id = vipData.getId();
        vip.loadBalancerId = vipData.getLoadBalancerId();
        vip.poolId = vipData.getPoolId();
        vip.address = vipData.getAddress();
        vip.protocolPort = vipData.getProtocolPort();
        vip.sessionPersistence = vipData.getSessionPersistence();
        vip.adminStateUp = vipData.getAdminStateUp();
        vip.setBaseUri(baseUri);
        return vip;
    }

    public static org.midonet.cluster.data.l4lb.VIP toData(VIP vip) {
        return new org.midonet.cluster.data.l4lb.VIP()
                .setId(vip.id)
                .setLoadBalancerId(vip.loadBalancerId)
                .setPoolId(vip.poolId)
                .setAddress(vip.address)
                .setProtocolPort(vip.protocolPort)
                .setSessionPersistence(vip.sessionPersistence)
                .setAdminStateUp(vip.adminStateUp);
    }

}
