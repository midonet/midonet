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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage, Transaction}
import org.midonet.cluster.models.Neutron.{GatewayDevice, RemoteMacEntry}
import org.midonet.cluster.models.Topology.{Port, Router}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{CreateNode, DeleteNode, Update}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps

class RemoteMacEntryTranslator(protected val storage: ReadOnlyStorage,
                               protected val stateTableStorage: StateTableStorage)
    extends Translator[RemoteMacEntry] with StateTableManager {

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(tx: Transaction,
                                           rm: RemoteMacEntry)
    : OperationList = {
        // Get the ports on the gateway device's router.
        val gwDev = storage.get(classOf[GatewayDevice], rm.getDeviceId).await()
        val router = storage.get(classOf[Router], gwDev.getResourceId).await()
        val ports = storage.getAll(classOf[Port], router.getPortIdsList).await()

        // For each port with the same VNI, add a peering table entry. Remember
        // which ports have this in their peering tables.
        val rmBldr = rm.toBuilder
        val mapEntryOps = for {
            p <- ports if p.hasVni && p.getVni == rm.getSegmentationId
        } yield {
            rmBldr.addPortIds(p.getId)
            CreateNode(stateTableStorage.portPeeringEntryPath(
                p.getId.asJava, MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)))
        }

        // Update the RemoteMacEntry if it was added to any ports.
        if (rmBldr.getPortIdsList == rm.getPortIdsList) {
            mapEntryOps.toList
        } else {
            mapEntryOps.toList :+ Update(rmBldr.build())
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           rm: RemoteMacEntry)
    : OperationList = {
        val ports = storage.getAll(classOf[Port], rm.getPortIdsList).await()
        for (p <- ports.toList) yield {
            DeleteNode(stateTableStorage.portPeeringEntryPath(
                p.getId.asJava, MAC.fromString(rm.getMacAddress),
                IPv4Addr(rm.getVtepAddress.getAddress)))
        }
    }

    override protected def translateUpdate(tx: Transaction,
                                           rm: RemoteMacEntry)
    : OperationList = {
        throw new NotImplementedError("RemoteMacEntry update not supported.")
    }
}
