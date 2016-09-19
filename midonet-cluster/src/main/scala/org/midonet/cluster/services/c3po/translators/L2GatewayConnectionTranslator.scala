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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage, Transaction}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.{NETWORK_VLAN, ROUTER_VTEP}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection, RemoteMacEntry}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.rest_api.validation.MessageProperty.{ONLY_ONE_GW_DEV_SUPPORTED, UNSUPPORTED_GATEWAY_DEVICE}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, CreateNode, Delete, DeleteNode}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps

class L2GatewayConnectionTranslator(protected val storage: ReadOnlyStorage,
                                    protected val stateTableStorage: StateTableStorage)
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
            CreateNode(stateTableStorage.portPeeringEntryPath(
                rtrPortBldr.getId.asJava,
                MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)))
        }

        List(Create(nwPort), Create(rtrPortBldr.build())) ++ rmOps
    }

    private def translateNetworkVlanCreate(cnxn: L2GatewayConnection,
                                           gwDev: GatewayDevice)
    : OperationList = {

        val nwPort = Port.newBuilder
            .setId(l2gwNetworkPortId(cnxn.getNetworkId))
            .setNetworkId(cnxn.getNetworkId)
            .build()

        val vlanPort = Port.newBuilder
            .setId(l2gwGatewayPortId(cnxn.getNetworkId))
            .setNetworkId(gwDev.getResourceId)
            .setVlanId(cnxn.getSegmentationId)
            .setPeerId(nwPort.getId)
            .build()

        List(Create(nwPort), Create(vlanPort))
    }

    override protected def translateCreate(tx: Transaction,
                                           cnxn: L2GatewayConnection)
    : OperationList = {
        if (cnxn.getL2Gateway.getDevicesCount != 1) {
            throw new IllegalArgumentException(ONLY_ONE_GW_DEV_SUPPORTED)
        }
        val gwDevId = cnxn.getL2Gateway.getDevices(0).getDeviceId
        val gwDev = storage.get(classOf[GatewayDevice], gwDevId).await()

        gwDev.getType match {
            case ROUTER_VTEP =>
                translateRouterVtepCreate(cnxn, gwDev)
            case NETWORK_VLAN =>
                translateNetworkVlanCreate(cnxn, gwDev)
            case _ =>
                throw new IllegalArgumentException(UNSUPPORTED_GATEWAY_DEVICE)
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           cnxn: L2GatewayConnection)
    : OperationList = {
        val gwPortId = l2gwGatewayPortId(cnxn.getNetworkId)
        val gwPort = storage.get(classOf[Port], gwPortId).await()

        // Even though remote mac entries are only applicable to VTEP router
        // case, it does no harm in other cases to do a fetch here.
        val rms = storage.getAll(classOf[RemoteMacEntry],
                                 gwPort.getRemoteMacEntryIdsList).await()
        val rmOps = for (rm <- rms) yield {
            DeleteNode(stateTableStorage.portPeeringEntryPath(
                gwPortId.asJava,
                MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)))
        }

        Delete(classOf[Port], l2gwNetworkPortId(cnxn.getNetworkId)) ::
        Delete(classOf[Port], gwPortId) ::
        rmOps.toList
    }

    override protected def translateUpdate(tx: Transaction,
                                           cnxn: L2GatewayConnection)
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

