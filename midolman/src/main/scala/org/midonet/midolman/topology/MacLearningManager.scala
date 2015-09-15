/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.topology

import java.lang.{Short => JShort}
import java.util.{List => JList, UUID}

import scala.collection.{Map => ROMap}
import scala.concurrent.duration._

import com.typesafe.scalalogging.Logger

import org.midonet.cluster.client._
import org.midonet.packets.MAC
import org.midonet.util.collection.Reducer
import org.midonet.util.concurrent.TimedExpirationMap

case class MacPortMapping(mac: MAC, vlan: JShort, port: UUID) {
    override def toString = s"{vlan=$vlan mac=$mac port=$port}"
}

/**
 * Handles a bridge's mac-port associations. It add/removes the (MAC, VLAN, PORT)
 * tuples to/from the underlying replicated map. The callbacks guarantee
 * the required happens-before relationship because all zookeeper requests
 * are served by a single threaded reactor.
 */
class MacLearningManager(log: Logger, ttlMillis: Duration) {

    val map = new TimedExpirationMap[MacPortMapping, AnyRef](log, _ => ttlMillis)

    @volatile var vlanMacTableMap: Map[Short, MacLearningTable] = null

    val reducer = new Reducer[MacPortMapping, Any, Unit] {
        override def apply(acc: Unit, key: MacPortMapping, value: Any): Unit =
            vlanMacTableOperation(key.vlan, _.remove(key.mac, key.port))
    }

    private def vlanMacTableOperation(vlanId: JShort, fun: MacLearningTable => Unit) {
        vlanMacTableMap.get(vlanId) match {
            case Some(macLearningTable) => fun(macLearningTable)
            case None => log.warn(s"Mac learning table not found for VLAN $vlanId")
        }
    }

    def incRefCount(e: MacPortMapping): Unit =
        if (map.putIfAbsentAndRef(e, e) eq null) {
            vlanMacTableOperation(e.vlan, _.add(e.mac, e.port))
        }

    def decRefCount(key: MacPortMapping, currentTime: Long): Unit =
        map.unref(key, currentTime)

    def expireEntries(currentTime: Long): Unit =
        map.obliterateIdleEntries(currentTime, (), reducer)
}

