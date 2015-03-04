/*
 * Copyright 2014 Midokura SARL
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

import org.midonet.packets.IPv4Addr

import scala.concurrent.Future

/**
 * A client class for the connection to a VTEP-enabled switch. A client
 * instance allows multiple users to share the same connection to a VTEP,
 * while monitoring the connection for possible failure and including a
 * recovery mechanism.
 */
trait VtepConnection {

    /** @return The VTEP management IP */
    def getManagementIp: IPv4Addr

    /** @return The VTEP management port */
    def getManagementPort: Int

    /**
     * Connect to the VTEP using a specific user. If the VTEP is already
     * connected or connecting, it does nothing; if the connection state
     * is disconnecting or disposed, the method throws an exception.
     * @param user The user asking for the connection
     */
    def connect(user: UUID): Future[Unit]

    /**
     * Disconnects from the VTEP. If the VTEP is already disconnecting
     * or disconnected, it does nothing; if the connection state is
     * connecting, the method throws an exception. The method returns
     * a future true if the vtep is actually disconnected, or a future
     * false if the connection is kept open because of additional clients
     * @param user The user that previously asked for this connection
     */
    def disconnect(user: UUID): Future[Boolean]

    /**
     * Get the current connection state
     */
    def getState: VtepConnection.State.Value

}

object VtepConnection {
    /** VTEP connection states */
    object State extends Enumeration {
        type State = Value
        val DISCONNECTED, CONNECTING, CONNECTED, BROKEN, DISPOSED = Value
    }
}

