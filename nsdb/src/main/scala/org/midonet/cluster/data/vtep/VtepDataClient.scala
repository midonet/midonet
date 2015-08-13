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

import scala.concurrent.Future
import scala.concurrent.duration.Duration

import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState.State
import org.midonet.cluster.data.vtep.model.{MacLocation, LogicalSwitch}
import org.midonet.packets.IPv4Addr
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
    /** @return The VTEP management IP */
    def getManagementIp: IPv4Addr

    /** @return The VTEP management port */
    def getManagementPort: Int

    /**
     * Connect to the VTEP using a specific user. If the VTEP is already
     * connected or connecting, it does nothing;
     * @param user The user asking for the connection
     */
    def connect(user: UUID): Unit

    /**
     * Disconnects from the VTEP. If the VTEP is already disconnecting
     * or disconnected, it does nothing;
     * @param user The user that previously asked for this connection
     */
    def disconnect(user: UUID): Unit

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
      * the VTEP connection reaches a decisive state. The `isFatal` field
      * indicates whether a data operation should always fail for the current
      * state. */
    object ConnectionState extends Enumeration {
        class State(val isDecisive: Boolean, val isFatal: Boolean) extends Val
        final val Disconnected = new State(true, true)
        final val Connected = new State(false, false)
        final val Ready = new State(true, false)
        final val Disconnecting = new State(false, true)
        final val Broken = new State(true, true)
        final val Connecting = new State(false, false)
        final val Disposed = new State(true, true)
    }
    /** A connection handle to be used to perform operations on the vtep */
    abstract class VtepHandle
}

/**
 * Access data from a VTEP
 */
trait VtepData {

    /** Return the VTEP tunnel IP */
    def vxlanTunnelIp: Future[Option[IPv4Addr]]

    /** The Observable that emits updates in the *cast_Mac_Local tables, with
      * MACs that are local to the VTEP and should be published to other
      * members of a VxLAN gateway. */
    def macLocalUpdates: Observable[MacLocation]

    /** The Observer to use in order to push updates about MACs that are local
      * to other VTEPs (which includes ports in MidoNet.  Entries pushed to this
      * Observer are expected to be applied in the Mac_Remote tables on the
      * hardware VTEPs. */
    def macRemoteUpdater: Future[Observer[MacLocation]]

    /** Provide a snapshot with the current contents of the Mac_Local tables
      * in the VTEP's OVSDB. */
    def currentMacLocal: Future[Seq[MacLocation]]

    /** Ensure that the hardware VTEP's config contains a Logical Switch with
      * the given name and VNI. */
    def ensureLogicalSwitch(name: String, vni: Int): Future[LogicalSwitch]

    /** Remove the logical switch with the given name, as well as all bindings
      * and entries in Mac tables. */
    def removeLogicalSwitch(name: String): Future[Unit]

    /** Ensure that the hardware VTEP's config for the given Logical Switch
      * contains these and only these bindings. */
    def ensureBindings(lsName: String, bindings: Iterable[(String, Short)])
    : Future[Unit]

}


