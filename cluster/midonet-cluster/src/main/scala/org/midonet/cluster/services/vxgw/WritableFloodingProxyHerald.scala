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

package org.midonet.cluster.services.vxgw

import java.util.UUID
import java.util.concurrent.Executor

import rx.Observable

import org.midonet.cluster.data.storage.StateResult
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.util.reactivex

/**
 * This class is responsible for keeping a register of the current flooding
 * proxy for each tunnel zone, and announcing it via the NSDB.
 */
class WritableFloodingProxyHerald(backend: MidonetBackend, executor: Executor)
    extends FloodingProxyHerald(backend, executor) {

    /** Asynchronously publish the given hostId as Flooding Proxy for the given
      * tunnel zone.
      */
    private[vxgw] def announce(tzId: UUID, hostId: UUID,
                               onError: Throwable => Unit): Unit = (
        if (hostId == null) announceRemoval(tzId, hostId)
        else announceAddition(tzId, hostId)
    ).subscribe(reactivex.retryObserver[StateResult](onError))

    /** Asynchronously publishes a removal of the flooding proxy, emitting the
      * result on the observable when the value is safely stored in the NSDB.
      * If the write fails, the Observable will emit an onError.
      */
    private def announceRemoval(tzId: UUID, hostId: UUID)
    : Observable[StateResult] = {
        backend.stateStore
               .removeValue(classOf[TunnelZone], tzId, TunnelZoneKey, null)
    }

    /** Asynchronously publishes a new flooding proxy, emitting the result on
      * the observable when the value is safely stored in the NSDB.  If the
      * write fails, the Observable will emit an onError.
      */
    private def announceAddition(tzId: UUID, hId: UUID)
    : Observable[StateResult] = {
        backend.stateStore
               .addValue(classOf[TunnelZone], tzId, TunnelZoneKey, hId.toString)
    }
}
