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

import java.util.{UUID => JUUID}

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.models.Neutron._
import org.midonet.containers
import org.midonet.util.concurrent.toFutureOps


import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Operation, Update}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{UUIDUtil, IPSubnetUtil, RangeUtil, SequenceDispenser}
import org.midonet.containers
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps



class BgpSpeakerTranslator(protected val storage: ReadOnlyStorage,
                           protected val stateTableStorage: StateTableStorage,
                           sequenceDispenser: SequenceDispenser)
    extends Translator[NeutronBgpSpeaker] with PortManager
                                          with RuleManager {

    import BgpSpeakerTranslator._

    override protected def translateCreate(bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {

        val ops = new OperationListBuffer

        val routerId = bgpSpeaker.getRouterId
        val router = storage.get(classOf[Router], routerId).await()
        val rBldr = router.toBuilder

        ops ++= createQuaggaRouterPort(router)
        ops ++= scheduleService(quaggaPortId(router.getId), routerId)
        ops ++= ensureRedirectChain(rBldr)
        ops ++= addPeers(rBldr, bgpSpeaker)
        ops ++= addNetworks(rBldr)

        ops += Update(rBldr.build())

        ops.toList
    }

    override protected def translateUpdate(bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        val ops = new OperationListBuffer

        ops.toList
    }

    override protected def translateDelete(bgpSpeakerId: UUID): OperationList = {
        val ops = new OperationListBuffer

        val speaker = storage.get(classOf[NeutronBgpSpeaker], bgpSpeakerId).await()
        val container = storage.get(classOf[ServiceContainer], quaggaContainerId(speaker.getRouterId)).await()
        val router = storage.get(classOf[Router], speaker.getRouterId).await()
        val rBldr = router.toBuilder

        ops += Delete(classOf[Port], quaggaPortId(speaker.getRouterId))
        ops += Delete(classOf[ServiceContainer], container.getId)
        ops += Delete(classOf[ServiceContainerGroup], container.getServiceGroupId)

        val chainId = router.getLocalRedirectChainId
        val chain = storage.get(classOf[Chain], chainId).await()
        val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList()).await()
        val peers = storage.getAll(classOf[NeutronBgpPeer], speaker.getPeersList).await()

        ops ++= removeRedirectRules(peers, rules)
        ops ++= removePeers(rBldr)
        ops ++= removeNetworks(rBldr)

        ops += Update(router)

        ops.toList
    }

    def removePeers(rBldr: Router.Builder): OperationList = {
        val ops = new OperationListBuffer

        ops ++= rBldr.getBgpPeerIdsList.map(Delete(classOf[BgpPeer], _))
        rBldr.clearBgpPeerIds

        ops.toList
    }

    def removeNetworks(rBldr: Router.Builder): OperationList = {
        val ops = new OperationListBuffer

        ops ++= rBldr.getBgpNetworkIdsList.map(Delete(classOf[BgpNetwork], _))
        rBldr.clearBgpNetworkIds()

        ops.toList
    }

    def removeRedirectRules(peers: Seq[NeutronBgpPeer], rules: Seq[Rule]): OperationList = {
        val peerIps = peers.map(p => IPSubnetUtil.fromAddr(p.getPeerIp))

        rules.filter(r => r.getCondition.hasNwSrcIp &&
                          peerIps.contains(r.getCondition.getNwSrcIp) &&
                          r.getCondition.getTpDst.equals(RangeUtil.toProto(127, 127)))
             .map(r => Delete(classOf[Rule], r.getId)).toList
    }

    def filterBgpRules(rules: Seq[Rule], ip: IPAddress): List[Rule] = {
        val peerIp = IPSubnetUtil.fromAddr(ip)

        rules.filter(r => r.getCondition.hasNwDstIp &&
                          r.getCondition.getNwDstIp.equals(peerIp)).toList
    }

    def addNetworks(router: Router.Builder): OperationList = {
        val ops = new OperationListBuffer
        val nPorts = storage.getAll(classOf[NeutronPort]).await()
        val subIds = nPorts.filter(_.getDeviceOwner == NeutronPort.DeviceOwner.ROUTER_INTERFACE)
                                 .filter(_.getDeviceId == UUIDUtil.fromProto(router.getId).toString)
                                 .map(_.getFixedIpsList.get(0).getSubnetId)

        val subs = storage.getAll(classOf[NeutronSubnet], subIds).await()

        for (sub <- subs) {
            val bgpNet = makeBgpNetwork(router.getId, sub.getCidr)
            router.addBgpNetworkIds(bgpNet.getId)
            ops += Create(bgpNet)
        }
        ops.toList
    }

    def addPeers(router: Router.Builder,
                 bgpSpeaker: NeutronBgpSpeaker): OperationList = {
        val peers = storage.getAll(classOf[NeutronBgpPeer], bgpSpeaker.getPeersList).await()
        val peerOps = new OperationListBuffer

        peers.foreach(peer => peerOps ++= addPeerOps(router, peer))

        peerOps.toList
    }

    def addPeerOps(router: Router.Builder, bgpPeer: NeutronBgpPeer): OperationList = {
        router.addBgpPeerIds(bgpPeer.getId)
        val bgpPeerOp = makeBgpPeerOp(router.getId, bgpPeer)
        val redirectRuleOp = makePeerRedirectRule(quaggaPortId(router.getId),
                                                  router.getLocalRedirectChainId,
                                                  bgpPeer)
        List(bgpPeerOp, redirectRuleOp)
    }

    def scheduleService(portId: UUID, routerId: UUID): OperationList = {
        val scg = ServiceContainerGroup.newBuilder
            .setId(JUUID.randomUUID)
            .build()

        val sc = ServiceContainer.newBuilder
            .setId(quaggaContainerId(routerId))
            .setServiceGroupId(scg.getId)
            .setPortId(portId)
            .setServiceType("QUAGGA")
            .setConfigurationId(routerId)
            .build()

        List(Create(scg), Create(sc))
    }

    def ensureRedirectChain(router: Router.Builder): OperationList = {
        if (router.hasLocalRedirectChainId) {
            List()
        } else {
            val id = JUUID.randomUUID
            val chain = newChain(id, "LOCAL_REDIRECT_" + router.getId.asJava)
            router.setLocalRedirectChainId(id)

            List(Create(chain))
        }
    }

    def makeBgpPeerOp(routerId: UUID, neutronBgpPeer: NeutronBgpPeer) = {
        Create(BgpPeer.newBuilder()
               .setAddress(neutronBgpPeer.getPeerIp)
               .setAsNumber(neutronBgpPeer.getRemoteAs)
               .setId(neutronBgpPeer.getId)
               .setRouterId(routerId)
               .build())
    }

    def makeBgpNetwork(routerId: UUID, subnet: IPSubnet): BgpNetwork = {
        BgpNetwork.newBuilder()
                  .setId(JUUID.randomUUID())
                  .setRouterId(routerId)
                  .setSubnet(subnet)
                  .build()
    }

    def makePeerRedirectRule(portId: UUID, chainId: UUID, bgpPeer: NeutronBgpPeer) = {
        val peerIp = IPSubnetUtil.fromAddr(bgpPeer.getPeerIp)

        val peerRuleBldr = redirectRuleBuilder(
            id = Some(JUUID.randomUUID),
            chainId = chainId,
            targetPortId = portId)
        peerRuleBldr.getConditionBuilder
                    .setNwSrcIp(peerIp)
                    .setNwProto(6)
                    .setTpDst(RangeUtil.toProto(127, 127))
        Create(peerRuleBldr.build())
    }

    def createQuaggaRouterPort(router: Router): OperationList = {

        if (storage.exists(classOf[Port], quaggaPortId(router.getId)).await()) {
            List()
        } else {
            val currentPorts = storage.getAll(classOf[Port], router.getPortIdsList).await()

            val subnet = containers.findLocalSubnet(currentPorts)
            val routerAddr = containers.routerPortAddress(subnet)

            val pbuilder = Port.newBuilder
                .setId(quaggaPortId(router.getId))
                .setRouterId(router.getId)
                .setPortSubnet(subnet)
                .setPortAddress(routerAddr)
                .setPortMac(MAC.random().toString)
                .setQuaggaContainer(true)

            assignTunnelKey(pbuilder, sequenceDispenser)
            List(Create(pbuilder.build))
        }
    }
}

object BgpSpeakerTranslator {
    def quaggaContainerId(routerId: UUID): UUID =
        routerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    def quaggaPortId(deviceId: UUID): UUID =
        deviceId.xorWith(0xff498a4c22390ae3L, 0x3e3ec848baff217dL)
}

