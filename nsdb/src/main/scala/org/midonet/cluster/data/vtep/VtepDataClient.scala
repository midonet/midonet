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

package org.midonet.cluster.data.vtep

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState.State
import org.midonet.cluster.data.vtep.model._
import org.midonet.util.functors.makeFunc1

/**
 * A client class for the connection to a VTEP-enabled switch. A client
 * instance allows multiple users to share the same connection to a VTEP,
 * while monitoring the connection for possible failure and including a
 * recovery mechanism.
 */
trait VtepDataClient extends VtepConnection with VtepData

/**
 * Handle connections to a VTEP
 */
trait VtepConnection {

    /**
     * The management end-point for the VTEP connection.
     */
    def endPoint: VtepEndPoint

    /**
     * Connect to the VTEP using a specific user. If the VTEP is already
     * connected or connecting, it does nothing.
     */
    def connect(): Future[State]

    /**
     * Disconnects from the VTEP. If the VTEP is already disconnecting
     * or disconnected, it does nothing.
     */
    def disconnect(): Future[State]

    /**
     * Releases all resources used by the VTEP connection.
     */
    def close()(implicit ex: ExecutionContext): Future[State]

    /**
     * Get a observable to get the current state and monitor connection changes
     */
    def observable: Observable[State]

    /**
     * Get the current connection state
     */
    def getState: State

    /**
     * Get the connection handle
     */
    def getHandle: Option[VtepConnection.VtepHandle]

    /**
     * Wait for a specific state.
     */
    def awaitState(expected: State, timeout: Duration): State = {
        awaitState(Set(expected), timeout)
    }

    /**
     * Wait for a state in a specific set.
     */
    def awaitState(expected: Set[State], timeout: Duration): State = {
        observable.first(makeFunc1(expected.contains))
                  .toBlocking
                  .toFuture
                  .get(timeout.toMillis, TimeUnit.MILLISECONDS)
    }

}

object VtepConnection {
    /** An enumeration indicating the state of the VTEP connection. Each state
      * value has two boolean indicating whether a data VTEP operation should
      * be allowed to continue when the connection is in the specified state.
      * When `isDecisive` is true, the current connection state allows a
      * data operation to complete, either successfully or not. Otherwise, when
      * `isDecisive` is false any data operation should be postponed until
      * the VTEP connection reaches a decisive state. The `isFailed` field
      * indicates whether a data operation should always fail for the current
      * state. */
    object ConnectionState extends Enumeration {
        class State(val isDecisive: Boolean, val isFailed: Boolean) extends Val
        final val Disconnected = new State(true, true)
        final val Connected = new State(false, false)
        final val Ready = new State(true, false)
        final val Disconnecting = new State(false, true)
        final val Broken = new State(true, true)
        final val Connecting = new State(false, false)
        final val Failed = new State(true, true)
        final val Disposed = new State(true, true)
    }
    /** A connection handle to be used to perform operations on the vtep */
    abstract class VtepHandle
}

/**
 * Access data from a VTEP
 */
trait VtepData {

    /** Returns all physical switches. */
    def physicalSwitches: Future[Seq[PhysicalSwitch]]

    /** Gets the physical switch corresponding to the current VTEP endpoint. */
    def physicalSwitch: Future[Option[PhysicalSwitch]]

    /** Lists all logical switches. */
    def logicalSwitches: Future[Seq[LogicalSwitch]]

    /** Gets the logical switch with the specified name. */
    def logicalSwitch(name: String): Future[Option[LogicalSwitch]]

    /** Creates a new logical switch with the specified name and VNI. If a
      * logical switch with the same name and VNI already exists, the method
      * succeeds immediately. */
    def createLogicalSwitch(name: String, vni: Int): Future[LogicalSwitch]

    /** Deletes the logical switch with the specified name, along with all its
      * bindings and MAC entries. */
    def deleteLogicalSwitch(name: String): Future[Unit]

    /** Lists all physical ports. */
    def physicalPorts: Future[Seq[PhysicalPort]]

    /** Gets the physical port with the specified port identifier. */
    def physicalPort(portId: UUID): Future[Option[PhysicalPort]]

    /** Adds the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The methds does not change any existing bindings for the specified
      * physical ports. */
    def addBindings(lsName: String, bindings: Iterable[(String, Short)])
    : Future[Int]

    /** Sets the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The method overwrites any of the previous bindings for the specified
      * physical ports, and replaces them with the given ones. The physical
      * ports that are not included in the bindings list are left unchanged.
      * The method returns a future with the number of physical ports that
      * were changed. */
    def setBindings(lsName: String, bindings: Iterable[(String, Short)])
    : Future[Int]

    /** Clears all bindings for the specified logical switch name. */
    def clearBindings(lsName: String): Future[Int]

    /** Returns an [[Observable]] that emits updates for the `Ucast_Mac_Local`
      * and `Mcast_Mac_Local` tables, with the MACs that are local to the VTEP
      * and should be published to other members of a VxLAN gateway. */
    def macLocalUpdates: Observable[MacLocation]

    /** Returns an [[Observer]] that will write updates to the remote MACs in
      * the `Ucast_Mac_Remote` or `Mcast_Mac_Remote` tables. */
    def macRemoteUpdater: Future[Observer[MacLocation]]

    /** Provides a snapshot of the `Ucast_Mac_Local` and `Mcast_Mac_Local`
      * tables. */
    def currentMacLocal: Future[Seq[MacLocation]]

}


