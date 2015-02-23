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
package org.midonet.midolman.topology

import java.util.UUID

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.midolman.topology.devices.{TunnelZone => SimTunnelZone}
import org.midonet.util.functors.makeFunc1

/**
 * This mapper offers an observable of a tunnel zone simulation object of a given
 * id. The tunnel zone is obtained from Zoom as a protocol buffer
 * and converted into the corresponding simulation object using ZoomConvert.
 */
final class TunnelZoneMapper(id: UUID, vt: VirtualTopology)
    extends DeviceMapper[SimTunnelZone](id, vt) {

    override def logSource = s"org.midonet.devices.tunnelzone.tunnelzone-$id"

    protected override val observable =
        vt.store.observable(classOf[TunnelZone], id)
            .map[SimTunnelZone](
                makeFunc1(ZoomConvert.fromProto(_, classOf[SimTunnelZone])))
            .observeOn(vt.vtScheduler)
            .distinctUntilChanged()

}
