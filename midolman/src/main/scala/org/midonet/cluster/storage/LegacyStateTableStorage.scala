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

package org.midonet.cluster.storage

import java.util.UUID

import scala.reflect.ClassTag

import com.google.inject.Inject

import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.data.storage.{StateTable, StateTableStorage}
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.{IPv4Addr, MAC}

/**
 * In implementation of the [[StateTableStorage]] for the legacy cluster
 * storage.
 */
final class LegacyStateTableStorage @Inject()(curator: CuratorFramework,
                                              paths: PathBuilder)
    extends StateTableStorage {

    /** Returns the IPv4 ARP table for the specified bridge. */
    @Deprecated
    def bridgeArpTable(bridgeId: UUID): StateTable[IPv4Addr, MAC] = {
        new LegacyArpTable(bridgeId, curator, paths)
    }

    def registerTable[K, V](clazz: Class[_], key: Class[K], value: Class[V],
                            name: String,
                            provider: Class[_ <: StateTable[K,V]]): Unit = ???

    def getTable[K, V](clazz: Class[_], id: Any, name: String, args: Any*)
                      (implicit key: ClassTag[K], value: ClassTag[V])
    : StateTable[K, V] = ???


}
