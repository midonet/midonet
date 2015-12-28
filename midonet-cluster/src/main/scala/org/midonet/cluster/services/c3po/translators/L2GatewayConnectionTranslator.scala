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

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.ROUTER_VTEP
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection, RemoteMacEntry}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete}
import org.midonet.cluster.services.c3po.midonet.{CreateNode, DeleteNode}
import org.midonet.cluster.services.c3po.translators.GatewayDeviceTranslator.OnlyRouterVtepSupported
import org.midonet.cluster.services.c3po.translators.RemoteMacEntryTranslator.remoteMacEntryPath
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

class L2GatewayConnectionTranslator(protected val storage: ReadOnlyStorage,
                                    protected val stateTable: StateTableStorage)
    extends Translator[L2GatewayConnection] {
    import L2GatewayConnectionTranslator._


    override protected def translateCreate(cnxn: L2GatewayConnection)
    : OperationList = {
        val l2GwDevs = cnxn.getL2Gateway.getDevicesList
        val gwDevIds = l2GwDevs.map(_.getDeviceId)
        val gwDevs = storage.getAll(classOf[GatewayDevice], gwDevIds).await()
        val gwDev = gwDevs.find(_.getType == ROUTER_VTEP).getOrElse {
            throw new IllegalArgumentException(OnlyRouterVtepSupported)
        }

        val nwPort = Port.newBuilder
            .setId(vtepNetworkPortId(cnxn.getNetworkId))
            .setNetworkId(cnxn.getNetworkId)
            .build()

        val vni = cnxn.getSegmentationId
        val rtrPortBldr = Port.newBuilder
            .setId(vtepRouterPortId(cnxn.getNetworkId))
            .setPeerId(nwPort.getId)
            .setRouterId(gwDev.getResourceId)
            .setTunnelIp(gwDev.getTunnelIps(0))
            .setVni(vni)

        // TODO: Find a better way than scanning all the RemoteMacEntries for
        // the specified gateway device.
        val rms = storage.getAll(classOf[RemoteMacEntry],
                                 gwDev.getRemoteMacEntryIdsList).await()
        val rmOps = for (rm <- rms if rm.getSegmentationId == vni) yield {
            rtrPortBldr.addRemoteMacEntryIds(rm.getId)
            CreateNode(remoteMacEntryPath(rm, rtrPortBldr.getId.asJava,
                                          stateTable))
        }

        List(Create(nwPort), Create(rtrPortBldr.build())) ++ rmOps
    }

    override protected def translateDelete(id: UUID): OperationList = {
        val cnxn = try {
            storage.get(classOf[L2GatewayConnection], id).await()
        } catch {
            case ex: NotFoundException => return List() // Idempotent.
        }

        val rtrPortId = vtepRouterPortId(cnxn.getNetworkId)
        val rtrPort = storage.get(classOf[Port], rtrPortId).await()
        val rms = storage.getAll(classOf[RemoteMacEntry],
                                 rtrPort.getRemoteMacEntryIdsList).await()
        val rmOps = for (rm <- rms) yield {
            val path = remoteMacEntryPath(rm, rtrPortId.asJava, stateTable)
            DeleteNode(path)
        }

        Delete(classOf[Port], vtepNetworkPortId(id)) ::
        Delete(classOf[Port], rtrPortId) ::
        rmOps.toList
    }

    override protected def translateUpdate(cnxn: L2GatewayConnection)
    : OperationList = {
        // TODO: Implement.
        List()
    }

}

object L2GatewayConnectionTranslator {
    def vtepNetworkPortId(nwId: UUID): UUID =
        nwId.xorWith(0xaf59914959d45f8L, 0xb1b028de11f2ee4eL)

    def vtepRouterPortId(nwId: UUID): UUID = {
        nwId.xorWith(0x5ad1c08827cb459eL, 0x80042ee7006a8210L)
    }
}

