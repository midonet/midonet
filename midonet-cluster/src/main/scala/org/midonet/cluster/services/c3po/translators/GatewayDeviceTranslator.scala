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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.{NETWORK_VLAN, ROUTER_VTEP}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.rest_api.validation.MessageProperty.UNSUPPORTED_GATEWAY_DEVICE
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Update
import org.midonet.util.concurrent.toFutureOps

class GatewayDeviceTranslator(protected val storage: ReadOnlyStorage,
                              protected val stateTableStorage: StateTableStorage)
    extends Translator[GatewayDevice] {
    import L2GatewayConnectionTranslator._

    override protected def translateCreate(gwDev: GatewayDevice)
    : OperationList = {
        // Only router VTEP and network VLAN are supported.
        if (gwDev.getType != ROUTER_VTEP && gwDev.getType != NETWORK_VLAN)
            throw new IllegalArgumentException(UNSUPPORTED_GATEWAY_DEVICE)

        // Nothing to do other than pass the Neutron data through to ZK.
        List()
    }

    override protected def translateDelete(gwDev: GatewayDevice)
    : OperationList = {
        // Nothing to do but delete the Neutron data.
        List()
    }

    override protected def translateUpdate(gwDev: GatewayDevice)
    : OperationList = {

        def hasThisGW(conn: L2GatewayConnection) = {
            val devs = conn.getL2Gateway.getDevicesList.asScala
            devs.exists(_.getDeviceId == gwDev.getId)
        }

        def updatePortWithTunnelIp(conn: L2GatewayConnection) = {
            val portId = l2gwGatewayPortId(conn.getNetworkId)
            val port = storage.get(classOf[Port], portId).await()
            val updatedPort = port.toBuilder
                                  .setTunnelIp(gwDev.getTunnelIps(0))
                                  .build()
            Update(updatedPort)
        }

        /*
         * All of the ports that were assigned this tunnel IP need to have
         * their tunnel IPs updated. We don't have forward references to
         * the L2GatewayConnections using this gateway device, so we have
         * go through them all and update when needed.
         */
        storage.getAll(classOf[L2GatewayConnection]).await()
            .filter(hasThisGW)
            .map(updatePortWithTunnelIp)
            .toList
    }
}
