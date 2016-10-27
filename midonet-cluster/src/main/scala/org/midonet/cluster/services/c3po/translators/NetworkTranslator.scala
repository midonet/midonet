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
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Neutron.NeutronNetwork.NetworkType
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.state.PathBuilder

/** Provides a Neutron model translator for Network. */
class NetworkTranslator(pathBldr: PathBuilder)
    extends Translator[NeutronNetwork] {
    import NetworkTranslator._

    override protected def translateCreate(tx: Transaction,
                                           network: NeutronNetwork)
    : OperationList = {
        // Uplink networks do not exist in MidoNet.
        if (isUplinkNetwork(network)) {
            return List()
        }

        tx.create(translate(network))
        replMapPaths(network.getId).map(tx.createNode(_, null))
        List()
    }

    override protected def translateUpdate(tx: Transaction,
                                           nNetwork: NeutronNetwork)
    : OperationList = {
        // Uplink networks do not exist in MidoNet.
        if (isUplinkNetwork(nNetwork)) {
            return List()
        }

        val mNetwork = tx.get(classOf[Network], nNetwork.getId)
        val builder = mNetwork.toBuilder
            .setName(nNetwork.getName)
            .setAdminStateUp(nNetwork.getAdminStateUp)

        tx.update(builder.build())
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           nNetwork: NeutronNetwork)
    : OperationList = {
        // Uplink networks do not exist in MidoNet.
        if (isUplinkNetwork(nNetwork)) {
            return List()
        }

        tx.delete(classOf[Network], nNetwork.getId)
        replMapPaths(nNetwork.getId).map(tx.deleteNode(_))
        List()
    }

    @inline
    private def translate(network: NeutronNetwork) = Network.newBuilder()
        .setId(network.getId)
        .setTenantId(network.getTenantId)
        .setName(network.getName)
        .setAdminStateUp(network.getAdminStateUp)
        .build

    /** Paths for legacy replicated maps (ARP and MAC tables). */
    private def replMapPaths(networkId: UUID): Iterable[String] = {
        val id = networkId.asJava
        Iterable(pathBldr.getBridgeIP4MacMapPath(id),
                 pathBldr.getBridgeMacPortsPath(id),
                 pathBldr.getBridgeVlansPath(id))
    }
}

private[translators] object NetworkTranslator {
    def isUplinkNetwork(nn: NeutronNetwork): Boolean =
        nn.hasNetworkType && nn.getNetworkType == NetworkType.UPLINK
}
