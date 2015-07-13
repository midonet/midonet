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

/*
 * addr, tenantId, instanceId are needed by ProxyHandler.
 * They are sent to the nova metadata API.
 *
 * Others are kept here for the convenience of Plumber.unplumb().
 */
case class InstanceInfo(
    val addr: String,
    val mac: String,
    val portId: UUID,
    val tenantId: String,
    val instanceId: String)

object InstanceInfoMap {
    private val byAddr: TrieMap[String, InstanceInfo] = new TrieMap()
    private val byPortId: TrieMap[UUID, String] = new TrieMap()

    def put(addr: String, portId: UUID, value: InstanceInfo) = {
        byAddr put (addr, value)
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
