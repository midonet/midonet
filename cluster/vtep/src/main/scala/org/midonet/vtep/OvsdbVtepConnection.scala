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

import java.net.{InetAddress, UnknownHostException}
import java.util.UUID
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.{OvsdbClient, OvsdbConnection, OvsdbConnectionListener}
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.cluster.data.vtep.{VtepConnection, VtepStateException}
import org.midonet.util.functors.{makeAction1, makeFunc1, makeRunnable}

/**
 * This class handles the connection to an ovsdb-compliant vtep
 */
object OvsdbVtepConnection {
    import VtepConnection._

    // Timer thread
    protected[OvsdbVtepConnection] val timerThread =
        new ScheduledThreadPoolExecutor(1)

    // Connection handle
    case class OvsdbHandle(client: OvsdbClient, db: DatabaseSchema)
        extends VtepHandle

    // Connection states
    abstract class ConnectionStatus(val state: State.Value)

    // - Disconnected: The state when:
    //   (a) The client is created.
    //   (b) Connection has been terminated by a explicit user request
    case object Disconnected extends ConnectionStatus(State.DISCONNECTED)
    // - Connected: The state when:
    //   (a) The connection to the vtep has been successfully established
    case class Connected(client: OvsdbClient)
        extends ConnectionStatus(State.CONNECTED)
    // - Disconnecting: The state when:
    //   (a) A disconnection has ben requested, but the operation has not
    //       yet been completed.
    case class Disconnecting(Client: OvsdbClient)
        extends ConnectionStatus(State.DISCONNECTING)
    // - Broken: The state when:
    //   (a) A connection request failed
    //   (b) A connection was dropped from the vtep/network
    case class Broken(ms: Long, retries: Long)
        extends ConnectionStatus(State.BROKEN)
    // - Connecting: The state when:
    //   (a) A connection was broken and it's waiting for re-connection
    case class Connecting(ms: Long, retries: Long, task: ScheduledFuture[_])
        extends ConnectionStatus(State.CONNECTING)
    // - Ready: The state when:
    //   (a) The connection has been established and the table schema
    //       information has been downloaded from the vtep.
    case class Ready(handle: OvsdbHandle) extends ConnectionStatus(State.READY)
    // - Disposed: The state when:
    //   (a) The connection is broken and no retries are left
    case object Disposed extends ConnectionStatus(State.DISPOSED)
}

class OvsdbVtepConnection(val endPoint: VtepEndPoint, vtepThread: Executor,
                          connectionService: OvsdbConnection,
                          retryMs: Long, maxRetries: Long)
    extends VtepConnection {
    import OvsdbVtepConnection._
    import VtepConnection.State
    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepConnection])
    private val vtepScheduler = Schedulers.from(vtepThread)
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)

    override def getManagementIp = endPoint.mgmtIp
    override def getManagementPort = endPoint.mgmtPort

    private val state = new AtomicReference[ConnectionStatus](Disconnected)
    private val stateSubject = BehaviorSubject.create[ConnectionStatus](state.get)
    private val connState = stateSubject.asObservable.distinctUntilChanged()
        .onBackpressureBuffer().observeOn(vtepScheduler)

    /** an observable with the vtep connection states */
    private val stateObservable = connState.map[State.Value](makeFunc1(_.state))

    private val users = new mutable.HashSet[UUID]()

    case class StateException(msg: String)
        extends VtepStateException(endPoint, msg)

    // Setup automatic connection retry
    connState.subscribe(makeAction1[ConnectionStatus] {
        case Broken(ms, left) if left > 0 => onRetry()
        case Broken(ms, left) =>
            log.error("VTEP connection failure: {}", endPoint)
            state.set(Disposed)
            stateSubject.onNext(state.get())
        case Connected(client) => onConnected()
        case _ =>
    })

    /** Process connection-closed events from vtep/network.
      * This must be executed from the vtep execution thread */
    private def onClose(ovsdbClient: OvsdbClient): Unit = {
        state.get() match {
            case Disconnecting(client) if client == ovsdbClient =>
                state.set(Disconnected)
            case Connected(client) if client == ovsdbClient =>
                state.set(Broken(retryMs, maxRetries))
            case Ready(handle) if handle.client == ovsdbClient =>
                state.set(Broken(retryMs, maxRetries))
            case other =>
                log.debug("unexpected vtep client close event: " + ovsdbClient +
                          ", state: " + other)
        }
        stateSubject.onNext(state.get())
    }
    connectionService.registerConnectionListener(new OvsdbConnectionListener {
        override def connected(client: OvsdbClient): Unit = {}
        override def disconnected(client: OvsdbClient): Unit =
            vtepThread.execute(makeRunnable(onClose(client)))
    })

    // Log connection failure and set state to broken
    private def vtepFailed(e: Throwable, retriesLeft: Long): Unit = e match {
        case t: UnknownHostException =>
            log.warn("Unknown VTEP address: " + endPoint, e)
            state.set(Broken(retryMs, retriesLeft))
        case NonFatal(t) =>
            log.warn("VTEP connection failure: " + endPoint, t)
            state.set(Broken(retryMs, retriesLeft))
    }

    /** Activate connection to vtep.
      * This must be executed from the vtep execution thread
      * @param user is the user requiring the activation (null on retry) */
    private def onActivate(user: UUID): Unit = {
        if (user != null)
            users.add(user)
        state.get() match {
            case Disconnected if user != null => try {
                val address = InetAddress.getByName(endPoint.mgmtIpString)
                val client = connectionService
                    .connect(address, endPoint.mgmtPort)
                state.set(Connected(client))
            } catch {
                case NonFatal(e) => vtepFailed(e, maxRetries)
            }
            case Connecting(ms, left, _) => try {
                val address = InetAddress.getByName(endPoint.mgmtIpString)
                val client = connectionService
                    .connect(address, endPoint.mgmtPort)
                state.set(Connected(client))
            } catch {
                case NonFatal(e) => vtepFailed(e, left - 1)
            }
            case other =>
                log.debug("ignoring connection request for: " + endPoint +
                          ", state: " + other + ", user: " + user)
        }
        stateSubject.onNext(state.get())
    }

    /** Try to restore a broken connection
      * This must be executed from the vtep execution thread. */
    private def onRetry() = {
        state.get() match {
            case Broken(ms, left) if left > 0 =>
                val reconnect = makeRunnable(vtepThread.execute(
                    makeRunnable(onActivate(user = null))))
                val task = timerThread.schedule(
                    reconnect, ms, TimeUnit.MILLISECONDS)
                state.set(Connecting(ms, left, task))
            case other =>
                log.debug("ignoring retry request for: " + endPoint +
                          ", state: " + other)
        }
        stateSubject.onNext(state.get())
    }

    /** Download table data from the vtep, once the connection is established
      * This must be executed from the vtep execution thread. */
    private def onConnected() = {
        state.get() match {
            case Connected(client) =>
                OvsdbTools.getDbSchema(client, OvsdbTools.DB_HARDWARE_VTEP)
                    .future.onComplete({
                    case Failure(e) =>
                        log.error("cannot get ovsdb schema for" + endPoint, e)
                        // the following results in a 'BROKEN' state
                        // and causes a retry
                        connectionService.disconnect(client)
                    case Success(dbSchema) =>
                        log.info("retrieved hardware vtep schema")
                        state.get() match {
                            case Connected(cl) if cl == client =>
                                state.set(Ready(OvsdbHandle(client, dbSchema)))
                                stateSubject.onNext(state.get())
                            case _ =>
                                log.debug("ignored vtep schema on changed state")
                        }
                })(vtepContext)
            case other =>
                log.debug("ignoring ready request for: " + endPoint +
                          ", state: " + other)
        }
    }

    /** Deactivate connection to vtep.
      * This must bue executed from the vtep execution thread.
      * @param user is the user abandoning the vtep (null on vtep-initiated) */
    private def onDeactivate(user: UUID) = {
        if (user == null || users.remove(user)) {
            if (users.nonEmpty) {
                log.debug("remaining users: ignoring disconnect request for: " +
                          endPoint + ", state: " + state.get + ", user: " + user)
            } else state.get() match {
                case Connected(client) =>
                    state.set(Disconnecting(client))
                    connectionService.disconnect(client)
                case Ready(handle) =>
                    state.set(Disconnecting(handle.client))
                    connectionService.disconnect(handle.client)
                case Broken(_, _) =>
                    state.set(Disconnected)
                case Disposed  =>
                    state.set(Disconnected)
                case Connecting(_, _, task) =>
                    task.cancel(false)
                    state.set(Disconnected)
                case other =>
                    log.debug("ignoring disconnect request for: " + endPoint +
                              ", state: " + other + ", user: " + user)
            }
            stateSubject.onNext(state.get())
        }
    }

    /**
     * Connect to vtep, registering the given user reference
     */
    override def connect(user: UUID): Unit = {
        vtepThread.execute(makeRunnable(onActivate(user)))
    }

    /**
     * Disconnect from vtep and return a future, satisfied with a boolean
     * indicating if the vtep was actually disconnected (true) or it is still
     * connected because there are remaining users (false)
     */
    override def disconnect(user: UUID): Unit = {
        vtepThread.execute(makeRunnable(onDeactivate(user)))
    }

    /**
     * publish an observable with connection state updates
     */
    override def observable = stateObservable

    /**
     * Get the current connection state
     */
    override def getState: State.Value = state.get().state

    /**
     * Get the current ovsdb client handler
     */
    override def getHandle: Option[OvsdbHandle] = state.get() match {
        case Ready(handle) => Some(handle)
        case _ => None
    }
}

