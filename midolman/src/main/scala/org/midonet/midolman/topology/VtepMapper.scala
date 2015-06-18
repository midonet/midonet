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

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.OverlayVtep
import org.midonet.midolman.simulation.Vtep
import org.midonet.util.functors._

sealed class VtepMapper(id: UUID, vt: VirtualTopology)
    extends VirtualDeviceMapper[Vtep](id, vt) {

    override def logSource = s"org.midonet.midolman.topology.vtep-$id"

    protected override def observable = {
        vt.store.observable(classOf[OverlayVtep], id)
            .map[Vtep](
                makeFunc1(ZoomConvert.fromProto(_, classOf[Vtep])))
    }
}
