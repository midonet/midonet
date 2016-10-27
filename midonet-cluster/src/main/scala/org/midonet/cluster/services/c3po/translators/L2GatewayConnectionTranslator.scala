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

import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.{StateTableStorage, Transaction}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.{NETWORK_VLAN, ROUTER_VTEP}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection, RemoteMacEntry}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.rest_api.validation.MessageProperty.{ONLY_ONE_GW_DEV_SUPPORTED, UNSUPPORTED_GATEWAY_DEVICE}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{IPv4Addr, MAC}

class L2GatewayConnectionTranslator(stateTableStorage: StateTableStorage)
    extends Translator[L2GatewayConnection] {

    import L2GatewayConnectionTranslator._

    private def translateRouterVtepCreate(tx: Transaction,
                                          cnxn: L2GatewayConnection,
                                          gwDev: GatewayDevice): Unit = {
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
        val rms = tx.getAll(classOf[RemoteMacEntry],
                            gwDev.getRemoteMacEntryIdsList)
        for (rm <- rms if rm.getSegmentationId == vni) {
            rtrPortBldr.addRemoteMacEntryIds(rm.getId)
            tx.createNode(stateTableStorage.portPeeringEntryPath(
                rtrPortBldr.getId.asJava,
                MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)))
        }

        tx.create(nwPort)
        tx.create(rtrPortBldr.build())
    }

    private def translateNetworkVlanCreate(tx: Transaction,
                                           cnxn: L2GatewayConnection,
                                           gwDev: GatewayDevice): Unit = {

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

        tx.create(nwPort)
        tx.create(vlanPort)
    }

    override protected def translateCreate(tx: Transaction,
                                           cnxn: L2GatewayConnection): Unit = {
        if (cnxn.getL2Gateway.getDevicesCount != 1) {
            throw new IllegalArgumentException(ONLY_ONE_GW_DEV_SUPPORTED)
        }

        val gwDevId = cnxn.getL2Gateway.getDevices(0).getDeviceId
        val gwDev = tx.get(classOf[GatewayDevice], gwDevId)

        gwDev.getType match {
            case ROUTER_VTEP =>
                translateRouterVtepCreate(tx, cnxn, gwDev)
            case NETWORK_VLAN =>
                translateNetworkVlanCreate(tx, cnxn, gwDev)
            case _ =>
                throw new IllegalArgumentException(UNSUPPORTED_GATEWAY_DEVICE)
        }

    }

    override protected def translateDelete(tx: Transaction,
                                           cnxn: L2GatewayConnection): Unit = {
        val gwPortId = l2gwGatewayPortId(cnxn.getNetworkId)
        val gwPort = tx.get(classOf[Port], gwPortId)

        // Even though remote mac entries are only applicable to VTEP router
        // case, it does no harm in other cases to do a fetch here.
        val remoteMacEntries = tx.getAll(classOf[RemoteMacEntry],
                                         gwPort.getRemoteMacEntryIdsList)
        for (remoteMacEntry <- remoteMacEntries) {
            tx.deleteNode(
                stateTableStorage.portPeeringEntryPath(
                    gwPortId.asJava,
                    MAC.fromString(remoteMacEntry.getMacAddress),
                    IPv4Addr(remoteMacEntry.getVtepAddress.getAddress)))
        }

        tx.delete(classOf[Port], l2gwNetworkPortId(cnxn.getNetworkId),
                  ignoresNeo = true)
        tx.delete(classOf[Port], gwPortId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           cnxn: L2GatewayConnection)
    : Unit = {
        // TODO: Implement.
    }

}

object L2GatewayConnectionTranslator {
    def l2gwNetworkPortId(nwId: UUID): UUID =
        nwId.xorWith(0xaf59914959d45f8L, 0xb1b028de11f2ee4eL)

    def l2gwGatewayPortId(nwId: UUID): UUID = {
        nwId.xorWith(0x5ad1c08827cb459eL, 0x80042ee7006a8210L)
    }
}

