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
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.model._
import org.midonet.util.functors.makeFunc1

/** This trait defines the interface required to create and interact
  *  with an OVSDB connection, from the point of view of the
  *  VtepDataClient.  The class implementing this trait should only need
  *  to worry about managing the connection to the VTEP and exposing the
  *  state updates by interacting with the client provided by the
  *  (external) OVSDB plugin library.
  *
  *  The OVSDB plugin exposes a [[OvsdbVtepConnection.OvsdbHandle]] to the
  *  connection that is exposed also by this interface.
  *
  *  Data operations are *not* relevant here.
  */
trait VtepConnection {

    /** The management end-point for the VTEP connection. */
    def endPoint: VtepEndPoint

    /** Connect to the VTEP using a specific user. If the VTEP is already
      * connected or connecting, it does nothing.
      */
    def connect(): Future[ConnectionState.State]

    /** Disconnects from the VTEP. If the VTEP is already disconnecting
      * or disconnected, it does nothing.
      */
    def disconnect(): Future[ConnectionState.State]

    /** Releases all resources used by the VTEP connection.
      */
    def close()(implicit ex: ExecutionContext): Future[ConnectionState.State]

    /** Get a observable to get the current state and monitor connection
      * changes.
      */
    def observable: Observable[ConnectionState.State]

    /** Get the current connection state. */
    def getState: ConnectionState.State

    /** Get the connection handle. */
    def getHandle: Option[OvsdbVtepConnection.OvsdbHandle]

    /** Wait for a specific state. */
    final def awaitState(expected: ConnectionState.State, timeout: Duration)
    : ConnectionState.State = { awaitState(Set(expected), timeout) }

    /** Wait for a state in a specific set. */
    final def awaitState(expected: Set[ConnectionState.State],
                         timeout: Duration): ConnectionState.State = {
        observable.first(makeFunc1(expected.contains))
                  .toBlocking
                  .toFuture
                  .get(timeout.toMillis, TimeUnit.MILLISECONDS)
    }

}

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

/** This trait models the data manipulation options that are required by
  *  the VxLAN Gateway feature when interacting with a VTEP's OVSDB
  *  instance.  Connection details are separated to the
  *  [[VtepConnection]] trait.  Implementing classes are free to decide
  *  how to manage and establish a connection, as long as they implement
  *  these high level operations.
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
    def createLogicalSwitch(name: String, vni: Int): Future[UUID]

    /** Deletes the logical switch, along with all its bindings and MAC
      * entries.
      */
    def deleteLogicalSwitch(id: UUID): Future[Int]

    /** Lists all physical ports. */
    def physicalPorts: Future[Seq[PhysicalPort]]

    /** Gets the physical port with the specified port identifier. */
    def physicalPort(portId: UUID): Future[Option[PhysicalPort]]

    /** Adds the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The methds does not change any existing bindings for the specified
      * physical ports. */
    def addBindings(lsId: UUID, bindings: Iterable[(String, Short)])
    : Future[Int]

    /** Sets the bindings for the logical switch with the specified name. The
      * bindings are specified as an [[Iterable]] of port name and VLAN pairs.
      * The method overwrites any of the previous bindings for the specified
      * logical switch, and replaces them with the given ones. The method
      * returns a future with the number of physical ports that were changed. */
    def setBindings(lsId: UUID, bindings: Iterable[(String, Short)]): Future[Int]

    /** Clears all bindings for the specified logical switch. */
    def clearBindings(lsId: UUID): Future[Int]

    /** Returns an [[Observable]] that emits updates for the `Ucast_Mac_Local`
      * and `Mcast_Mac_Local` tables, with the MACs that are local to the VTEP
      * and should be published to other members of a VxLAN gateway. */
    def macLocalUpdates: Observable[MacLocation]

    /** Returns an [[Observable]] that emits updates for the `Ucast_Mac_Remote`
      * and `Mcast_Mac_Remote` tables, with the MACs that are remote to the
      * VTEP. */
    //def macRemoteUpdates: Observable[MacLocation]

    /** Returns an [[Observer]] that will write updates to the remote MACs in
      * the `Ucast_Mac_Remote` or `Mcast_Mac_Remote` tables. */
    def macRemoteUpdater: Future[Observer[MacLocation]]

    /** Provides a snapshot of the `Ucast_Mac_Local` and `Mcast_Mac_Local`
      * tables. */
    def currentMacLocal: Future[Seq[MacLocation]]

    /** Provides a snapshot of the `Ucast_Mac_Remote` and `Mcast_Mac_Remote`
      * tables. */
    def currentMacRemote: Future[Seq[MacLocation]]

}


