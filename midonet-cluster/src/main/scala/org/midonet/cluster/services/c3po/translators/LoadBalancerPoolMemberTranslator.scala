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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.LBStatus
import org.midonet.cluster.models.Commons.LBStatus.ACTIVE
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPoolMember
import org.midonet.cluster.models.Topology.PoolMember

/**
  * Provides a Neutron model translator for NeutronLoadBalancerPoolMember.
  */
class LoadBalancerPoolMemberTranslator
    extends Translator[NeutronLoadBalancerPoolMember] {

    private def translate(poolMember: NeutronLoadBalancerPoolMember,
                          status: LBStatus = ACTIVE)
    : PoolMember = {
        val mMember = PoolMember.newBuilder()
                                .setId(poolMember.getId)
                                .setAdminStateUp(poolMember.getAdminStateUp)
                                .setProtocolPort(poolMember.getProtocolPort)
                                .setWeight(poolMember.getWeight)
                                .setStatus(status)
        if (poolMember.hasPoolId) mMember.setPoolId(poolMember.getPoolId)
        if (poolMember.hasAddress) mMember.setAddress(poolMember.getAddress)

        mMember.build
    }

    override protected def translateCreate(tx: Transaction,
                                           poolMember: NeutronLoadBalancerPoolMember)
    : Unit = {
        tx.create(translate(poolMember))
    }

    override protected def translateDelete(tx: Transaction,
                                           poolMember: NeutronLoadBalancerPoolMember)
    : Unit = {
        tx.delete(classOf[PoolMember], poolMember.getId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           poolMember: NeutronLoadBalancerPoolMember)
    : Unit = {
        // Load Balancer Pool status is set to ACTIVE upon creation by default,
        // and it is to be updated only by Health Monitor. Therefore when we
        // update Pool, we need to look up the current status and explicitly
        // restore it.
        val status = tx.get(classOf[PoolMember], poolMember.getId).getStatus
        tx.update(translate(poolMember, status = status))
    }
}
