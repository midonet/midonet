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

import java.net.URI;

import org.midonet.cluster.rest_api.models.Vip;

public class VipDataConverter {

    public static Vip fromData(org.midonet.cluster.data.l4lb.VIP vipData) {
        Vip vip = new Vip();
        vip.id = vipData.getId();
        vip.loadBalancerId = vipData.getLoadBalancerId();
        vip.poolId = vipData.getPoolId();
        vip.address = vipData.getAddress();
        vip.protocolPort = vipData.getProtocolPort();
        vip.sessionPersistence = vipData.getSessionPersistence();
        vip.adminStateUp = vipData.getAdminStateUp();
        return vip;
    }

}
