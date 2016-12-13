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

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{NeutronLoadBalancerV2Listener, NeutronPort}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation

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
        if (nL.getLoadbalancersCount != 1) {
            throw new IllegalArgumentException(
                "Listener must have one and only one load balancer ID")
        }

        val bldr = Vip.newBuilder
        bldr.setAdminStateUp(nL.getAdminStateUp)
        bldr.setId(nL.getId)
        if (nL.hasDefaultPoolId) {
            bldr.setPoolId(nL.getDefaultPoolId)
        }
        bldr.setProtocolPort(nL.getProtocolPort)

        val lbRouter = tx.get(classOf[Router],
            lbV2RouterId(nL.getLoadbalancers(0).getId))

        val routerPortList = tx.getAll(classOf[Port], lbRouter.getPortIdsList.asScala)
        val vipPeerPort = routerPortList find { p =>
            p.hasPeerId && routerInterfacePortPeerId(p.getPeerId) == p.getId
        } getOrElse {
            throw new IllegalStateException(
                "LB router has no router interface port which links to " +
                "the VIP port")
        }

        bldr.setAddress(vipPeerPort.getPortAddress)
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
        if (nL.hasDefaultPoolId) {
            oldBldr.setPoolId(nL.getDefaultPoolId)
        }
        tx.update(oldBldr.build())
    }

}
