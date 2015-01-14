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

import java.util.concurrent.atomic.AtomicReference
import java.util.{Set => JavaSet}
import java.util.{Map => JavaMap}
import java.util.UUID
import java.util.concurrent.Executors

import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.util.Try

import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService
import org.slf4j.LoggerFactory
import rx.subjects.{PublishSubject, Subject}
import rx.{Observer, Observable}

import org.midonet.cluster.data.vtep.VtepConnection.State
import org.midonet.cluster.data.vtep.model.{VtepEndPoint, PhysicalLocator, MacLocation, LogicalSwitch}
import org.midonet.cluster.data.vtep.{VtepStateException, VtepData, VtepDataClient}
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

    val onStateChange = makeAction1[State.Value]({
        case State.READY =>
            val client = connection.getOvsdbClient.get
            val db = connection.getOvsdbDB.get
            data.set(Some(new OvsdbVtepData(endPoint, client, db, vtepThread)))
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

    connection.stateObservable
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

    //val pipe: Subject[MacLocation, MacLocation] = PublishSubject.create()
    //pipe.observeOn().subscribe(apply)
    override def macRemoteUpdater: Observer[MacLocation] = {
        val pipe: Subject[MacLocation, MacLocation] = PublishSubject.create()
        Await.result(ovsdbData.get(), Duration.Inf).applyRemoteMacLocations(pipe)
        pipe
    }
    override def ensureLogicalSwitch(name: String,
                                     vni: Int): Try[LogicalSwitch] = ???

    override def removeLogicalSwitch(name: String): Try[Unit] = ???

    override def ensureBindings(lsName: String,
                                bindings: Iterable[(String, Short)]): Try[Unit] = ???

    def getLogicalSwitches: JavaSet[LogicalSwitch] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getLogicalSwitches.result()

    def getPhysicalLocators: JavaSet[PhysicalLocator] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getPhysicalLocators.result()

    def getPhysicalLocatorSets: JavaMap[UUID, java.util.Set[UUID]] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getPhysicalLocatorSets.result()

    def getLocalUcastMacs: JavaSet[MacLocation] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getUcastMacLocalEntries.result()

    def getRemoteUcastMacs: JavaSet[MacLocation] =
        Await.result(ovsdbData.get(), Duration.Inf)
            .getUcastMacRemoteEntries.result()

}

