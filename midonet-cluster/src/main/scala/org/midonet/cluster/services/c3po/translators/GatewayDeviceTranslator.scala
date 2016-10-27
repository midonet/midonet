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

import org.midonet.cluster.data.storage.{StateTableStorage, Transaction}
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.{NETWORK_VLAN, ROUTER_VTEP}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.rest_api.validation.MessageProperty.UNSUPPORTED_GATEWAY_DEVICE

class GatewayDeviceTranslator(stateTableStorage: StateTableStorage)
    extends Translator[GatewayDevice] {
    import L2GatewayConnectionTranslator._

    override protected def translateCreate(tx: Transaction,
                                           gatewayDevice: GatewayDevice)
    : Unit = {
        // Only router VTEP and network VLAN are supported.
        if (gatewayDevice.getType != ROUTER_VTEP &&
            gatewayDevice.getType != NETWORK_VLAN)
            throw new IllegalArgumentException(UNSUPPORTED_GATEWAY_DEVICE)

        // Nothing to do other than pass the Neutron data through to ZK.
    }

    override protected def translateDelete(tx: Transaction,
                                           gatewayDevice: GatewayDevice)
    : Unit = {
        // Nothing to do but delete the Neutron data.
    }

    override protected def translateUpdate(tx: Transaction,
                                           gatewayDevice: GatewayDevice)
    : Unit = {

        def hasThisGateway(connection: L2GatewayConnection) = {
            val devices = connection.getL2Gateway.getDevicesList.asScala
            devices.exists(_.getDeviceId == gatewayDevice.getId)
        }

        /*
         * All of the ports that were assigned this tunnel IP need to have
         * their tunnel IPs updated. We don't have forward references to
         * the L2GatewayConnections using this gateway device, so we have
         * go through them all and update when needed.
         */
        for (connection <- tx.getAll(classOf[L2GatewayConnection])
             if hasThisGateway(connection)) {

            val portId = l2gwGatewayPortId(connection.getNetworkId)
            val port = tx.get(classOf[Port], portId)
            val updatedPort = port.toBuilder
                .setTunnelIp(gatewayDevice.getTunnelIps(0))
                .build()
            tx.update(updatedPort)
        }
    }
}
