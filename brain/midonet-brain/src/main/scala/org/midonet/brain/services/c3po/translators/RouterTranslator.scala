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

package org.midonet.brain.services.c3po.translators

import org.midonet.brain.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter}
import org.midonet.cluster.models.Topology.{Chain, Port, Router}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps

class RouterTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronRouter] {
    import org.midonet.brain.services.c3po.translators.RouterTranslator._

    override protected def translateCreate(nr: NeutronRouter): MidoOpList = {
        val router = translate(nr)
        val inChain = createChain(router.getInboundFilterId,
                                  preRouteChainName(nr.getId))
        val outChain = createChain(router.getOutboundFilterId,
                                   postRouteChainName(nr.getId))

        val gwPortOps = gwPortLinkOps(nr)

        // TODO: SNAT rules.

        val ops = new MidoOpListBuffer
        ops += Create(inChain)
        ops += Create(outChain)
        ops += Create(router)
        ops ++= gwPortOps
        ops.toList
    }
    override protected def translateDelete(id: UUID): MidoOpList = {
        // TODO: Probably more to do when there's a gateway port or NAT rules.
        val chainIds = getChainIds(id)
        List(Delete(classOf[Chain], chainIds.inChainId),
             Delete(classOf[Chain], chainIds.outChainId),
             Delete(classOf[Router], id))
    }

    override protected def translateUpdate(nr: NeutronRouter): MidoOpList = {
        // TODO: Probably more to do when there's a gateway port or NAT rules.
        List(Update(translate(nr)))
    }

    private def translate(nr: NeutronRouter): Router = {
        val chainIds = getChainIds(nr.getId)
        val bldr = Router.newBuilder()
        bldr.setId(nr.getId)
        bldr.setInboundFilterId(chainIds.inChainId)
        bldr.setOutboundFilterId(chainIds.outChainId)
        if (nr.hasTenantId) bldr.setTenantId(nr.getTenantId)
        if (nr.hasName) bldr.setName(nr.getName)
        if (nr.hasAdminStateUp) bldr.setAdminStateUp(nr.getAdminStateUp)
        if (nr.hasGwPortId) bldr.setGwPortId(nr.getGwPortId)
        bldr.build()
    }

    private def gwPortLinkOps(nr: NeutronRouter): MidoOpList = {
        if (!nr.hasGwPortId) return List()

        val gwPort = storage.get(classOf[NeutronPort], nr.getGwPortId).await()
        val gwIpAddr = gwPort.getFixedIps(0).getIpAddress

        // Create port on tenant router to link to gateway port.
        val rPort = Port.newBuilder()
                        .setId(UUIDUtil.randomUuidProto)
                        .setRouterId(nr.getId)
                        .setPortSubnet(LL_CIDR)
                        .setPortAddress(gwIpAddr)
                        .setAdminStateUp(true)
                        .setPortMac(MAC.random().toString).build()

        // TODO: Route creation.

        // TODO: Actually return something.
        List()
    }
}

protected[translators] object RouterTranslator {

    def preRouteChainName(id: UUID) = "OS_PRE_ROUTING_" + id.asJava
    def postRouteChainName(id: UUID) = "OS_PORT_ROUTING_" + id.asJava

    val providerRouterId = UUID.newBuilder()
                               .setMsb(0xf7a6a8153dc44c28L)
                               .setLsb(0xb627646bf183e517L).build()

    val providerRouterName = "Midonet Provider Router"

    val LL_CIDR = IPSubnet.newBuilder()
                          .setAddress("169.254.255.0")
                          .setPrefixLength(30)
                          .setVersion(IPVersion.V4).build()

    val LL_GW_IP_1 = IPAddress.newBuilder()
                              .setAddress("169.254.255.1")
                              .setVersion(IPVersion.V4).build()
}
