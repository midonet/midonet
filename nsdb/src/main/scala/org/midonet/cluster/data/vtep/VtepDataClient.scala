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

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import scala.util.Try

import rx.{Observable, Observer}

import org.midonet.cluster.data.vtep.model._
import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.makeAction1
import org.midonet.util.functors.makeFunc1

/**
 * A client class for the connection to a VTEP-enabled switch. A client
 * instance allows multiple users to share the same connection to a VTEP,
 * while monitoring the connection for possible failure and including a
 * recovery mechanism.
 */
trait VtepDataClient extends VtepConnection with VtepData

/**
 * Prevent java confusions between traits, classes and interfaces
 */
abstract class VtepDataClientClass extends VtepDataClient

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
     * Close all connections and set to a disposed state
     */
    def dispose(): Unit

    /**
     * Get a observable to get the current state and monitor connection changes
     */
    def observable: Observable[VtepConnection.State.Value]

    /**
     * Get the current connection state
     */
    def getState: VtepConnection.State.Value

    /**
     * Get the connection handle
     */
    def getHandle: Option[VtepConnection.VtepHandle]

    /**
     * Wait for a specific state
     */
    def awaitState(expected: Set[VtepConnection.State.Value],
                   timeout: Duration = Duration.Inf)
    : VtepConnection.State.Value = {
        val f = observable
            .takeFirst(makeFunc1 {expected.contains})
            .toBlocking.toFuture
        if (timeout.isFinite()) f.get(timeout.toMillis, TimeUnit.MILLISECONDS)
        else f.get()
    }

    /**
     * Wait for a ready state, return false if that is impossible
     */
    def awaitReady(): Boolean =
        awaitState(Set(VtepConnection.State.READY,
                       VtepConnection.State.DISPOSED)) == VtepConnection.State.READY

    /**
     * Wait for a disconnected state (Disconnected or Disposed)
     */
    def awaitDisconnected(): VtepConnection.State.Value =
        awaitState(Set(VtepConnection.State.DISCONNECTED,
                       VtepConnection.State.DISPOSED))
}

// Java compatibility
abstract class VtepConnectionClass extends VtepConnection

object VtepConnection {
    /** VTEP connection states */
    object State extends Enumeration {
        type State = Value
        val DISCONNECTED, CONNECTED, READY,
            DISCONNECTING, BROKEN, CONNECTING,
            DISPOSED = Value
    }
    /** A connection handle to be used to perform operations on the vtep */
    abstract class VtepHandle
}

/**
 * Access data from a VTEP
 */
trait VtepData {
    /** Return the VTEP tunnel IP */
    def vxlanTunnelIp: Option[IPv4Addr]

    /** The Observable that emits updates in the *cast_Mac_Local tables, with
      * MACs that are local to the VTEP and should be published to other
      * members of a VxLAN gateway. */
    def macLocalUpdates: Observable[MacLocation]

    /** The Observer to use in order to push updates about MACs that are local
      * to other VTEPs (which includes ports in MidoNet.  Entries pushed to this
      * Observer are expected to be applied in the Mac_Remote tables on the
      * hardware VTEPs. */
    def macRemoteUpdater: Observer[MacLocation]

    /** Provide a snapshot with the current contents of the Mac_Local tables
      * in the VTEP's OVSDB. */
    def currentMacLocal: Seq[MacLocation]
    def currentMacLocal(networkId: UUID): Seq[MacLocation]

    /** Ensure that the hardware VTEP's config contains a Logical Switch
      * corresponding to the given network Id and VNI. */
    def ensureLogicalSwitch(networkId: UUID, vni: Int): Try[LogicalSwitch]

    /** Remove the logical switch corresponding to the given network,
      * as well as all bindings and entries in Mac tables. */
    def removeLogicalSwitch(networkId: UUID): Try[Unit]

    /** Ensure that the hardware VTEP's config for the Logical Switch
      * corresponding to the given networkId contains these and only
      * these bindings. */
    def ensureBindings(networkId: UUID, bindings: Iterable[VtepBinding]): Try[Unit]

    /** Remove the binding corresponding to the given port and Vlan Id */
    def removeBinding(portName: String, vlanId: Short): Try[Unit]

    /** Create binding */
    def createBinding(portName: String, vlanId: Short, networkId: UUID): Try[Unit]

    /** Get the current set of logical switches */
    def listLogicalSwitches: Set[LogicalSwitch]

    /** Get the current set of physical switches */
    def listPhysicalSwitches: Set[PhysicalSwitch]

    /** Get the physical ports of a physical switch */
    def physicalPorts(psId: UUID): Set[PhysicalPort]
}


