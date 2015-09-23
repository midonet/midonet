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

package org.midonet.cluster.state

import java.util.UUID

import rx.Observable

import org.midonet.cluster.data.storage.{StateResult, StateStorage}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend.ActiveKey

object PortStateStorage {

    implicit def asPort(store: StateStorage): PortStateStorage = {
        new PortStateStorage(store)
    }

}

/**
 * A wrapper class around the [[StateStorage]] with utility methods for changing
 * the state information of a port.
 */
class PortStateStorage(val store: StateStorage) extends AnyVal {

    /**
     * Sets the port as active or inactive at the given host.
     */
    def setPortActive(portId: UUID, hostId: UUID, active: Boolean)
    : Observable[StateResult] = {
        if (active) {
            store.addValue(classOf[Port], portId, ActiveKey, hostId.toString)
        } else {
            store.removeValue(classOf[Port], portId, ActiveKey, hostId.toString)
        }
    }

}
