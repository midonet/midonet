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

package org.midonet.southbound.vtep

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{ExecutionContext, Future}

import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState._
import org.midonet.cluster.data.vtep.model._
import org.midonet.cluster.data.vtep.{VtepData, VtepDataClient, VtepStateException}
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}
import org.midonet.util.reactivex._

object OvsdbVtepDataClient {

    /**
     * Creates a new VTEP data client with the specified management IP address
     * and port, default connection service and without connection retry.
     */
    def apply(mgmtIp: IPv4Addr, mgmtPort: Int): OvsdbVtepDataClient = {
        new OvsdbVtepDataClient(VtepEndPoint(mgmtIp, mgmtPort), 0 seconds, 0)
    }

    /**
     * Creates a new VTEP data client with the specified management IP address
     * and port, retry policy and default connection service.
     */
    def apply(mgmtIp: IPv4Addr, mgmtPort: Int, retryInterval: Duration,
              maxRetries: Long): OvsdbVtepDataClient = {
        new OvsdbVtepDataClient(VtepEndPoint(mgmtIp, mgmtPort), retryInterval,
                                maxRetries)
    }

}

/**
 * This class handles the connection to an ovsdb-compliant vtep
 */
class OvsdbVtepDataClient(val endPoint: VtepEndPoint,
                          val retryInterval: Duration, val maxRetries: Long)
    extends VtepDataClient {

    private val log =
        Logger(LoggerFactory.getLogger(s"org.midonet.vtep.vtep-$endPoint"))

    private val vtepThread = Executors.newSingleThreadExecutor(
        new NamedThreadFactory(s"vtep-$endPoint"))
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val eventThread = Executors.newSingleThreadExecutor(
        new NamedThreadFactory(s"vtep-$endPoint-event"))

    private val connection: OvsdbVtepConnection = newConnection()

    private val data = new AtomicReference[VtepData](null)
    private val stateSubject = BehaviorSubject.create[State]

    private val onStateChange = makeAction1[State] { state =>
        if (Ready == state) {
            val handle = connection.getHandle.get
            data.set(new OvsdbVtepData(endPoint, handle.client, handle.db,
                                       vtepThread, eventThread))
        } else {
            data.set(null)
        }
        stateSubject onNext state
    }
    private val onStateError = makeAction1[Throwable] { e: Throwable =>
        log.error("VTEP state tracking lost", e)
        stateSubject onError e
    }
    private val onStateCompletion = makeAction0 {
        log.error("VTEP state tracking lost")
        stateSubject.onCompleted()
    }

    connection.observable.subscribe(onStateChange, onStateError,
                                    onStateCompletion)

    override def connect() = connection.connect()

    override def disconnect() = connection.disconnect()

    override def close()(implicit ex: ExecutionContext): Future[State] = {
        connection.close() map { state =>
            vtepThread.shutdown()
            eventThread.shutdown()
            state
        }
    }

    override def getState = connection.getState

    override def getHandle = connection.getHandle

    override def observable = stateSubject.asObservable()

    /** Lists all physical switches. */
    override def physicalSwitches: Future[Seq[PhysicalSwitch]] = {
        onReady { _.physicalSwitches }
    }

    /** Gets the physical switch corresponding to the current VTEP endpoint. */
    override def physicalSwitch: Future[Option[PhysicalSwitch]] = {
        onReady { _.physicalSwitch }
    }

    /** Lists all logical switches. */
    override def logicalSwitches: Future[Seq[LogicalSwitch]] = {
        onReady { _.logicalSwitches }
    }

    /** Gets the logical switch with the specified name. */
    override def logicalSwitch(name: String): Future[Option[LogicalSwitch]] = {
        onReady { _.logicalSwitch(name) }
    }

    /** Creates a new logical switch with the specified name and VNI. If a
      * logical switch with the same name and VNI already exists, the method
      * succeeds immediately. */
    override def createLogicalSwitch(name: String, vni: Int)
    : Future[UUID] = {
        onReady { _.createLogicalSwitch(name, vni) }
    }

    /** Deletes the logical switch, along with all its bindings and MAC entries.
      */
    override def deleteLogicalSwitch(id: UUID): Future[Int] = {
        onReady { _.deleteLogicalSwitch(id) }
    }

    /** Lists all physical ports. */
    override def physicalPorts: Future[Seq[PhysicalPort]] = {
        onReady { _.physicalPorts }
    }

    /** Gets the physical port with the specified port identifier. */
    override def physicalPort(portId: UUID): Future[Option[PhysicalPort]] = {
        onReady { _.physicalPort(portId) }
    }

    /** Adds the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The methds does not change any existing bindings for the specified
      * physical ports. */
    override def addBindings(lsId: UUID, bindings: Iterable[(String, Short)])
    : Future[Int] = {
        onReady { _.addBindings(lsId, bindings) }
    }

    /** Sets the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The method overwrites any of the previous bindings for the specified
      * physical ports, and replaces them with the given ones. The physical
      * ports that are not included in the bindings list are left unchanged.
      * The method returns a future with the number of physical ports that
      * were changed. */
    override def setBindings(lsId: UUID, bindings: Iterable[(String, Short)])
    : Future[Int] = {
        onReady { _.setBindings(lsId, bindings) }
    }

    /** Clears all bindings for the specified logical switch name. */
    override def clearBindings(lsId: UUID): Future[Int] = {
        onReady { _.clearBindings(lsId) }
    }

    /** Returns an [[Observable]] that emits updates for the `Ucast_Mac_Local`
      * and `Mcast_Mac_Local` tables, with the MACs that are local to the VTEP
      * and should be published to other members of a VxLAN gateway. */
    override def macLocalUpdates: Observable[MacLocation] = {
        onReady { _.macLocalUpdates }
    }

    /** Returns an [[Observer]] that will write updates to the remote MACs in
      * the `Ucast_Mac_Local` or `Mcast_Mac_Local` tables. */
    override def macLocalUpdater: Future[Observer[MacLocation]] = {
        onReady { _.macLocalUpdater }
    }

    /** Returns an [[Observer]] that will write updates to the remote MACs in
      * the `Ucast_Mac_Remote` or `Mcast_Mac_Remote` tables. */
    override def macRemoteUpdater: Future[Observer[MacLocation]] = {
        onReady { _.macRemoteUpdater }
    }

    /** Provides a snapshot of the `Ucast_Mac_Local` and `Mcast_Mac_Local`
      * tables. */
    override def currentMacLocal: Future[Seq[MacLocation]] = {
        onReady { _.currentMacLocal }
    }

    /** Provides a snapshot of the `Ucast_Mac_Remote` and `Mcast_Mac_Remote`
      * tables. */
    override def currentMacRemote: Future[Seq[MacLocation]] = {
        onReady { _.currentMacRemote }
    }

    /**
     * Creates a new OVSDB connection.
     */
    @VisibleForTesting
    protected def newConnection(): OvsdbVtepConnection = {
        new OvsdbVtepConnection(endPoint, vtepThread, retryInterval, maxRetries)
    }

    /**
     * Calls the specified function when the VTEP connection is [[Ready]] with
     * an [[OvsdbVtepData]] instance as argument. The method wait for the VTEP
     * connection state be decisive, and succeeds only if the VTEP becomes
     * [[Ready]], in which case the method returns a future that will complete
     * with the same result as the one returned by the given function.
     * Otherwise, the future fails.
     */
    private def onReady[T](f: (VtepData) => Future[T]): Future[T] = {
        stateSubject.filter(makeFunc1(_.isDecisive))
                    .map[VtepData](makeFunc1 { state =>
            if (state.isFailed) {
                throw new VtepStateException(
                    endPoint,
                    s"VTEP connection state $state cannot handle a data request")
            }
            data.get
        }).asFuture.flatMap(f)(vtepContext)
    }

    /**
     * Calls the specified function when the VTEP connection is [[Ready]] with
     * an [[OvsdbVtepData]] instance as argument. The method returns an
     * [[Observable]] which emits the notifications emitted by the observable
     * returns by the given function after the connection reaches the [[Ready]]
     * state. The returned observable always completes with an error when
     * the VTEP connection reaches a decisive failed state.
     */
    private def onReady[T](f: (VtepData) => Observable[T]): Observable[T] = {
        val observables =
            stateSubject.filter(makeFunc1(_.isDecisive))
                        .map[Observable[T]](makeFunc1 { state =>
                if (state.isFailed) {
                    throw new VtepStateException(
                        endPoint,
                        "Update stream failed because the VTEP connection " +
                        s"state $state cannot handle more data requests")
                }
                f(data.get)
            })
        Observable.switchOnNext(observables)
    }

}

