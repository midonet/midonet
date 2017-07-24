/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster;

import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;

public interface TopologyBuilder {

    default Commons.UUID toProto(java.util.UUID uuid) {
        return Commons.UUID.newBuilder()
            .setMsb(uuid.getMostSignificantBits())
            .setLsb(uuid.getLeastSignificantBits()).build();
    }

    default Topology.Host createHost(java.util.UUID id) {
        return Topology.Host.newBuilder()
            .setId(toProto(id))
            .build();
    }

    default Topology.Network createNetwork(java.util.UUID id) {
        return Topology.Network.newBuilder()
            .setId(toProto(id))
            .build();
    }

}
