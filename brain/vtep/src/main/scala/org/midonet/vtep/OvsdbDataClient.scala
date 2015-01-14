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

import java.net.InetAddress
import java.util.{Set => JavaSet}
import java.util.{Map => JavaMap}
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import org.midonet.cluster.data.vtep.model.{PhysicalLocator, MacLocation, LogicalSwitch}
import org.midonet.cluster.data.vtep.VtepDataClient
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.makeRunnable
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.impl.OvsdbConnectionService
import rx.subjects.{PublishSubject, Subject}
import rx.{Observer, Observable}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise, Future}
import scala.util.Try

/**
 * This class handles the connection to an ovsdb-compliant vtep
 */
class OvsdbDataClient(val endPoint: VtepEndPoint) extends VtepDataClient {

    import VtepDataClient.State._

    private val vtepThread = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("vtep-thread"))

    private val connectionService = OvsdbConnectionService.getService

    // Ovsdb client
    private val ovsdbClient = new AtomicReference[OvsdbClient](null)
    // Ovsdb data manager
    private val ovsdbData = new AtomicReference[Future[OvsdbVtepData]](
        notConnected)
    // Vtep users
    private val users = new mutable.HashSet[UUID]()
    // Connection status
    private val state = new AtomicReference[VtepDataClient.State.Value](
        if (endPoint.mgmtIp == null) DISPOSED else DISCONNECTED
    )

    private def notConnected: Future[OvsdbVtepData] =
        Future.failed(new VtepStateException(endPoint, "not connected"))

    override def getManagementIp = endPoint.mgmtIp

    override def getManagementPort = endPoint.mgmtPort

    /**
     * Connect to vtep and return a future, which is satisfied once the
     * vtep is actually connected
     */
    override def connect(user: UUID): Future[Unit] = {
        val result: Promise[Unit] = Promise[Unit]()
        val connector = makeRunnable {
            state.get() match {
                case DISPOSED =>
                    result.failure(new VtepStateException(
                        endPoint, "disposed and cannot be connected"))
                case CONNECTED =>
                    users.add(user)
                    result.success({})
                case DISCONNECTED => try {
                    val address =
                        InetAddress.getByName(endPoint.mgmtIp.toString)
                    val client =
                        connectionService.connect(address, endPoint.mgmtPort)
                    ovsdbData.set(OvsdbVtepData.get(client, vtepThread).future)
                    ovsdbClient.set(client)
                    state.set(CONNECTED)
                    users.add(user)
                    result.success({})
                } catch {
                    case e: VtepException =>
                        result.failure(e)
                    case e: Throwable =>
                        result.failure(new VtepException(endPoint, e))
                }
            }
        }
        vtepThread.submit(connector)
        result.future
    }

    /**
     * Disconnect from vtep and return a future, satisfied with a boolean
     * indicating if the vtep was actually disconnected (true) or it is still
     * connected because there are remaining users (false)
     */
    override def disconnect(user: UUID): Future[Boolean] = {
        val result: Promise[Boolean] = Promise[Boolean]()
        val disconnector = makeRunnable {
            state.get() match {
                case CONNECTED if users.remove(user) && users.isEmpty => try {
                    connectionService.disconnect(ovsdbClient.get())
                    state.set(DISCONNECTED)
                    ovsdbData.set(notConnected)
                    ovsdbClient.set(null)
                    result.success(true)
                } catch {
                    case e: VtepException =>
                        result.failure(e)
                    case e: Throwable =>
                        result.failure(new VtepException(endPoint, e))
                }
                case CONNECTED =>
                    result.success(false)
                case DISCONNECTED =>
                    result.success(true)
                case DISPOSED =>
                    result.failure(new VtepStateException(
                        endPoint, "disposed and cannot be disconnected"))
            }

        }
        vtepThread.submit(disconnector)
        result.future
    }

    override def getState: VtepDataClient.State.Value = state.get()

    override def vxlanTunnelIp: Option[IPv4Addr] =
        Await.result(ovsdbData.get(), Duration.Inf).getTunnelIp

    override def macLocalUpdates: Observable[MacLocation] = {
        val vtep = Await.result(ovsdbData.get(), Duration.Inf)
        Observable.merge(vtep.ucastMacLocalUpdates(),
                         vtep.mcastMacLocalUpdates())
    }

    //val pipe: Subject[MacLocation, MacLocation] = PublishSubject.create()
    //pipe.observeOn().subscribe(apply)
    override def macRemoteUpdater: Observer[MacLocation] = {
        val pipe: Subject[MacLocation, MacLocation] = PublishSubject.create()
        Await.result(ovsdbData.get(), Duration.Inf).applyRemoteMacLocations(pipe)
        pipe
    }

    override def currentMacLocal: Seq[MacLocation] = {
        val vtep = Await.result(ovsdbData.get(), Duration.Inf)
        vtep.getUcastMacLocalEntries.result().toSeq ++
        vtep.getMcastMacLocalEntries.result().toSeq
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

