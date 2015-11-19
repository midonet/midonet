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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Commons.LBStatus.ACTIVE
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPoolMember
import org.midonet.cluster.models.Topology.PoolMember
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for NeutronLoadBalancerPoolMember. */
class LoadBalancerPoolMemberTranslator(protected val storage: ReadOnlyStorage)
        extends Translator[NeutronLoadBalancerPoolMember]{

    private def translate(nm: NeutronLoadBalancerPoolMember,
                          status: LBStatus = ACTIVE)
    : PoolMember = {
        val mMember = PoolMember.newBuilder()
                                .setId(nm.getId)
                                .setAdminStateUp(nm.getAdminStateUp)
                                .setProtocolPort(nm.getProtocolPort)
                                .setWeight(nm.getWeight)
                                .setStatus(status)
        if (nm.hasPoolId) mMember.setPoolId(nm.getPoolId)
        if (nm.hasAddress) mMember.setAddress(nm.getAddress)

        mMember.build
    }

    override protected def translateCreate(nm: NeutronLoadBalancerPoolMember)
    : MidoOpList = List(Create(translate(nm)))

    override protected def translateDelete(id: UUID)
    : MidoOpList = List(Delete(classOf[PoolMember], id))

    override protected def translateUpdate(nm: NeutronLoadBalancerPoolMember)
    : MidoOpList = {
        // Load Balancer Pool status is set to ACTIVE upon creation by default,
        // and it is to be updated only by Health Monitor. Therefore when we
        // update Pool, we need to look up the current status and explicitly
        // restore it.
        val mMember = storage.get(classOf[PoolMember], nm.getId).await()
        List(Update(translate(nm, status = mMember.getStatus)))
    }
}