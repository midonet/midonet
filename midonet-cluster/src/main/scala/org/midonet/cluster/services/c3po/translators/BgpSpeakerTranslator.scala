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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage, Transaction}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{NeutronBgpPeer, NeutronBgpSpeaker}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Delete, Operation}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

class BgpSpeakerTranslator(protected val storage: ReadOnlyStorage,
                           protected val stateTableStorage: StateTableStorage)
    extends Translator[NeutronBgpSpeaker] with PortManager
                                          with RuleManager {
    import BgpPeerTranslator._

    override protected def translateCreate(tx: Transaction,
                                           bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        throw new UnsupportedOperationException(
            "Create NeutronBgpSpeaker not supported.")
    }

    override protected def translateUpdate(tx: Transaction,
                                           bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        if (bgpSpeaker.getDelBgpPeerIdsCount == 0)
            return List()

        val router =
            storage.get(classOf[Router], bgpSpeaker.getLogicalRouter).await()

        val ops = new OperationListBuffer

        // Delete all specified peers.
        for (bgpPeerId <- bgpSpeaker.getDelBgpPeerIdsList) {
            ops ++= deleteBgpPeer(router, bgpPeerId)
            ops += Delete(classOf[NeutronBgpPeer], bgpPeerId)
        }

        ops.toList
    }

    override protected def translateDelete(tx: Transaction,
                                           bgpSpeakerId: UUID): OperationList = {
        throw new UnsupportedOperationException(
            "Delete NeutronBgpSpeaker not supported.")
    }

    // We don't store the BGPSpeaker in Zookeeper.
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronBgpSpeaker])
    : List[Operation[NeutronBgpSpeaker]] = List()
}

