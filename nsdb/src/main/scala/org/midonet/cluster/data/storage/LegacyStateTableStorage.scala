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

package org.midonet.cluster.data.storage

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.services.MidonetBackend.Ip4MacTable

/**
  * A compatibility layer for the paths of the state tables.
  */
trait LegacyStateTableStorage {
    protected def rootPath: String

    type LegacyPath = (ObjId, Any*) => String
    private val legacyPaths = Map[(Class[_], String), LegacyPath](
        (classOf[Network], Ip4MacTable) -> ((id, args) => {
            s"$rootPath/bridges/$id/ip4_mac_map"
        })
    )

    @inline
    private[storage] def legacyTableRootPath(clazz: Class[_], id: ObjId,
                                             name: String, args: Any*)
    : Option[String] = {
        legacyPaths get (clazz, name) map { _.apply(id, Seq.empty) }
    }

    @inline
    private[storage] def legacyTablePath(clazz: Class[_], id: ObjId,
                                         name: String, args: Any*)
    : Option[String] = {
        legacyPaths get (clazz, name) map { _.apply(id, args) }
    }

}
