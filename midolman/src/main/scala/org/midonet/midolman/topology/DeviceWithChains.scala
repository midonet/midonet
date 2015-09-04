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

import java.util.{List, UUID}
import scala.collection.JavaConverters._
import org.midonet.midolman.topology.VirtualTopologyActor.DeviceRequest

trait DeviceWithChains extends TopologyPrefetcher {

    protected def cfg: { def inboundFilters: List[UUID]
                         def outboundFilters: List[UUID] }

    override def prefetchTopology(requests: DeviceRequest*) {
        super.prefetchTopology(
            requests ++
                cfg.inboundFilters.asScala.map(chain(_)) ++
                cfg.outboundFilters.asScala.map(chain(_)): _*)
    }
}
