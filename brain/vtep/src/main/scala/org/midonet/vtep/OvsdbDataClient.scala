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

package org.midonet.vtep

import java.net.InetAddress
import java.util.UUID
import javax.annotation.concurrent.GuardedBy

import org.midonet.packets.IPv4Addr
import org.midonet.vtep.model.LogicalSwitch
import rx.{Observer, Observable}

import scala.collection.mutable

import com.google.common.util.concurrent.Monitor
import com.google.common.util.concurrent.Monitor.Guard
import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

/**
 * The class representing an ovsdb-based VTEP
 */
class OvsdbDataClient(val endPoint: VtepEndPoint)
    extends VtepConnection with VtepDataClient {

    import VtepConnection.State._

    private val log = LoggerFactory.getLogger(classOf[OvsdbDataClient])
    private val monitor = new Monitor()
    private val isOperative = new Guard(monitor) {
        override def isSatisfied: Boolean = state != DISPOSED
    }

    @GuardedBy("monitor")
    private var state = if (endPoint.mgmtIp == null) DISPOSED else DISCONNECTED
    @GuardedBy("monitor")
    private val users = new mutable.HashSet[UUID]()
    @GuardedBy("monitor")
    private var client: OvsdbClient = null
    @GuardedBy("monitor")
    private var vtepData: OvsdbVtepData = null

    private val connectionService = OvsdbConnectionService.getService

    override def getManagementIp = endPoint.mgmtIp
    override def getManagementPort = endPoint.mgmtPort

    override def connect(user: UUID) = {
        if (!monitor.enterIf(isOperative)) {
            throw new VtepStateException(endPoint, "cannot be connected")
        }
        try {
            log.info("Connecting to VTEP on {} for user " + user, endPoint)
            val address = try {
                InetAddress.getByName(endPoint.mgmtIp.toString)
            } catch {
                case e: Throwable =>
                    state = DISPOSED
                    throw new VtepStateException(endPoint, "invalid IP address")
            }

            // Skip if already connected
            if (state == DISCONNECTED) {
                client = connectionService.connect(address, endPoint.mgmtPort)
                vtepData = new OvsdbVtepData(client)
                state = CONNECTED
            }
            users.add(user)
        } catch {
            case e: VtepException =>
                // state has already been processed
                throw e
            case e: Throwable =>
                state = BROKEN
                throw new VtepStateException(endPoint, "connection failed", e)
        } finally {
            monitor.leave()
        }
    }

    override def disconnect(user: UUID) = {
        if (!monitor.enterIf(isOperative)) {
            throw new VtepStateException(endPoint, "cannot be disconnected")
        }
        try {
            log.info("Disconnecting from VTEP on {} for user " + user, endPoint)

            // Skip if not connected
            if (state == CONNECTED) {
                if (users.remove(user) && users.isEmpty) {
                    connectionService.disconnect(client)
                    client = null
                    state = DISCONNECTED
                }
            }
        } finally {
            monitor.leave()
        }
    }

    @GuardedBy("monitor")
    override def getState: VtepConnection.State.Value = state

    override def vxlanTunnelIp: Option[IPv4Addr] = {
        Await.ready(vtepData.getTunnelIp, Duration.Inf).value match {
            case Some(Success(ip)) => Some(ip)
            case Some(Failure(none: NoSuchElementException)) => None
            case Some(Failure(exception)) => throw exception
            case None => None
        }
    }

    override def macLocalUpdates: Observable[MacLocation] = ???

    override def macRemoteUpdates: Observer[MacLocation] = ???

    override def currentMacLocal: Seq[MacLocation] = ???

    override def ensureLogicalSwitch(name: String, vni: Int): Try[LogicalSwitch] = ???

    override def removeLogicalSwitch(name: String): Try[Unit] = ???

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???
}


