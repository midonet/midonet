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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import com.google.inject.Inject
import org.midonet.cluster.data.storage.{StateKey, StateStorage, Storage}
import org.midonet.cluster.models.State.HostState
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.concurrent.toFutureOps
import rx.Observable

class PortGroupScheduler @Inject()(store: Storage, stateStore: StateStorage)
        extends AbstractScheduler(store:Storage, stateStore: StateStorage) {

    case class StateHost(key: Observable[StateKey], host: Host)

    def doAllocationPolicyFilter(scg: ServiceContainerGroup): Boolean = {
        Option(scg.getPortGroupId).nonEmpty
    }

    // Return only alive hosts belonging to the container group
    protected def candidateHosts(scg: ServiceContainerGroup): List[UUID] = {
        val portIds = store.get(classOf[PortGroup], scg.getPortGroupId).await().getPortIdsList()

        val hostIds = store.getAll(classOf[Port], portIds.toArray()).await()
                           .map(p => p.getHostId)

        store.getAll(classOf[Host], hostIds.toArray)
             .await()
             .map(h => StateHost(stateStore.keyObservable(h.getId.toString,
                 classOf[HostState],
                 h.getId,
                 MidonetBackend.AliveKey), h))
             .filter(state => state.key.toBlocking.single.nonEmpty)
             .map(state => fromProto(state.host.getId))
             .toList
    }

    // TODO: add a portgroup observer (and individual port observers) to get updates on those
    // to keep track of membership changes
}
