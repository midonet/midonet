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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Commons.{IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.{NeutronBgpPeer, NeutronBgpSpeaker, NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, SequenceDispenser}
import org.midonet.containers
import org.midonet.packets.{MAC, TCP}
import org.midonet.util.concurrent.toFutureOps

class BgpPeerTranslator(protected val storage: ReadOnlyStorage,
                        protected val stateTableStorage: StateTableStorage,
                        sequenceDispenser: SequenceDispenser)
    extends Translator[NeutronBgpPeer] with RuleManager
                                       with PortManager
                                       with ChainManager {

    import BgpPeerTranslator._

    override protected def translateCreate(bgpPeer: NeutronBgpPeer)
    : OperationList = {
        val speaker = bgpPeer.getBgpSpeaker
        val router = storage.get(classOf[Router], speaker.getRouterId).await()

        val (chainId, chainOps) = ensureRedirectChain(router)

        val ops = new OperationListBuffer
        ops ++= ensureContainer(router, speaker)
        ops ++= chainOps
        ops += createBgpPeer(router.getId, bgpPeer)
        ops += createPeerRedirectRule(quaggaPortId(router.getId), chainId, bgpPeer)
        ops.toList
    }

    override protected def translateUpdate(newPeer: NeutronBgpPeer)
    : OperationList = {
        val oldPeer = storage.get(classOf[BgpPeer], newPeer.getId).await()

        // Can only update password.
        if (oldPeer.getPassword == newPeer.getPassword) List() else {
            List(Update(
                oldPeer.toBuilder.setPassword(newPeer.getPassword).build()))
        }
    }

    override protected def translateDelete(bgpPeer: NeutronBgpPeer)
    : OperationList = {
         val router = storage.get(classOf[Router],
                                  bgpPeer.getBgpSpeaker.getRouterId).await()

        val ops = new OperationListBuffer
        ops ++= deleteBgpPeer(router, bgpPeer.getId)
        if (router.getBgpPeerIdsCount == 1) {
            ops ++= deleteBgpContainer(router)
        }
        ops.toList
    }

    def createBgpPeer(routerId: UUID,
                      neutronBgpPeer: NeutronBgpPeer): Create[BgpPeer] = {
        Create(BgpPeer.newBuilder()
                   .setAddress(neutronBgpPeer.getPeerIp)
                   .setAsNumber(neutronBgpPeer.getRemoteAs)
                   .setId(neutronBgpPeer.getId)
                   .setRouterId(routerId)
                   .setPassword(neutronBgpPeer.getPassword)
                   .build())
    }

    def createPeerRedirectRule(portId: UUID, chainId: UUID,
                               bgpPeer: NeutronBgpPeer): Create[Rule] = {
        val peerIp = IPSubnetUtil.fromAddr(bgpPeer.getPeerIp)

        val peerRuleBldr = redirectRuleBuilder(
            id = Some(redirectRuleId(bgpPeer.getId)),
            chainId = chainId,
            targetPortId = portId)
        peerRuleBldr.getConditionBuilder
            .setNwSrcIp(peerIp)
            .setNwProto(TCP.PROTOCOL_NUMBER)
            .setTpDst(bgpPortRange)
        Create(peerRuleBldr.build())
    }

    private def ensureContainer(router: Router, bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        val ops = new OperationListBuffer
        if (router.getBgpPeerIdsCount == 0) {
            ops += Update(
                router.toBuilder.setAsNumber(bgpSpeaker.getLocalAs).build())
            ops += createQuaggaRouterPort(router)
            ops ++= scheduleService(quaggaPortId(router.getId), router.getId)
            ops ++= addNetworks(router)
        }
        ops.toList
    }


    def addNetworks(router: Router): OperationList = {
        val rPorts = storage.getAll(classOf[Port], router.getPortIdsList).await()
        val rPortPeerIds = rPorts.map(_.getPeerId)
        val nPorts = storage.getAll(classOf[NeutronPort], rPortPeerIds).await()

        for (nPort <- nPorts.toList) yield {
            val subId = nPort.getFixedIpsList.get(0).getSubnetId
            val sub = storage.get(classOf[NeutronSubnet], subId).await()
            Create(makeBgpNetwork(router.getId, sub.getCidr, nPort.getId))
        }
    }

    def scheduleService(portId: UUID, routerId: UUID): OperationList = {
        val scg = ServiceContainerGroup.newBuilder
            .setId(quaggaContainerGroupId(routerId))
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

    def createQuaggaRouterPort(router: Router): Create[Port] = {
        val currentPorts = storage.getAll(classOf[Port], router.getPortIdsList).await()

        val subnet = containers.findLocalSubnet(currentPorts)
        val routerAddr = containers.routerPortAddress(subnet)

        val pbuilder = Port.newBuilder
            .setId(quaggaPortId(router.getId))
            .setRouterId(router.getId)
            .setPortSubnet(subnet)
            .setPortAddress(routerAddr)
            .setPortMac(MAC.random().toString)

        assignTunnelKey(pbuilder, sequenceDispenser)
        Create(pbuilder.build)
    }
}

object BgpPeerTranslator {
    def quaggaContainerId(routerId: UUID): UUID =
        routerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    def quaggaContainerGroupId(routerId: UUID): UUID =
        routerId.xorWith(0x7d263d2d55da46d2L, 0xb53951e91eba3a1fL)

    def quaggaPortId(deviceId: UUID): UUID =
        deviceId.xorWith(0xff498a4c22390ae3L, 0x3e3ec848baff217dL)

    def bgpNetworkId(rifPortId: UUID): UUID =
        rifPortId.xorWith(0x39c62a620c7049a9L, 0xbc9c1acb80e516fcL)

    def redirectRuleId(peerId: UUID): UUID =
        peerId.xorWith(0x12d34babf7d84902L, 0xaa840971afc3307fL)

    val bgpPortRange = RangeUtil.toProto(127, 127)

    private[translators] def deleteBgpContainer(router: Router)
    : OperationList = {
        val ops = new OperationListBuffer
        ops += Delete(classOf[Port], quaggaPortId(router.getId))
        ops += Delete(classOf[ServiceContainer],
                      quaggaContainerId(router.getId))
        ops += Delete(classOf[ServiceContainerGroup],
                      quaggaContainerGroupId(router.getId))
        ops ++= router.getBgpNetworkIdsList.map(Delete(classOf[BgpNetwork], _))
        ops.toList
    }

    private[translators] def deleteBgpPeer(router: Router, bgpPeerId: UUID)
    : OperationList = {
        List(Delete(classOf[BgpPeer], bgpPeerId),
             Delete(classOf[Rule], redirectRuleId(bgpPeerId)))
    }

    def makeBgpNetwork(routerId: UUID, subnet: IPSubnet, rifPortId: UUID)
    : BgpNetwork = {
        BgpNetwork.newBuilder()
            .setId(bgpNetworkId(rifPortId))
            .setRouterId(routerId)
            .setSubnet(subnet)
            .build()
    }
}
