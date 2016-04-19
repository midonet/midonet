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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.data.storage.StateTableStorage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{NeutronBgpPeer, NeutronBgpSpeaker}
import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.packets.TCP
import org.midonet.util.concurrent.toFutureOps

class BgpPeerTranslator(protected val storage: ReadOnlyStorage,
                        protected val stateTableStorage: StateTableStorage)
    extends Translator[NeutronBgpPeer] with RuleManager
                                       with PortManager
                                       with ChainManager {

    import BgpSpeakerTranslator._

    override protected def translateCreate(bgpPeer: NeutronBgpPeer)
    : OperationList = {
        val ops = new OperationListBuffer

        val speaker = storage.get(classOf[NeutronBgpSpeaker], bgpPeer.getBgpSpeakerId).await()
        val router = storage.get(classOf[Router], speaker.getRouterId).await()

        val (chainId, chainOps) = ensureRedirectChain(router)
        ops ++= chainOps
        ops += makeBgpPeer(router.getId, bgpPeer)
        ops += makePeerRedirectRule(quaggaPortId(router.getId), chainId, bgpPeer)

        ops.toList
    }

    override protected def translateDelete(bgpPeer: NeutronBgpPeer)
    : OperationList = {
        List(Delete(classOf[BgpPeer], bgpPeer.getId),
             Delete(classOf[Rule], redirectRuleId(bgpPeer.getId)))
    }

    def isBgpPeerRule(rule: Rule, ip: Commons.IPAddress): Boolean = {
        val peerIp = IPSubnetUtil.fromAddr(ip)
        rule.getCondition.hasNwSrcIp &&
        rule.getCondition.getNwSrcIp.equals(peerIp) &&
        rule.getCondition.getTpDst.equals(bgpPortRange)
    }

    def makeBgpPeer(routerId: UUID, neutronBgpPeer: NeutronBgpPeer) = {
        Create(BgpPeer.newBuilder()
            .setAddress(neutronBgpPeer.getPeerIp)
            .setAsNumber(neutronBgpPeer.getRemoteAs)
            .setId(neutronBgpPeer.getId)
            .setRouterId(routerId)
            .build())
    }

    def makePeerRedirectRule(portId: UUID, chainId: UUID, bgpPeer: NeutronBgpPeer) = {
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


    override protected def translateUpdate(bgpSpeaker: NeutronBgpPeer)
    : OperationList = {
        List()
    }
}
