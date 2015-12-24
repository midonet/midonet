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

import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.ROUTER_VTEP
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.c3po.C3POStorageManager.{Delete, Create}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps
import GatewayDeviceTranslator.vtepRouterPortId

class L2GatewayConnectionTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[L2GatewayConnection] {
    import L2GatewayConnectionTranslator._

    override protected def translateCreate(cnxn: L2GatewayConnection)
    : OperationList = {
        // TODO: Default timeout for await() has been changed to 30 seconds. Do
        // we really want that in the translators?
        val l2GwDevs = cnxn.getL2Gateway.getDevicesList
        val gwDevIds = l2GwDevs.map(_.getDeviceId)
        val gwDevs = storage.getAll(classOf[GatewayDevice], gwDevIds).await()
        val gwDev = gwDevs.find(_.getType == ROUTER_VTEP).getOrElse {
            // Only ROUTER_VTEP devices supported.
            return List()
        }

        // VNI can be set in either of these places.
        val vni = if (cnxn.hasSegmentationId) {
            cnxn.getSegmentationId
        } else {
            l2GwDevs.find(_.getDeviceId == gwDev.getId).get.getSegmentationId
        }

        val port = Port.newBuilder
            .setId(vtepNetworkPortId(cnxn.getId))
            .setNetworkId(cnxn.getNetworkId)
            .setPeerId(vtepRouterPortId(gwDev.getId, vni))
            .build()

        List(Create(port))
    }

    override protected def translateDelete(id: UUID): OperationList = {
        // There's no port to delete if the gateway device isn't of type
        // ROUTER_VTEP, but checking would require several lines of code and
        // mutiple trips to Zookeeper, and delete is idempotent, so just try to
        // delete.
        List(Delete(classOf[Port], vtepNetworkPortId(id)))
    }

    override protected def translateUpdate(cnxn: L2GatewayConnection)
    : OperationList = {
        // TODO: Implement.
        List()
    }

}

object L2GatewayConnectionTranslator {
    def vtepNetworkPortId(cnxnId: UUID): UUID =
        cnxnId.xorWith(0xaf59914959d45f8L, 0xb1b028de11f2ee4eL)
}

