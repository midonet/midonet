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

import org.midonet.cluster.rest_api.models.PoolMember;
import org.midonet.midolman.state.l4lb.LBStatus;

public class PoolMemberDataConverter {

    public static PoolMember fromData(
        org.midonet.cluster.data.l4lb.PoolMember poolMember, URI baseUri)
        throws IllegalAccessException{

        PoolMember pm = new PoolMember();
        pm.poolId = poolMember.getPoolId();
        pm.address = poolMember.getAddress();
        pm.protocolPort = poolMember.getProtocolPort();
        pm.weight = poolMember.getWeight();
        pm.adminStateUp = poolMember.getAdminStateUp();
        pm.status = poolMember.getStatus().toString();
        pm.id = poolMember.getId();
        pm.setBaseUri(baseUri);
        return pm;
    }

    public static org.midonet.cluster.data.l4lb.PoolMember toData(
        PoolMember pm) {
        return new org.midonet.cluster.data.l4lb.PoolMember()
                .setPoolId(pm.poolId)
                .setAddress(pm.address)
                .setProtocolPort(pm.protocolPort)
                .setWeight(pm.weight)
                .setAdminStateUp(pm.adminStateUp)
                .setStatus(LBStatus.valueOf(pm.status))
                .setId(pm.id);
    }

}
