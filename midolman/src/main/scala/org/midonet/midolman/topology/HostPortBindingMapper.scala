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

package org.midonet.midolman.topology

import java.util.UUID

import rx.Observable

import org.midonet.cluster.models.Neutron.{HostPortBinding => TopologyHostPortBinding}
import org.midonet.midolman.simulation.{HostPortBinding => SimulationHostPortBinding}
import org.midonet.util.functors.{makeAction0, makeAction1, makeFunc1}
import org.midonet.cluster.util.UUIDUtil.fromProto

/**
  * A device mapper that exposes an [[rx.Observable]] with notifications for a
  * host port binding.
  *
  * @param id the host port binding ID (mix of host ID and port ID)
  * @param vt the virtual topology object where observe on
  */
class HostPortBindingMapper(id: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper(classOf[SimulationHostPortBinding], id, vt) {

    lazy val observable: Observable[SimulationHostPortBinding] =
        try {
            vt.store.observable(classOf[TopologyHostPortBinding], id)
                .observeOn(vt.vtScheduler)
                .map[SimulationHostPortBinding](makeFunc1(buildHostPortBinding))
                .distinctUntilChanged()
        } finally {
            Observable.empty()
        }

    private def buildHostPortBinding(input: TopologyHostPortBinding) = {
        new SimulationHostPortBinding(input.getId, input.getInterfaceName)
    }
}
