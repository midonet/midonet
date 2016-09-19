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
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Neutron.NeutronNetwork.NetworkType
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for Network. */
class NetworkTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[NeutronNetwork] {
    import NetworkTranslator._

    override protected def translateCreate(nn: NeutronNetwork): OperationList = {
        // Uplink networks don't exist in Midonet.
        if (isUplinkNetwork(nn)) return List()

        val ops = new OperationListBuffer
        ops += Create(translate(nn))
        ops.toList
    }

    override protected def translateUpdate(nn: NeutronNetwork): OperationList = {
        // Uplink networks don't exist in Midonet, and regular networks can't
        // be turned into uplink networks via update.
        if (isUplinkNetwork(nn)) return List()

        val mNet = storage.get(classOf[Network], nn.getId).await()
        val bldr = mNet.toBuilder
            .setName(nn.getName)
            .setAdminStateUp(nn.getAdminStateUp)

        List(Update(bldr.build()))
    }

    override protected def translateDelete(nn: NeutronNetwork)
    : OperationList = {
        // Uplink networks don't exist in Midonet.
        if (isUplinkNetwork(nn)) return List()

        val ops = new OperationListBuffer
        ops += Delete(classOf[Network], nn.getId)
        ops.toList
    }

    @inline
    private def translate(network: NeutronNetwork) = Network.newBuilder()
        .setId(network.getId)
        .setTenantId(network.getTenantId)
        .setName(network.getName)
        .setAdminStateUp(network.getAdminStateUp)
        .build
}

private[translators] object NetworkTranslator {
    def isUplinkNetwork(nn: NeutronNetwork): Boolean =
        nn.hasNetworkType && nn.getNetworkType == NetworkType.UPLINK
}
