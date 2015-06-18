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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, NeutronSubnet, NeutronNetwork, NeutronVIP}
import org.midonet.cluster.models.Topology.{Pool, Vip}
import org.midonet.cluster.services.c3po.midonet.{Create, CreateNode, Delete, DeleteNode, Update}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.midolman.state.PathBuilder
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for VIP. */
class VipTranslator(protected val storage: ReadOnlyStorage,
                    protected val pathBldr: PathBuilder)
        extends NeutronTranslator[NeutronVIP]
        with BridgeStateTableManager {

    private def translate(nVip: NeutronVIP): Vip.Builder = {
        val mVipBldr = Vip.newBuilder
                      .setId(nVip.getId)
                      .setAdminStateUp(nVip.getAdminStateUp)
        if (nVip.hasAddress) mVipBldr.setAddress(nVip.getAddress)
        if (nVip.hasProtocolPort) mVipBldr.setProtocolPort(nVip.getProtocolPort)
        if (nVip.hasSessionPersistence &&
            nVip.getSessionPersistence.getType ==
                NeutronVIP.SessionPersistence.Type.SOURCE_IP) {
            mVipBldr.setSessionPersistence(Vip.SessionPersistence.SOURCE_IP)
        }
        if (nVip.hasPoolId) {
            val pool = storage.get(classOf[Pool], nVip.getPoolId).await()
            mVipBldr.setPoolId(pool.getId)
            mVipBldr.setLoadBalancerId(pool.getLoadBalancerId)
        }
        mVipBldr
    }

    override protected def translateCreate(nVip: NeutronVIP) : MidoOpList = {
        val mVip = translate(nVip)

        val midoOps = new MidoOpListBuffer
        if (nVip.hasPoolId) {
            val subnet = storage.get(classOf[NeutronSubnet], nVip.getSubnetId)
                                .await()
            val networkId = subnet.getNetworkId
            val network = storage.get(classOf[NeutronNetwork],
                                      networkId).await()
            if (network.getExternal) {
                val pool = storage.get(classOf[Pool], nVip.getPoolId).await()
                val router = storage.get(classOf[NeutronRouter],
                                         pool.getLoadBalancerId).await()
                if (router.hasGwPortId) {
                    val gwPort = storage.get(classOf[NeutronPort],
                                             router.getGwPortId).await()
                    val arpPath = arpEntryPath(networkId,
                                               nVip.getAddress.getAddress,
                                               gwPort.getMacAddress)
                    midoOps += CreateNode(arpPath, null)
                    // Set a back reference from gateway port to VIP.
                    mVip.setGatewayPortId(router.getGwPortId)
                } else {
                    log.warn("VIP's associated to a Router without a gateway " +
                             "port. No ARP entry is added now, nor will be " +
                             "when the router is set a gateway port.")
                    // TODO: Update the ARP entry when a gateway port is set to
                    // to a tenant router with LB / VIP?
                }
            }
        }

        midoOps += Create(mVip.build())
        midoOps.toList
    }

    override protected def translateDelete(id: UUID) : MidoOpList = {
        val midoOps = new MidoOpListBuffer
        midoOps += Delete(classOf[Vip], id)

        val vip = storage.get(classOf[Vip], id).await()
        if (vip.hasGatewayPortId) {
            val gwPort = storage.get(classOf[NeutronPort],
                                     vip.getGatewayPortId).await()
            val arpPath = arpEntryPath(gwPort.getNetworkId,
                                       vip.getAddress.getAddress,
                                       gwPort.getMacAddress)
            midoOps += DeleteNode(arpPath)
        }

        midoOps.toList
    }

    override protected def translateUpdate(nVip: NeutronVIP) : MidoOpList = {
        // The specs don't allow the IP address of the VIP to change, and that
        // the MAC address of a port also does not change on the port update.
        // If the gateway port of the Router may be somehow changed, the ARP
        // entry for the VIP should be updated at that timing. Therefore, here
        // we don't need to update the ARP entry here and just check if the VIP
        // IP address is indeed not changed.
        val oldVip = storage.get(classOf[NeutronVIP], nVip.getId).await()
        if (oldVip.getAddress != nVip.getAddress) {
            log.error(s"VIP's IP changed from${oldVip.getAddress.getAddress} " +
                      s"to ${nVip.getAddress.getAddress}")
        }
        List(Update(translate(nVip).build()))
    }
}
