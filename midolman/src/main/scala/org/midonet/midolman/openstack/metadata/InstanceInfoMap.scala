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

case class InstanceInfo(
    val addr: String,
    val mac: String,  // XXX
    val portId: UUID,
    val tenantId: String,
    val instanceId: String)

object InstanceInfoMap {
    private val map: TrieMap[String, InstanceInfo] = new TrieMap()

    def put(key: String, value: InstanceInfo) = {
        map put (key, value)
    }

    def get(key: String) = {
        val Some(value) = map get key
        value
    }

    def remove(key: String) = {
        map remove key
    }
}
