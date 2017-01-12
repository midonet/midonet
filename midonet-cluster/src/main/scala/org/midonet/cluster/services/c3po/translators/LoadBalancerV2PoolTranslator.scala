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
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool.{LBV2SessionPersistenceType, LoadBalancerV2Algorithm, LoadBalancerV2Protocol}
import org.midonet.cluster.models.Topology.Pool.{PoolLBMethod, PoolProtocol}
import org.midonet.cluster.models.Topology.{Pool, SessionPersistence, Vip}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation

class LoadBalancerV2PoolTranslator
    extends Translator[NeutronLoadBalancerV2Pool] {

    override protected def translateCreate(
            tx: Transaction, nPool: NeutronLoadBalancerV2Pool): Unit = {
        if (nPool.getLoadbalancersCount != 1) {
            throw new IllegalArgumentException(
                "A LBaaS V2 pool must have one and only one Load Balancer parent")
        }
        val bldr = Pool.newBuilder
        bldr.setAdminStateUp(nPool.getAdminStateUp)
        if (nPool.hasHealthmonitorId)
            bldr.setHealthMonitorId(nPool.getHealthmonitorId)
        bldr.setId(nPool.getId)
        bldr.setLoadBalancerId(nPool.getLoadbalancers(0).getId)
        bldr.setLbMethod(toLbMethod(nPool.getLbAlgorithm))
        bldr.setProtocol(toPoolProtocol(nPool.getProtocol))

        if (nPool.hasSessionPersistence)
            bldr.setSessionPersistence(translateSessionPersistence(nPool))

        if (nPool.hasListenerId)
            bldr.addVipIds(nPool.getListenerId)

        tx.create(bldr.build())
    }

    private def toLbMethod(alg: LoadBalancerV2Algorithm)
    : PoolLBMethod = alg match {
        case LoadBalancerV2Algorithm.ROUND_ROBIN => PoolLBMethod.ROUND_ROBIN
        case _ =>
            throw new IllegalArgumentException(
                s"Unsupported load balancer algorithm: $alg")
    }

    private def toPoolProtocol(prot: LoadBalancerV2Protocol)
    : PoolProtocol = prot match {
        case LoadBalancerV2Protocol.TCP => PoolProtocol.TCP
        case _ =>
            throw new IllegalArgumentException(
                s"Unsupported protocol: $prot")
    }

    private def translateSessionPersistence(
            nPool: NeutronLoadBalancerV2Pool): SessionPersistence = {
        if (!nPool.hasSessionPersistence)
            return null

        nPool.getSessionPersistence.getType match {
            case LBV2SessionPersistenceType.SOURCE_IP =>
                SessionPersistence.SOURCE_IP
            case typ =>
                throw new IllegalArgumentException(
                    s"Unsupported session persistence type: $typ")
        }
    }

    override protected def translateUpdate(
            tx: Transaction, nPool: NeutronLoadBalancerV2Pool): Unit = {
        val mPool = tx.get(classOf[Pool], nPool.getId)
        val bldr = mPool.toBuilder
        bldr.setAdminStateUp(nPool.getAdminStateUp)
        bldr.setLbMethod(toLbMethod(nPool.getLbAlgorithm))

        translateSessionPersistence(nPool) match {
            case null => bldr.clearSessionPersistence()
            case sp => bldr.setSessionPersistence(sp)
        }

        tx.update(bldr.build())
    }


    override protected def translateDelete(tx: Transaction, id: UUID): Unit = {
        // THe Pool-VIP binding is set up to cascade deletion of the Pool to
        // its associated VIPs. Changing the binding would affect LBV1 as well,
        // creating a breaking change. Instead, just update the pool to clear
        // its vip_ids list before deleting it. This will prevent the cascade.
        val pool = tx.get(classOf[Pool], id)
        tx.update(pool.toBuilder.clearVipIds().build())
        tx.delete(classOf[Pool], id, ignoresNeo = true)
    }

    // Don't retain Neutron model.
    override protected def retainHighLevelModel(
            tx: Transaction, op: Operation[NeutronLoadBalancerV2Pool]) = List()
}
