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

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{NeutronLoadBalancerV2Listener, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation

/** Provides a Neutron model translator for NeutronLoadBalancerV2Listener. */
class ListenerV2Translator
    extends Translator[NeutronLoadBalancerV2Listener]
            with LoadBalancerManager
            with PortManager {

    import PortManager.routerInterfacePortPeerId

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronLoadBalancerV2Listener])
    : List[Operation[NeutronLoadBalancerV2Listener]] = List()


    override protected def translateCreate(tx: Transaction,
                                           nL: NeutronLoadBalancerV2Listener)
    : Unit = {
        if (!nL.hasLoadBalancerId) {
            throw new IllegalArgumentException("Listener must have load balancer ID")
        }

        val bldr = Vip.newBuilder
        bldr.setAdminStateUp(nL.getAdminStateUp)
        bldr.setId(nL.getId)
        if (nL.hasDefaultPoolId) {
            bldr.setPoolId(nL.getDefaultPoolId)
        }
        bldr.setProtocolPort(nL.getProtocolPort)

        val lbRouter = tx.get(classOf[Router], lbV2RouterId(nL.getLoadBalancerId))

        val lbRouterPeerIds = lbRouter.getPortIdsList.asScala map routerInterfacePortPeerId
        val vipPort = lbRouterPeerIds.collectFirst {
            case peerId if tx.exists(classOf[NeutronPort], peerId) =>
                tx.get(classOf[NeutronPort], peerId)
        }.getOrElse {
            throw new IllegalStateException(
                "LB router has no router interface port which links " +
                "to the VIP port")
        }

        if (vipPort.getFixedIpsCount == 0) {
            throw new IllegalStateException(
                "VIP port has no IP addresses assigned.  The VIP port on the " +
                "parent Load Balancer must at least have one IP address to " +
                "create a Listener.")
        }
        bldr.setAddress(vipPort.getFixedIps(0).getIpAddress)
        tx.create(bldr.build())
    }

    override protected def translateDelete(tx: Transaction,
                                           id: UUID)
    : Unit = {
        tx.delete(classOf[Vip], id, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nL: NeutronLoadBalancerV2Listener)
    : Unit = {
        val oldBldr = tx.get(classOf[Vip], nL.getId).toBuilder
        oldBldr.setAdminStateUp(nL.getAdminStateUp)
        oldBldr.setPoolId(nL.getDefaultPoolId)
        tx.update(oldBldr.build())
    }

}
