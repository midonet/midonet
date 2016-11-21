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

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Neutron.NeutronNetwork.NetworkType
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._

/**
  * Provides a Neutron model translator for Network.
  */
class NetworkTranslator extends Translator[NeutronNetwork] {
    import NetworkTranslator._

    override protected def translateCreate(tx: Transaction,
                                           network: NeutronNetwork)
    : Unit = {
        // Uplink networks do not exist in MidoNet.
        if (isUplinkNetwork(network)) {
            return
        }

        tx.create(Network.newBuilder()
                      .setId(network.getId)
                      .setTenantId(network.getTenantId)
                      .setName(network.getName)
                      .setAdminStateUp(network.getAdminStateUp)
                      .build)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nNetwork: NeutronNetwork)
    : Unit = {
        // Uplink networks do not exist in MidoNet.
        if (isUplinkNetwork(nNetwork)) {
            return
        }

        val mNetwork = tx.get(classOf[Network], nNetwork.getId)
        val builder = mNetwork.toBuilder
            .setName(nNetwork.getName)
            .setAdminStateUp(nNetwork.getAdminStateUp)

        tx.update(builder.build())
    }

    override protected def translateDelete(tx: Transaction,
                                           nNetwork: NeutronNetwork)
    : Unit = {
        // Uplink networks do not exist in MidoNet.
        if (isUplinkNetwork(nNetwork)) {
            return
        }

        tx.delete(classOf[Network], nNetwork.getId, ignoresNeo = true)
    }

    /*
     * Retain backrefs to subnets.
     */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronNetwork])
    : List[Operation[NeutronNetwork]] = {
        op match {
            case Create(nm) => List(Create(nm))
            case Update(nm, _) => List(Update(nm, NeutronNetworkUpdateValidator))
            case Delete(clazz, id) => List(Delete(clazz, id))
        }
    }
}

private[translators] object NetworkTranslator {
    def isUplinkNetwork(nn: NeutronNetwork): Boolean =
        nn.hasNetworkType && nn.getNetworkType == NetworkType.UPLINK
}

private[translators] object NeutronNetworkUpdateValidator
        extends UpdateValidator[NeutronNetwork] {
    override def validate(oldNet: NeutronNetwork, newNet: NeutronNetwork)
    : NeutronNetwork = {
        if (oldNet.getSubnetsCount > 0)
            newNet.toBuilder.addAllSubnets(oldNet.getSubnetsList).build()
        else newNet
    }
}
