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

import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.{StateTableStorage, Transaction}
import org.midonet.cluster.models.Neutron.{GatewayDevice, RemoteMacEntry}
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Operation}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{IPv4Addr, MAC}

class RemoteMacEntryTranslator(stateTableStorage: StateTableStorage)
    extends Translator[RemoteMacEntry] {

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(tx: Transaction,
                                           rm: RemoteMacEntry): Unit = {
        // Get the ports on the gateway device's router.
        val gwDev  = tx.get(classOf[GatewayDevice], rm.getDeviceId)
        val router = tx.get(classOf[Router], gwDev.getResourceId)
        val ports  = tx.getAll(classOf[Port], router.getPortIdsList)

        // For each port with the same VNI, add a peering table entry. Remember
        // which ports have this in their peering tables.
        val rmBldr = rm.toBuilder
        for (p <- ports if p.hasVni && p.getVni == rm.getSegmentationId) {
            rmBldr.addPortIds(p.getId)
            tx.createNode(stateTableStorage.portPeeringEntryPath(
                p.getId.asJava, MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)), null)
        }

        tx.create(rmBldr.build())
    }

    override protected def translateDelete(tx: Transaction,
                                           rm: RemoteMacEntry): Unit = {
        val ports = tx.getAll(classOf[Port], rm.getPortIdsList)
        for (p <- ports.toList) {
            tx.deleteNode(stateTableStorage.portPeeringEntryPath(
                p.getId.asJava, MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)))
        }
    }

    override protected def translateUpdate(tx: Transaction,
                                           rm: RemoteMacEntry): Unit = {
        throw new NotImplementedError("RemoteMacEntry update not supported.")
    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[RemoteMacEntry])
    : List[Operation[RemoteMacEntry]] = {
        op match {
            case Create(nm) => List() // Do nothing
            case _ => super.retainHighLevelModel(tx, op)
        }
    }

}
