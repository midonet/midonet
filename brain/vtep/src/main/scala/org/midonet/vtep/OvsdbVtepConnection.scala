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

import java.net.{UnknownHostException, InetAddress}
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Success, Failure}

import org.opendaylight.ovsdb.lib.schema.DatabaseSchema
import org.opendaylight.ovsdb.lib.{OvsdbConnection, OvsdbConnectionListener, OvsdbClient}
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.Subscriber

import org.midonet.cluster.data.vtep.model.VtepEndPoint
import org.midonet.cluster.data.vtep.{VtepStateException, VtepConnection}
import org.midonet.util.functors.{makeAction1, makeFunc1, makeRunnable}
import org.midonet.vtep.schema._

/**
 * This class handles the connection to an ovsdb-compliant vtep
 */
object OvsdbVtepConnection {
    import VtepConnection.State

    class OvsdbDB(val dbs: DatabaseSchema) {
        val lsTable = new LogicalSwitchTable(dbs)
        val psTable = new PhysicalSwitchTable(dbs)
        val locSetTable = new PhysicalLocatorSetTable(dbs)
        val locTable = new PhysicalLocatorTable(dbs)
        val uLocalTable = new UcastMacsLocalTable(dbs)
        val uRemoteTable = new UcastMacsRemoteTable(dbs)
        val mLocalTable = new McastMacsLocalTable(dbs)
        val mRemoteTable = new McastMacsRemoteTable(dbs)
    }

    // Timer thread
    protected[OvsdbVtepConnection] val timerThread =
        new ScheduledThreadPoolExecutor(1)

    // Connection states
    abstract class ConnectionStatus(val state: State.Value)

    // - Disconnected: The state when:
    //   (a) The client is created.
    //   (b) Connection has been terminated by a explicit user request
    case class Disconnected()
        extends ConnectionStatus(State.DISCONNECTED)
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
    case class Ready(client: OvsdbClient, db: OvsdbDB)
        extends ConnectionStatus(State.READY)
}

class OvsdbVtepConnection(val endPoint: VtepEndPoint, vtepThread: Executor,
                          connectionService: OvsdbConnection,
                          retryMs: Long, maxRetries: Long)
    extends VtepConnection {
    import VtepConnection.State
    import OvsdbVtepConnection._
    private val log = LoggerFactory.getLogger(classOf[OvsdbVtepConnection])
    private val vtepScheduler = Schedulers.from(vtepThread)
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)

    override def getManagementIp = endPoint.mgmtIp
    override def getManagementPort = endPoint.mgmtPort

    private val state = new AtomicReference[ConnectionStatus](Disconnected())
    private val stateSubject = BehaviorSubject.create[ConnectionStatus](state.get)
    private val connectionState = stateSubject.asObservable.distinctUntilChanged()
        .observeOn(vtepScheduler)

    /** an observable with the vtep connection states */
    val stateObservable = connectionState.map[State.Value](makeFunc1(_.state))

    private val users = new mutable.HashSet[UUID]()

    case class StateException(msg: String)
        extends VtepStateException(endPoint, msg)

    // Setup automatic connection retry
    connectionState.subscribe(makeAction1[ConnectionStatus] {
        case Broken(ms, left) if left > 0 => onRetry()
        case Connected(client) => onConnected()
        case _ =>
    })

    /** Process connection-closed events from vtep/network.
      * This must be executed from the vtep execution thread */
    private def onClose(ovsdbClient: OvsdbClient): Unit = {
        state.get() match {
            case Disconnecting(client) if client == ovsdbClient =>
                state.set(Disconnected())
            case Connected(client) if client == ovsdbClient =>
                state.set(Broken(retryMs, maxRetries))
            case Ready(client, _) if client == ovsdbClient =>
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

    /** Activate connection to vtep.
      * This must be executed from the vtep execution thread
      * @param user is the user requiring the activation (null on retry) */
    private def onActivate(user: UUID): Unit = {
        if (user != null)
            users.add(user)
        state.get() match {
            case Disconnected() if user != null => try {
                val address = InetAddress.getByName(endPoint.mgmtIpString)
                val client = connectionService
                    .connect(address, endPoint.mgmtPort)
                state.set(Connected(client))
            } catch {
                case e: UnknownHostException =>
                    log.error("Unknown VTEP address: " + endPoint, e)
                    state.set(Broken(retryMs, maxRetries))
                case t: Throwable =>
                    log.error("VTEP connection failure: " + endPoint, t)
                    state.set(Broken(retryMs, maxRetries))
            }
            case Connecting(ms, left, _) => try {
                val address = InetAddress.getByName(endPoint.mgmtIpString)
                val client = connectionService
                    .connect(address, endPoint.mgmtPort)
                state.set(Connected(client))
            } catch {
                case e: UnknownHostException =>
                    log.error("Unknown VTEP address: " + endPoint, e)
                    state.set(Broken(ms, left - 1))
                case t: Throwable =>
                    log.error("VTEP connection failure: " + endPoint, t)
                    state.set(Broken(ms, left - 1))
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
                OvsdbUtil.getDbSchema(client, OvsdbUtil.DB_HARDWARE_VTEP)
                    .future.onComplete({
                    case Failure(e) =>
                        log.error("cannot get ovsdb schema for" + endPoint, e)
                        // the following results in a 'BROKEN' state
                        // and causes a retry
                        connectionService.disconnect(client)
                    case Success(dbSchema) =>
                        state.compareAndSet(Connected(client),
                                            Ready(client, new OvsdbDB(dbSchema)))
                        stateSubject.onNext(state.get())
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
            state.get() match {
                case Connected(client) if users.isEmpty =>
                    state.set(Disconnecting(client))
                    connectionService.disconnect(client)
                case Ready(client, _) if users.isEmpty =>
                    state.set(Disconnecting(client))
                    connectionService.disconnect(client)
                case Broken(_, _) if users.isEmpty =>
                    state.set(Disconnected())
                case Connecting(_, _, task) if users.isEmpty =>
                    task.cancel(false)
                    state.set(Disconnected())
                case other =>
                    log.debug("ignoring retry request for: " + endPoint +
                                  ", state: " + other + ", user: " + user)
            }
            stateSubject.onNext(state.get())
        }
    }

    /**
     * Generate a future to wait for a specific state.
     * The completion thread is the vtep thread.
     */
    def futureState(expected: Set[State.Value]): Future[State.Value] = {
        val result = Promise[State.Value]()
        connectionState.subscribe(new Subscriber[ConnectionStatus]() {
            override def onError(e: Throwable): Unit =
                result.failure(e)
            override def onCompleted(): Unit =
                result.failure(StateException("no state available"))
            override def onNext(st: ConnectionStatus): Unit = {
                if (expected.contains(st.state)) {
                    result.success(st.state)
                    this.unsubscribe()
                }
            }
        })
        result.future
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
     * Get the current connection state
     */
    override def getState: State.Value = state.get().state

    /**
     * Get the current ovsdb client handler
     */
    def getOvsdbClient: Option[OvsdbClient] = state.get() match {
        case Connected(client) => Some(client)
        case Ready(client, _) => Some(client)
        case _ => None
    }

    /**
     * Get the current ovsdb database tables
     */
    def getOvsdbDB: Option[OvsdbDB] = state.get() match {
        case Ready(_, db) => Some(db)
        case _ => None
    }
}

