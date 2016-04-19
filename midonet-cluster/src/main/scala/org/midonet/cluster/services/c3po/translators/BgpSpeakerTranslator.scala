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

import org.midonet.cluster.data.storage.StateTableStorage
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.NeutronBgpSpeaker
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.{BgpNetwork, Port, Router, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{RangeUtil, SequenceDispenser, UUIDUtil}
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

        ops += createQuaggaRouterPort(router)
        ops ++= scheduleService(quaggaPortId(router.getId), routerId)
        ops ++= addNetworks(rBldr)
        ops += Update(rBldr.build)

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
        val routerId = speaker.getRouterId

        ops += Delete(classOf[Port], quaggaPortId(speaker.getRouterId))
        ops += Delete(classOf[ServiceContainer], quaggaContainerId(routerId))
        ops += Delete(classOf[ServiceContainerGroup], quaggaContainerGroupId(routerId))
        ops ++= removeNetworks(speaker)

        ops.toList
    }

    def removeNetworks(speaker: NeutronBgpSpeaker): OperationList = {
        val ops = new OperationListBuffer
        val router = storage.get(classOf[Router], speaker.getRouterId).await()
        val rBldr = router.toBuilder

        ops ++= rBldr.getBgpNetworkIdsList.map(Delete(classOf[BgpNetwork], _))
        rBldr.clearBgpNetworkIds()
        ops += Update(rBldr.build)

        ops.toList
    }

    def addNetworks(router: Router.Builder): OperationList = {
        val ops = new OperationListBuffer
        val nPorts = storage.getAll(classOf[NeutronPort]).await()
        val subIds = nPorts.filter(_.getDeviceOwner == NeutronPort.DeviceOwner.ROUTER_INTERFACE)
                           .filter(_.getDeviceId == UUIDUtil.fromProto(router.getId).toString)
                           .map(_.getFixedIpsList.get(0).getSubnetId)

        val subs = storage.getAll(classOf[NeutronSubnet], subIds).await()

        for (sub <- subs) {
            val bgpNet = makeBgpNetwork(router.getId, sub.getCidr, sub.getNetworkId)
            router.addBgpNetworkIds(bgpNet.getId)
            ops += Create(bgpNet)
        }
        ops.toList
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

    def makeBgpNetwork(routerId: UUID, subnet: IPSubnet, networkId: UUID)
        : BgpNetwork = {
        BgpNetwork.newBuilder()
                  .setId(bgpNetworkId(networkId))
                  .setRouterId(routerId)
                  .setSubnet(subnet)
                  .build()
    }

    def createQuaggaRouterPort(router: Router): Create[Topology.Port] = {
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
        Create(pbuilder.build)
    }
}

object BgpSpeakerTranslator {
    def quaggaContainerId(routerId: UUID): UUID =
        routerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    def quaggaContainerGroupId(routerId: UUID): UUID =
        routerId.xorWith(0x7d263d2d55da46d2L, 0xb53951e91eba3a1fL)

    def quaggaPortId(deviceId: UUID): UUID =
        deviceId.xorWith(0xff498a4c22390ae3L, 0x3e3ec848baff217dL)

    def bgpNetworkId(networkId: UUID): UUID =
        networkId.xorWith(0x39c62a620c7049a9L, 0xbc9c1acb80e516fcL)

    def redirectRuleId(peerId: UUID): UUID =
        peerId.xorWith(0x12d34babf7d84902L, 0xaa840971afc3307fL)

    val bgpPortRange = RangeUtil.toProto(127, 127)
}

