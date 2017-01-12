/*
 * Copyright 2016 Midokura SARL
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
import org.midonet.cluster.models.Commons.{LBStatus, UUID}
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2PoolMember
import org.midonet.cluster.models.Topology.PoolMember
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation

class LoadBalancerV2PoolMemberTranslator
    extends Translator[NeutronLoadBalancerV2PoolMember] {

    override protected def translateCreate(tx: Transaction,
                                           npm: NeutronLoadBalancerV2PoolMember)
    : Unit = {
        val bldr = PoolMember.newBuilder
        bldr.setAddress(npm.getAddress)
        bldr.setAdminStateUp(npm.getAdminStateUp)
        bldr.setId(npm.getId)
        bldr.setPoolId(npm.getPoolId)
        bldr.setProtocolPort(npm.getProtocolPort)
        bldr.setStatus(LBStatus.MONITORED)
        bldr.setWeight(npm.getWeight)
        tx.create(bldr.build())
    }

    override protected def translateUpdate(tx: Transaction,
                                           npm: NeutronLoadBalancerV2PoolMember)
    : Unit = {
        val bldr = tx.get(classOf[PoolMember], npm.getId).toBuilder
        bldr.setAdminStateUp(npm.getAdminStateUp)
        bldr.setWeight(npm.getWeight)
        tx.update(bldr.build())
    }


    override protected def translateDelete(tx: Transaction, id: UUID): Unit = {
        tx.delete(classOf[PoolMember], id, ignoresNeo = true)
    }

    // Don't retain high-level model.
    override protected def retainHighLevelModel(
            tx: Transaction, op: Operation[NeutronLoadBalancerV2PoolMember])
    : List[Operation[NeutronLoadBalancerV2PoolMember]] = List()
}
