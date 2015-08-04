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

import scala.collection.JavaConverters._

import rx.Observable

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.StateTable
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.midolman.state.StateAccessException
import org.midonet.packets.{IPv4Addr, MAC}

final class LegacyArpTable(bridgeId: UUID, dataClient: DataClient)
    extends StateTable[IPv4Addr, MAC] {

    /** Adds an opinion key MAC pair to the state table. */
    def add(address: IPv4Addr, mac: MAC): Unit = ???

    /** Adds a persistent address MAC pair to the state table. */
    @throws[StateAccessException]
    def addPersistent(address: IPv4Addr, mac: MAC): Unit = {
        dataClient.bridgeAddIp4Mac(bridgeId, address, mac)
    }

    /** Deletes the opinion for the specified address from the state table. */
    def delete(address: IPv4Addr): Unit = ???

    /** Deletes an address MAC pair from the state table, either learned or
      * persistent. */
    @throws[StateAccessException]
    def delete(address: IPv4Addr, mac: MAC): Unit = {
        dataClient.bridgeDeleteIp4Mac(bridgeId, address, mac)
    }

    /** Returns whether the table contains a MAC for the specified address,
      * either learned or persistent. */
    def contains(address: IPv4Addr): Boolean = ???

    /** Returns whether the table contains the address MAC pair, either
      * learned or persistent. */
    @throws[StateAccessException]
    def contains(address: IPv4Addr, mac: MAC): Boolean = {
        dataClient.bridgeHasIP4MacPair(bridgeId, address, mac)
    }

    /** Returns whether the table contains the persistent address MAC pair. */
    def containsPersistent(address: IPv4Addr, mac: MAC): Boolean = ???

    /** Gets the MAC for the specified address. */
    def get(address: IPv4Addr): Option[MAC] = ???

    /** Gets a read-only snapshot for the current state table. */
    def snapshot: Map[IPv4Addr, MAC] = {
        dataClient.bridgeGetIP4MacPairs(bridgeId).asScala.toMap
    }

    /** Returns an observable that notifies the updates to the current state
      * table. */
    def observable: Observable[Update[IPv4Addr, MAC]] = ???
}
