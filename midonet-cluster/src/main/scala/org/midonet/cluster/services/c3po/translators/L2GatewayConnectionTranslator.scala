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
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.{NETWORK_VLAN, ROUTER_VTEP}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection, RemoteMacEntry}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete}
import org.midonet.cluster.services.c3po.midonet.{CreateNode, DeleteNode}
import org.midonet.cluster.services.c3po.translators.GatewayDeviceTranslator.UnSupportedGatewayDeviceType
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.state.PathBuilder
import org.midonet.util.concurrent.toFutureOps

class L2GatewayConnectionTranslator(protected val storage: ReadOnlyStorage,
                                    protected val stateTableStorage: StateTableStorage,
                                    protected val pathBldr: PathBuilder)
    extends Translator[L2GatewayConnection] with StateTableManager {
    import L2GatewayConnectionTranslator._


    private def translateRouterVtepCreate(cnxn: L2GatewayConnection,
                                          gwDev: GatewayDevice)
    : OperationList = {
        val nwPort = Port.newBuilder
            .setId(l2gwNetworkPortId(cnxn.getNetworkId))
            .setNetworkId(cnxn.getNetworkId)
            .build()

        val vni = cnxn.getSegmentationId
        val rtrPortBldr = Port.newBuilder
            .setId(l2gwGatewayPortId(cnxn.getNetworkId))
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
            CreateNode(portPeeringEntryPath(rtrPortBldr.getId.asJava,
                                            rm.getMacAddress,
                                            rm.getVtepAddress.getAddress))
        }

        List(Create(nwPort), Create(rtrPortBldr.build())) ++ rmOps
    }

    private def translateNetworkVlanCreate(cnxn: L2GatewayConnection,
                                           gwDev: GatewayDevice)
    : OperationList = {

        val vlanPort = Port.newBuilder
            .setId(l2gwGatewayPortId(cnxn.getNetworkId))
            .setNetworkId(gwDev.getResourceId)
            .setVlanId(cnxn.getSegmentationId)
            .build()

        val nwPort = Port.newBuilder
            .setId(l2gwNetworkPortId(cnxn.getNetworkId))
            .setNetworkId(cnxn.getNetworkId)
            .setPeerId(vlanPort.getId)
            .build()

        List(Create(vlanPort), Create(nwPort))
    }

    override protected def translateCreate(cnxn: L2GatewayConnection)
    : OperationList = {
        val l2GwDevs = cnxn.getL2Gateway.getDevicesList
        val gwDevIds = l2GwDevs.map(_.getDeviceId)
        val gwDevs = storage.getAll(classOf[GatewayDevice], gwDevIds).await()
        val gwDev = gwDevs.find(d => d.getType == ROUTER_VTEP ||
                                     d.getType == NETWORK_VLAN)

        gwDev match {
            case Some(d) if d.getType == ROUTER_VTEP =>
                translateRouterVtepCreate(cnxn, d)
            case Some(d) if d.getType == NETWORK_VLAN =>
                translateNetworkVlanCreate(cnxn, d)
            case None =>
                throw new IllegalArgumentException(UnSupportedGatewayDeviceType)
        }
    }

    override protected def translateDelete(id: UUID): OperationList = {
        val cnxn = try {
            storage.get(classOf[L2GatewayConnection], id).await()
        } catch {
            case ex: NotFoundException => return List() // Idempotent.
        }

        val gwPortId = l2gwGatewayPortId(cnxn.getNetworkId)
        val gwPort = storage.get(classOf[Port], gwPortId).await()

        // Even though remote mac entries are only applicable to VTEP router
        // case, it does no harm in other cases.
        val rms = storage.getAll(classOf[RemoteMacEntry],
                                 gwPort.getRemoteMacEntryIdsList).await()
        val rmOps = for (rm <- rms) yield {
            DeleteNode(portPeeringEntryPath(gwPortId.asJava, rm.getMacAddress,
                                            rm.getVtepAddress.getAddress))
        }

        Delete(classOf[Port], l2gwNetworkPortId(cnxn.getNetworkId)) ::
        Delete(classOf[Port], gwPortId) ::
        rmOps.toList
    }

    override protected def translateUpdate(cnxn: L2GatewayConnection)
    : OperationList = {
        // TODO: Implement.
        List()
    }

}

object L2GatewayConnectionTranslator {
    def l2gwNetworkPortId(nwId: UUID): UUID =
        nwId.xorWith(0xaf59914959d45f8L, 0xb1b028de11f2ee4eL)

    def l2gwGatewayPortId(nwId: UUID): UUID = {
        nwId.xorWith(0x5ad1c08827cb459eL, 0x80042ee7006a8210L)
    }
}

