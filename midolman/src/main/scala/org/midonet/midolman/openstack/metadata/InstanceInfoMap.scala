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

package org.midonet.midolman.openstack.metadata

import java.util.UUID

import scala.collection.concurrent.TrieMap

import com.google.common.base.MoreObjects

/*
 * InstanceInfo describes VM-side of the plumbing.
 * (The other side is described by ProxyInfo.)
 *
 * addr, tenantId, instanceId are needed by ProxyHandler.
 * They are sent to the nova metadata API.
 */
case class InstanceInfo(address: String,
                        mac: String,
                        portId: UUID,
                        tenantId: String,
                        instanceId: String) {

    override lazy val toString = {
        MoreObjects.toStringHelper(this).omitNullValues()
            .add("address", address)
            .add("mac", mac)
            .add("portId", portId)
            .add("tenantId", tenantId)
            .add("instanceId", instanceId)
            .toString
    }
}

/*
 * IP address mapping for instances
 *
 * Accessed by MetadataServiceWorkflow, MetadataServiceManagerActor,
 * and ProxyHandler contexts.  InstanceInfo instances in this map
 * are considered immutable.
 */
object InstanceInfoMap {

    private val byAddr: TrieMap[String, InstanceInfo] = new TrieMap()
    private val byPortId: TrieMap[UUID, String] = new TrieMap()

    def put(addr: String, portId: UUID, value: InstanceInfo) = {
        // Since the address is calculated using the datapath port, if we get 2
        // different ports with the same datapath port, the last one will
        // overwrite the first entry.
        byAddr put (addr, value) match {
            case Some(staleInfo) if staleInfo.portId != portId =>
                Log warn s"Found stale info: $staleInfo"
                Log warn s"Removing old entry for port: ${staleInfo.portId}"
                byPortId remove staleInfo.portId
            case _ => // everything ok
        }
        byPortId put (portId, addr)
    }

    def getByAddr(addr: String) = {
        byAddr get addr
    }

    def getByPortId(portId: UUID) = {
        byPortId get portId
    }

    def removeByPortId(portId: UUID) = {
        val Some(addr) = byPortId get portId
        byPortId remove portId
        byAddr remove addr
    }
}
