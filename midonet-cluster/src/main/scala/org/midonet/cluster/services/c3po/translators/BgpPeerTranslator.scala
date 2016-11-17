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

import scala.collection.JavaConverters._

import org.midonet.cluster.data.storage.{StateTableStorage, Transaction}
import org.midonet.cluster.models.Commons.{IPSubnet, UUID}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.RouteManager.extraRouteId
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, SequenceDispenser}
import org.midonet.containers
import org.midonet.packets.{MAC, TCP}

class BgpPeerTranslator(stateTableStorage: StateTableStorage,
                        sequenceDispenser: SequenceDispenser)
    extends Translator[NeutronBgpPeer] with RuleManager with PortManager
            with ChainManager {

    import BgpPeerTranslator._

    override protected def translateCreate(tx: Transaction,
                                           bgpPeer: NeutronBgpPeer): Unit = {
        val speaker = bgpPeer.getBgpSpeaker
        val router = tx.get(classOf[Router], speaker.getLogicalRouter)

        tx.update(router.toBuilder.setAsNumber(speaker.getLocalAs).build())
        ensureContainer(tx, router, speaker)
        ensureExtraRouteBgpNetworks(tx, router.getId)
        val chainId = ensureRedirectChain(tx, router)
        createBgpPeer(tx, router.getId, bgpPeer)
        createPeerRedirectRule(tx, redirectRuleId(bgpPeer.getId),
                               quaggaPortId(router.getId), chainId, bgpPeer,
                               inverse = false)
        createPeerRedirectRule(tx,  inverseRedirectRuleId(bgpPeer.getId),
                               quaggaPortId(router.getId), chainId, bgpPeer,
                               inverse = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           newPeer: NeutronBgpPeer): Unit = {
        val oldPeer = tx.get(classOf[BgpPeer], newPeer.getId)

        // Can only update password.
        if (oldPeer.getPassword != newPeer.getPassword) {
            tx.update(oldPeer.toBuilder.setPassword(newPeer.getPassword).build())
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           bgpPeer: NeutronBgpPeer): Unit = {
         val router = tx.get(classOf[Router],
                             bgpPeer.getBgpSpeaker.getLogicalRouter)

        if (router.getBgpPeerIdsList.size() == 1) {
            tx.update(router.toBuilder.clearAsNumber().build())
        }
        deleteBgpPeer(tx, router, bgpPeer.getId)
        cleanUpBgpNetworkExtraRoutes(tx, router.getId)
    }

    private def createBgpPeer(tx: Transaction, routerId: UUID,
                              neutronBgpPeer: NeutronBgpPeer): Unit = {
        tx.create(BgpPeer.newBuilder()
                      .setAddress(neutronBgpPeer.getPeerIp)
                      .setAsNumber(neutronBgpPeer.getRemoteAs)
                      .setId(neutronBgpPeer.getId)
                      .setRouterId(routerId)
                      .setPassword(neutronBgpPeer.getPassword)
                      .build())
    }

    private def createPeerRedirectRule(tx: Transaction, ruleId: UUID,
                                       portId: UUID, chainId: UUID,
                                       bgpPeer: NeutronBgpPeer, inverse: Boolean)
    : Unit = {
        val peerIp = IPSubnetUtil.fromAddress(bgpPeer.getPeerIp)

        val peerRuleBuilder = redirectRuleBuilder(
            id = Some(ruleId),
            chainId = chainId,
            targetPortId = portId)

        val condBuilder = peerRuleBuilder.getConditionBuilder
            .setNwSrcIp(peerIp)
            .setNwProto(TCP.PROTOCOL_NUMBER)

        if (inverse)
            condBuilder.setTpSrc(BgpPortRange)
        else
            condBuilder.setTpDst(BgpPortRange)

        tx.create(peerRuleBuilder.build())
    }

    private def ensureExtraRouteBgpNetworks(tx: Transaction, routerId: UUID)
    : Unit = {
        val router = tx.get(classOf[NeutronRouter], routerId)
        for (route <- router.getRoutesList.asScala) {
            tx.create(makeBgpNetworkFromRoute(routerId, route))
        }
    }

    private def ensureContainer(tx: Transaction, router: Router,
                                bgpSpeaker: NeutronBgpSpeaker): Unit = {
        val portId = quaggaPortId(router.getId)
        if (!tx.exists(classOf[Port], portId)) {
            createQuaggaRouterPort(tx, router)
            scheduleService(tx, portId, router.getId)
            addNetworks(tx, router)
        }
    }


    private def addNetworks(tx: Transaction, router: Router): Unit = {
        val routerPorts = tx.getAll(classOf[Port], router.getPortIdsList.asScala)

        val neutronPorts = tx.getAll(classOf[NeutronPort],
                                     routerPorts.map(_.getPeerId))

        val networks = tx.getAll(classOf[NeutronNetwork],
                                 neutronPorts.map(_.getNetworkId))

        for ((port, network) <- neutronPorts.zip(networks) if !network.getExternal) {
            val subnetId = port.getFixedIpsList.get(0).getSubnetId
            val subnet = tx.get(classOf[NeutronSubnet], subnetId)
            tx.create(makeBgpNetwork(router.getId, subnet.getCidr, port.getId))
        }
    }

    private def scheduleService(tx: Transaction, portId: UUID, routerId: UUID): Unit = {
        val serviceContainerGroup = ServiceContainerGroup.newBuilder
            .setId(quaggaContainerGroupId(routerId))
            .build()

        val serviceContainer = ServiceContainer.newBuilder
            .setId(quaggaContainerId(routerId))
            .setServiceGroupId(serviceContainerGroup.getId)
            .setPortId(portId)
            .setServiceType("QUAGGA")
            .setConfigurationId(routerId)
            .build()

        tx.create(serviceContainerGroup)
        tx.create(serviceContainer)
    }

    private def createQuaggaRouterPort(tx: Transaction, router: Router): Unit = {
        val currentPorts = tx.getAll(classOf[Port], router.getPortIdsList.asScala)

        val subnet = containers.findLocalSubnet(currentPorts)
        val routerAddress = containers.routerPortAddress(subnet)

        val builder = Port.newBuilder
            .setId(quaggaPortId(router.getId))
            .setRouterId(router.getId)
            .setPortSubnet(subnet.asProto)
            .setPortAddress(routerAddress.asProto)
            .setPortMac(MAC.random().toString)

        assignTunnelKey(builder, sequenceDispenser)
        tx.create(builder.build())
    }

    private def cleanUpBgpNetworkExtraRoutes(tx: Transaction, routerId: UUID): Unit = {
        val router = tx.get(classOf[NeutronRouter], routerId)
        for (route <- router.getRoutesList.asScala) {
            val routeId = bgpNetworkId(extraRouteId(routerId, route))
            tx.delete(classOf[BgpNetwork], routeId, ignoresNeo = true)
        }
    }
}

object BgpPeerTranslator {
    def quaggaContainerId(routerId: UUID): UUID =
        routerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    def quaggaContainerGroupId(routerId: UUID): UUID =
        routerId.xorWith(0x7d263d2d55da46d2L, 0xb53951e91eba3a1fL)

    def quaggaPortId(deviceId: UUID): UUID =
        deviceId.xorWith(0xff498a4c22390ae3L, 0x3e3ec848baff217dL)

    def bgpNetworkId(sourceId: UUID): UUID =
        sourceId.xorWith(0x39c62a620c7049a9L, 0xbc9c1acb80e516fcL)

    def redirectRuleId(peerId: UUID): UUID =
        peerId.xorWith(0x12d34babf7d84902L, 0xaa840971afc3307fL)

    def inverseRedirectRuleId(peerId: UUID): UUID =
        peerId.xorWith(0x12d34babf7d85900L, 0xaa840971afc3307fL)

    val BgpPortRange = RangeUtil.toProto(179, 179)

    def deleteBgpPeer(tx: Transaction, router: Router, bgpPeerId: UUID): Unit = {
        tx.delete(classOf[BgpPeer], bgpPeerId, ignoresNeo = true)
        tx.delete(classOf[Rule], redirectRuleId(bgpPeerId), ignoresNeo = true)
        tx.delete(classOf[Rule], inverseRedirectRuleId(bgpPeerId),
                  ignoresNeo = true)
    }

    def isBgpSpeakerConfigured(tx: Transaction, routerId: UUID) = {
        val peers = tx.getAll(classOf[NeutronBgpPeer])
        peers.exists(_.getBgpSpeaker.getLogicalRouter == routerId)
    }

    def makeBgpNetwork(routerId: UUID, subnet: IPSubnet, id: UUID)
    : BgpNetwork = {
        BgpNetwork.newBuilder()
            .setId(bgpNetworkId(id))
            .setRouterId(routerId)
            .setSubnet(subnet)
            .build()
    }

    def makeBgpNetworkFromRoute(routerId: UUID, route: NeutronRoute)
    : BgpNetwork = {
        makeBgpNetwork(routerId, route.getDestination,
                       extraRouteId(routerId, route))
    }
}
