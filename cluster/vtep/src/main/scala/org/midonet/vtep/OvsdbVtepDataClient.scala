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

package org.midonet.vtep

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService
import org.slf4j.LoggerFactory
import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.VtepConnection.State
import org.midonet.cluster.data.vtep.model.{LogicalSwitch, MacLocation, VtepEndPoint}
import org.midonet.cluster.data.vtep.{VtepData, VtepDataClient, VtepStateException}
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.{makeAction0, makeAction1}

/**
 * This class handles the connection to an ovsdb-compliant vtep
 */
class OvsdbVtepDataClient(val endPoint: VtepEndPoint,
                      val retryMs: Long, val maxRetries: Long)
    extends VtepDataClient {

    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepDataClient])

    private val vtepThread = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("vtep-thread"))
    private val connectionService = OvsdbConnectionService.getService

    private val connection =
        new OvsdbVtepConnection(endPoint, vtepThread, connectionService,
                                retryMs, maxRetries)

    private val data = new AtomicReference[Option[VtepData]](None)

    case class StateException(msg: String)
        extends VtepStateException(endPoint, msg)

    override def getManagementIp = connection.getManagementIp
    override def getManagementPort = connection.getManagementPort
    override def connect(user: UUID) = connection.connect(user)
    override def disconnect(user: UUID) = connection.disconnect(user)
    override def getState = connection.getState
    override def getHandle = connection.getHandle
    override def observable = connection.observable

    val onStateChange = makeAction1[State.Value]({
        case State.READY =>
            val handle = connection.getHandle.get
            data.set(Some(new OvsdbVtepData(endPoint, handle.client, handle.db,
                                            vtepThread)))
        case _ =>
            data.set(None)
    })
    val onStateError = makeAction1[Throwable]({
        case e: Throwable =>
            log.error("vtep state tracking lost", e)
    })
    val onStateCompletion = makeAction0 {
        log.error("vtep state tracking lost")
    }

    connection.observable
        .subscribe(onStateChange, onStateError, onStateCompletion)

    override def vxlanTunnelIp: Option[IPv4Addr] =
        data.get.flatMap(_.vxlanTunnelIp)


    override def macLocalUpdates: Observable[MacLocation] = data.get match {
        case None => Observable.error(StateException("vtep not ready") )
        case Some(backend) => backend.macLocalUpdates
    }

    override def currentMacLocal: Seq[MacLocation] = data.get match {
        case None => throw StateException("vtep not ready")
        case Some(backend) => backend.currentMacLocal
    }

    override def macRemoteUpdater: Observer[MacLocation] = data.get match {
        case None => throw StateException("vtep not ready")
        case Some(backend) => backend.macRemoteUpdater
    }
    override def ensureLogicalSwitch(name: String, vni: Int):
        Try[LogicalSwitch] = data.get match {
        case None => throw StateException("vtep not ready")
        case Some(backend) => backend.ensureLogicalSwitch(name, vni)
    }

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)])
        : Try[Unit] = data.get match {
        case None => throw StateException("vtep not ready")
        case Some(backend) => backend.ensureBindings(lsName, bindings)
    }

    override def removeLogicalSwitch(name: String): Try[Unit] = data.get match {
        case None => throw StateException("vtep not ready")
        case Some(backend) => backend.removeLogicalSwitch(name)
    }


}

