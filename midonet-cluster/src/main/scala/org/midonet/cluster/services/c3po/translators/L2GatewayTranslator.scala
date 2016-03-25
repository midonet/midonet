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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.L2Gateway
import org.midonet.cluster.models.Neutron.L2Gateway.L2GatewayDevice
import org.midonet.cluster.models.Topology.{Network, Port}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{SequenceDispenser, UUIDUtil}
import org.midonet.midolman.state.PathBuilder
import org.midonet.util.concurrent.toFutureOps

import scala.collection.JavaConversions._

class L2GatewayTranslator(protected val storage: ReadOnlyStorage,
                          pathBldr: PathBuilder,
                          sequenceDispenser: SequenceDispenser)
    extends Translator[L2Gateway] with PortManager {

    import L2GatewayTranslator._

    private def createOps(gw: L2Gateway): OperationList = {
        // VLAN Aware Bridge is just a regular MidoNet network.  It becomes VAB
        // when a port with a VLAN ID is created on it.
        val netId = midoVlanAwareNetworkId(gw.getId)
        val vlanNet = Network.newBuilder()
            .setId(netId)
            .setAdminStateUp(true)
            .setTenantId(gw.getTenantId)
            .setName(gw.getName)
            .build()

        val processedBinding = collection.mutable.Set[(UUID, String)]()
        val hostCache = collection.mutable.Map[String, UUID]()
        val portOps = gw.getDevicesList.flatMap(d => {

            val hostId = hostCache.get(d.getDeviceName) match {
                case Some(h) => h
                case None =>
                    // NotFoundException thrown if this host cannot be found
                    val h = getHostIdByName(d.getDeviceName)
                    hostCache.put(hostId, h)
                    h
            }

            // For each interface, create a port and bind it.
            d.getInterfacesList.map(intf => {
                val intfName = intf.getName
                val binding = (hostId, intfName)
                if (!processedBinding.contains(binding)) {
                    processedBinding.add(binding)

                    val portBldr = Port.newBuilder()
                        .setId(UUIDUtil.randomUuidProto)
                        .setAdminStateUp(true)
                        .setNetworkId(netId)
                        .setHostId(hostId)
                        .setInterfaceName(intfName)
                    assignTunnelKey(portBldr, sequenceDispenser)

                    Create(portBldr.build())
                }
            })
        })

        List(Create(vlanNet)) ++ portOps
    }

    private def deleteOps(gw: L2Gateway): OperationList = {
        // It is assumed that Neutron would prevent L2Gateway deletion if it
        // has L2GatewayConnection association.
        List(Delete(classOf[Network], midoVlanAwareNetworkId(gw.getId)))
    }

    private def updateOps(gw: L2Gateway): OperationList = {
        val oldGw = storage.get(classOf[L2Gateway], gw.getId).await()
        val netId = midoVlanAwareNetworkId(gw.getId)

        val addUpdateOps = gw.getDevicesList.map(d => {

            val hostId = d.getDeviceName
            val oldDevs = oldGw.getDevicesList.filter(_.getDeviceName == hostId)
            if (oldDevs.isEmpty) {
                // new host - create/bind ports
                portCreateOps(netId, d)
            } else {
                // known host - check interfaces
                val oldIntfs = oldDevs.flatMap(
                    _.getInterfacesList).map(_.getName)
                val newIntfs = d.getInterfacesList.map(_.getName)

            }
        }).toList

        // Remove bindings that no longer exist
        val delOps = oldGw.getDevicesList.map(d => {

            val hostId = d.getDeviceName
            val delDevs = gw.getDevicesList.filter(_.getDeviceName != hostId)
            delDevs.map(dd => {
          //      val h = storage.get(classOf[Host], )
            })
        }).toList

        delOps ++ addUpdateOps
    }

    override protected def translateCreate(gw: L2Gateway): OperationList = {
        if(isVlanGw(gw))
            createOps(gw)
        else if(isRouterVtep(gw))
            List()
        else
            throw new IllegalArgumentException(
                s"Bad L2Gateway object sent: $gw")
    }

    override protected def translateDelete(id: UUID): OperationList = {
        val gw = storage.get(classOf[L2Gateway], id).await()
        deleteOps(gw)
    }

    override protected def translateUpdate(gw: L2Gateway): OperationList = {
        if (isVlanGw(gw))
            updateOps(gw)
        else if (isRouterVtep(gw))
            List()
        else
            throw new IllegalArgumentException(
                s"Bad L2Gateway object sent: $gw")
    }
}

object L2GatewayTranslator {

    /**
      * Returns true if this L2Gateway object is of type Router VTEP gateway.
      */
    def isRouterVtep(gw: L2Gateway): Boolean =
        gw.getDevicesCount == 1 && gw.getDevices(0).hasDeviceId

    /**
      * Returns true if this L2GatewayDevice has the required fields set to be
      * a VLAN GW device.
      */
    def validVlanGwDevice(d: L2GatewayDevice): Boolean = {
        !d.hasDeviceId && d.hasDeviceName && !d.hasSegmentationId &&
            d.getInterfacesCount > 0 && !d.getInterfacesList.exists(!_.hasName)
    }

    /**
      * Returns true if this L2Gateway object is of type VLAN L2 gateway
      */
    def isVlanGw(gw: L2Gateway): Boolean = {
        gw.getDevicesCount > 0 &&
            !gw.getDevicesList.exists(!validVlanGwDevice(_))
    }

    /**
      * Returns the ID to use for MidoNet VLAN-Aware network derived from this
      * Neutron L2Gateway ID
      */
    def midoVlanAwareNetworkId(gwId: UUID): UUID =
        gwId.xorWith(0x2624cbbef1cf11e5L, 0xa6db0242ac110001L)
}

