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

import scala.concurrent.{ExecutionContext, Future}

import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService
import org.slf4j.LoggerFactory
import rx.subjects.BehaviorSubject
import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.VtepConnection.State
import org.midonet.cluster.data.vtep.model.{LogicalSwitch, MacLocation, VtepEndPoint}
import org.midonet.cluster.data.vtep.{VtepData, VtepDataClient, VtepStateException}
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}
import org.midonet.util.reactivex._

/**
 * This class handles the connection to an ovsdb-compliant vtep
 */
class OvsdbVtepDataClient(val endPoint: VtepEndPoint,
                          val retryMs: Long, val maxRetries: Long)
    extends VtepDataClient {

    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepDataClient])

    private val vtepThread = Executors.newSingleThreadExecutor(
        new NamedThreadFactory(s"vtep-$endPoint"))
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val connectionService = OvsdbConnectionService.getService

    private val connection =
        new OvsdbVtepConnection(endPoint, vtepThread, connectionService,
                                retryMs, maxRetries)

    private val dataSubject = BehaviorSubject.create[VtepData]
    private val dataObservable = dataSubject.filter(makeFunc1(_ ne null))

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
            dataSubject onNext new OvsdbVtepData(endPoint, handle.client,
                                                 handle.db, vtepThread)
        case State.DISPOSED  =>
            dataSubject onError StateException("VTEP state tracking lost")
        case _ =>
            dataSubject onNext null
    })
    val onStateError = makeAction1[Throwable]({
        case e: Throwable =>
            log.error("VTEP state tracking lost", e)
    })
    val onStateCompletion = makeAction0 {
        log.error("VTEP state tracking lost")
    }

    connection.observable
        .subscribe(onStateChange, onStateError, onStateCompletion)

    override def vxlanTunnelIp: Future[Option[IPv4Addr]] = {
        dataObservable.asFuture.flatMap(_.vxlanTunnelIp)(vtepContext)
    }

    override def macLocalUpdates: Observable[MacLocation] = {
        Observable.switchOnNext(dataObservable.map(makeFunc1(_.macLocalUpdates)))
    }

    override def currentMacLocal: Future[Seq[MacLocation]] = {
        dataObservable.asFuture.flatMap(_.currentMacLocal)(vtepContext)
    }

    override def macRemoteUpdater: Future[Observer[MacLocation]] = {
        dataObservable.asFuture.flatMap(_.macRemoteUpdater)(vtepContext)
    }

    override def ensureLogicalSwitch(name: String, vni: Int)
    : Future[LogicalSwitch] = {
        dataObservable.asFuture
                      .flatMap(_.ensureLogicalSwitch(name, vni))(vtepContext)
    }

    override def removeLogicalSwitch(name: String): Future[Unit] = {
        dataObservable.asFuture
                      .flatMap(_.removeLogicalSwitch(name))(vtepContext)
    }

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)])
    : Future[Unit] = {
        dataObservable.asFuture
                      .flatMap(_.ensureBindings(lsName, bindings))(vtepContext)
    }

}

