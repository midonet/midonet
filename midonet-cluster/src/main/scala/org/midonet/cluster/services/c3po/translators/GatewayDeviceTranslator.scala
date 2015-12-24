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

import java.nio.ByteBuffer
import java.util.{UUID => JUUID}

import scala.collection.mutable
import scala.util.control.NonFatal

import org.midonet.cluster.data.storage.{NotFoundException, StateTableStorage, ReadOnlyStorage}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{RemoteMacEntry, GatewayDevice}
import org.midonet.cluster.models.Neutron.GatewayDevice.GatewayType.ROUTER_VTEP
import org.midonet.cluster.models.Topology.Port
import scala.collection.JavaConversions._
import org.midonet.cluster.storage.Ip4MacStateTable
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

import org.midonet.cluster.services.c3po.C3POStorageManager.{Delete, Create}
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.midolman.state.Ip4ToMacReplicatedMap
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps

import org.midonet.cluster.services.c3po.midonet.{CreateNode, DeleteNode}

class GatewayDeviceTranslator(protected val storage: ReadOnlyStorage,
                              protected val stateTableStorage: StateTableStorage)
    extends Translator[GatewayDevice] {
    import GatewayDeviceTranslator._

    override protected def translateCreate(gwDev: GatewayDevice)
    : OperationList = {
        // Only router VTEPs are supported.
        if (gwDev.getType != ROUTER_VTEP) return List()

        val portsByVni = mutable.Map[Int, Port]()
        val remoteMacOps = for (rm <- gwDev.getRemoteMacEntriesList) yield {
            val vni = rm.getSegmentationId
            val port = portsByVni.getOrElseUpdate(vni, {
                Port.newBuilder
                    .setId(vtepRouterPortId(gwDev.getId, vni))
                    .setRouterId(gwDev.getResourceId)
                    .setVni(vni)
                    .build()
            })

            CreateNode(remoteMacEntryPath(rm, port.getId.asJava))
        }

        val portOps = portsByVni.values.map(Create(_)).toList
        portOps ++ remoteMacOps
    }

    override protected def translateDelete(gwDevId: UUID): OperationList = {
        val gwDev = storage.get(classOf[GatewayDevice], gwDevId).await()

        // Only router VTEPs are supported.
        if (gwDev.getType != ROUTER_VTEP) return List()

        val portIdsByVni = mutable.Map[Int, UUID]()
        val rms = gwDev.getRemoteMacEntriesList.toList
        val remoteMacOps = for (rm <- rms) yield {
            val vni = rm.getSegmentationId
            val portId = portIdsByVni.getOrElseUpdate(
                vni, vtepRouterPortId(gwDevId, vni))
            DeleteNode(remoteMacEntryPath(rm, portId.asJava))
        }

        val portOps = portIdsByVni.values.map(Delete(classOf[Port], _)).toList
        remoteMacOps ++ portOps
    }

    override protected def translateUpdate(gwDev: GatewayDevice)
    : OperationList = {
        // TODO: Implement.
        List()
    }

    private def remoteMacEntryPath(rm: RemoteMacEntry,
                                   portId: JUUID): String = {
        val peeringTablePath =
            stateTableStorage.routerPortPeeringTablePath(portId)
        val ip = IPAddressUtil.toIPv4Addr(rm.getVtepAddress)
        val mac = MAC.fromString(rm.getMacAddress)
        val entryName = Ip4ToMacReplicatedMap.encodePersistentPath(ip, mac)
        peeringTablePath + entryName
    }
}

object GatewayDeviceTranslator {

    /** Hash GatewayDevice ID plus vni to generate a UUID for the device's
      * router port on the specified VNI. */
    def vtepRouterPortId(gwDevId: UUID, vni: Int): UUID = {
        val buf = ByteBuffer.allocate(20)
        buf.putLong(gwDevId.getLsb)
        buf.putLong(gwDevId.getMsb)
        buf.putInt(vni)
        UUIDUtil.toProto(JUUID.nameUUIDFromBytes(buf.array()))
    }
}

