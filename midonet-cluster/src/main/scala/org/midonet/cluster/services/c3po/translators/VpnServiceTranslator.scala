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

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, VpnService}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Operation, Update}
import org.midonet.cluster.util.UUIDUtil._

class VpnServiceTranslator
    extends Translator[VpnService] with ChainManager
            with RouteManager {

    // Does not create service container. Service container is created
    //  by the first vpn connection
    override protected def translateCreate(tx: Transaction,
                                           vpn: VpnService): Unit = {
        val routerId = vpn.getRouterId
        val router = tx.get(classOf[Router], routerId)

        // Nothing to do if the router already has a VPNService.
        // We only support one container per router now
        val existing = tx.getAll(classOf[VpnService], router.getVpnServiceIdsList)
        existing.headOption match {
            case Some(existingVpnService) =>
                val newVpnServiceBuilder = vpn.toBuilder
                    .setExternalIp(existingVpnService.getExternalIp)
                if (existingVpnService.hasContainerId) {
                    newVpnServiceBuilder
                        .setContainerId(existingVpnService.getContainerId)
                }
                tx.create(newVpnServiceBuilder.build())
                return
            case None =>
        }

        val neutronRouter = tx.get(classOf[NeutronRouter], routerId)
        if (!neutronRouter.hasGwPortId) {
            throw new IllegalArgumentException(
                "Cannot discover gateway port of router")
        }
        val neutronGatewayPort = tx.get(classOf[NeutronPort],
                                             neutronRouter.getGwPortId)
        val externalIp = if (neutronGatewayPort.getFixedIpsCount > 0) {
            neutronGatewayPort.getFixedIps(0).getIpAddress
        } else {
            throw new IllegalArgumentException(
                "Cannot discover gateway IP of router")
        }

        if (externalIp.getVersion != IPVersion.V4) {
            throw new IllegalArgumentException(
                "Only IPv4 endpoints are currently supported")
        }

        val modifiedVpnService = vpn.toBuilder
            .setExternalIp(externalIp)
            .build()
        tx.create(modifiedVpnService)
    }

    override protected def translateDelete(tx: Transaction,
                                           vpn: VpnService): Unit = {
        // ServiceContainer is removed by last deleted connection, so
        // no too much work to do here
    }

    override protected def translateUpdate(tx: Transaction,
                                           vpn: VpnService): Unit = {
        // No Midonet-specific changes, but changes to the VPNService are
        // handled in retainHighLevelModel().
    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[VpnService])
    : List[Operation[VpnService]] = op match {
        case Create(_) =>
            // Do nothing, the translator creates the VPN object.
            List()
        case Update(vpn, _) =>
            // Need to override update to make sure only certain fields are
            // updated, to avoid overwriting ipsec_site_conn_ids, which Neutron
            // doesn't know about.
            val oldVpn = tx.get(classOf[VpnService], vpn.getId)
            val newVpn = vpn.toBuilder
                .addAllIpsecSiteConnectionIds(oldVpn.getIpsecSiteConnectionIdsList)
                .setExternalIp(oldVpn.getExternalIp)
                if (oldVpn.hasContainerId) {
                    newVpn.setContainerId(oldVpn.getContainerId)
                }
            List(Update(newVpn.build()))
        case _ => super.retainHighLevelModel(tx, op)
    }
}

