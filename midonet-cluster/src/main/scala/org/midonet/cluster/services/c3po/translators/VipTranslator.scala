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

import org.midonet.cluster.data.storage.{StateTableStorage, Transaction, UpdateValidator}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology.Vip
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.packets.{IPv4Addr, MAC}

/** Provides a Neutron model translator for VIP. */
class VipTranslator(stateTableStorage: StateTableStorage)
    extends Translator[NeutronVIP] {

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
            mVipBldr.setPoolId(nVip.getPoolId)
        }
        mVipBldr
    }

    override protected def translateCreate(tx: Transaction,
                                           nVip: NeutronVIP) : OperationList = {
        val mVip = translate(nVip)

        // VIP is not associated with LB. Don't add an ARP entry yet.
        if (!nVip.hasPoolId) {
            tx.create(mVip.build())
            return List()
        }

        val subnet = tx.get(classOf[NeutronSubnet], nVip.getSubnetId)
        val networkId = subnet.getNetworkId
        val network = tx.get(classOf[NeutronNetwork], networkId)
        // If the VIP's DHCP is not on external, no need to add an ARP entry.
        if (!network.getExternal) {
            tx.create(mVip.build())
            return List()
        }

        val pool = tx.get(classOf[NeutronLoadBalancerPool], mVip.getPoolId)
        val router = tx.get(classOf[NeutronRouter], pool.getRouterId)
        if (router.hasGwPortId) {
            val gwPort = tx.get(classOf[NeutronPort], router.getGwPortId)
            val arpPath = stateTableStorage.bridgeArpEntryPath(
                networkId,
                IPv4Addr(nVip.getAddress.getAddress),
                MAC.fromString(gwPort.getMacAddress))
            tx.createNode(arpPath)
            // Set a back reference from gateway port to VIP.
            mVip.setGatewayPortId(router.getGwPortId)
        } else {
            // Neutron guarantees that the gateway port exists for the router
            // when a VIP is assigned.
            log.warn("VIP's associated to a Router without a gateway " +
                     "port. No ARP entry is added now, nor will be " +
                     "when the router is set a gateway port.")
        }

        tx.create(mVip.build())
        List()
    }

    override protected def translateDelete(tx: Transaction,
                                           nv: NeutronVIP) : OperationList = {
        val vip = tx.get(classOf[Vip], nv.getId)
        tx.delete(classOf[Vip], nv.getId, ignoresNeo = true)

        if (vip.hasGatewayPortId) {
            val gwPort = tx.get(classOf[NeutronPort], vip.getGatewayPortId)
            val arpPath = stateTableStorage.bridgeArpEntryPath(
                gwPort.getNetworkId,
                IPv4Addr(vip.getAddress.getAddress),
                MAC.fromString(gwPort.getMacAddress))
            tx.deleteNode(arpPath)
        }

        List()
    }

    override protected def translateUpdate(tx: Transaction,
                                           nVip: NeutronVIP) : OperationList = {
        // The specs don't allow the IP address of the VIP to change, and that
        // the MAC address of a port also does not change on the port update.
        // If the gateway port of the Router may be somehow changed, the ARP
        // entry for the VIP should be updated at that timing. Therefore, here
        // we don't need to update the ARP entry here and just check if the VIP
        // IP address is indeed not changed.
        val oldVip = tx.get(classOf[NeutronVIP], nVip.getId)
        if (oldVip.getAddress != nVip.getAddress) {
            throw new IllegalArgumentException(
                    s"VIP IP changed from ${oldVip.getAddress.getAddress} " +
                    s"to ${nVip.getAddress.getAddress}")
        }

        // TODO: Update the ARP entry when the VIP has been moved from one Pool
        // to another.
        // Can a VIP be moved to a Pool on a different LB? I doubt that.
        // So we only need to worry about a Pool in the same LB.
        tx.update(translate(nVip).build(),
                  VipUpdateValidator.asInstanceOf[UpdateValidator[Object]])
        List()
    }
}

private[translators] object VipUpdateValidator extends UpdateValidator[Vip] {
    override def validate(oldVip: Vip, newVip: Vip) : Vip = {
        val validatedUpdateBldr = newVip.toBuilder
        if (oldVip.hasGatewayPortId) {
            validatedUpdateBldr.setGatewayPortId(oldVip.getGatewayPortId)
        }
        validatedUpdateBldr.build
    }
}
