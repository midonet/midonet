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

package org.midonet.cluster.services.vxgw

import java.lang.{Short => JShort}
import java.util.UUID

import scala.util.Try

import org.apache.commons.lang3.tuple.{Pair => JPair}
import org.slf4j.LoggerFactory
import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr

/** This class abstracts low-level details of the connection to an OVSDB
  * instance in order to satisfy the high-level interface used by the VxLAN
  * Gateway implementation to coordinate with a hardware vtep. */
class VtepFromOvsdb(ip: IPv4Addr, port: Int) extends VtepConfig(ip, port) {
    private val log = LoggerFactory.getLogger("VxGW: vtep " + ip + ":" + port )
    override def macLocalUpdates = Observable.empty[MacLocation]()
    override def macRemoteUpdater = new Observer[MacLocation]() {
        override def onCompleted (): Unit = {
            log.info (s"Inbound stream completed")
        }
        override def onError (e: Throwable): Unit = {
            log.error (s"Inbound stream completed")
        }
        override def onNext (ml: MacLocation): Unit = {
            log.error (s"VTEP $ip:$port learning remote: $ml")
        }
    }
    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???
    override def ensureLogicalSwitch(name: String,
                                     vni: Int): Try[LogicalSwitch] = ???
    override def removeLogicalSwitch(name: String): Try[Unit] = ???
    override def currentMacLocal(ls: UUID): Seq[MacLocation] = ???
    override def vxlanTunnelIp: Option[IPv4Addr] = ???
}

