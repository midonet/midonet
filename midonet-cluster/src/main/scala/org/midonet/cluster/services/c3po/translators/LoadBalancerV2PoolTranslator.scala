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
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerV2Pool.{LBV2SessionPersistenceAlgorithm, LBV2SessionPersistenceType, LoadBalancerV2Algorithm, LoadBalancerV2Protocol}
import org.midonet.cluster.models.Topology.Pool.{PoolLBMethod, PoolProtocol}
import org.midonet.cluster.models.Topology.SessionPersistence
import org.midonet.cluster.models.Topology.{Pool, Vip}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import scala.collection.JavaConverters._

import org.midonet.cluster.models.Commons.UUID

class LoadBalancerV2PoolTranslator
    extends Translator[NeutronLoadBalancerV2Pool] {

    override protected def translateCreate(
            tx: Transaction, nPool: NeutronLoadBalancerV2Pool): Unit = {
        val bldr = Pool.newBuilder
        bldr.setAdminStateUp(nPool.getAdminStateUp)
        if (nPool.hasHealthmonitorId)
            bldr.setHealthMonitorId(nPool.getHealthmonitorId)
        bldr.setId(nPool.getId)
        bldr.setLoadBalancerId(nPool.getLoadBalancerId)
        bldr.setLbMethod(toLbMethod(nPool.getLbAlgorithm))
        bldr.setProtocol(toPoolProtocol(nPool.getProtocol))

        if (nPool.hasListenerId) {
            bldr.addPoolMemberIds(nPool.getListenerId)
            val vipBldr = tx.get(classOf[Vip], nPool.getListenerId).toBuilder
            if (nPool.hasSessionPersistence)
                vipBldr.setSessionPersistence(getSessionPersistence(nPool))
            tx.update(vipBldr)
        }

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

    private def getSessionPersistence(
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

    private def getSessionPersistence(vip: Vip) =
        if (vip.hasSessionPersistence) vip.getSessionPersistence else null

    override protected def translateUpdate(
            tx: Transaction, nPool: NeutronLoadBalancerV2Pool): Unit = {
        val mPool = tx.get(classOf[Pool], nPool.getId)
        val bldr = mPool.toBuilder
        bldr.setAdminStateUp(nPool.getAdminStateUp)
        bldr.setLbMethod(toLbMethod(nPool.getLbAlgorithm))

        // See if we need to update session persistence on associated VIPs.
        val newSessionPersistence = getSessionPersistence(nPool)
        if (nPool.getListenersCount > 0) {
            val headVip = tx.get(classOf[Vip], nPool.getListeners(0))
            if (newSessionPersistence != getSessionPersistence(headVip)) {
                for (v <- tx.getAll(classOf[Vip],
                                    nPool.getListenersList.asScala)) {
                    val vipBldr = v.toBuilder
                    if (newSessionPersistence == null) {
                        vipBldr.clearSessionPersistence()
                    } else {
                        vipBldr.setSessionPersistence(newSessionPersistence)
                    }
                    tx.update(vipBldr.build())
                }
            }
        }

        tx.update(bldr.build())
    }


    override protected def translateDelete(tx: Transaction, id: UUID): Unit = {
        tx.delete(classOf[Pool], id)
    }

    // Don't retain Neutron model.
    override protected def retainHighLevelModel(
            tx: Transaction, op: Operation[NeutronLoadBalancerV2Pool]) = List()
}
