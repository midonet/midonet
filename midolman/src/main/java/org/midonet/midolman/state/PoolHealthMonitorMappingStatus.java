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

package org.midonet.midolman.state;

import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.models.Topology;

/**
 * Status property used in MidoNet resources. They mirror statuses defined in
 * Neutron.
 */
@ZoomEnum(clazz = Topology.Pool.PoolHealthMonitorMappingStatus.class)
public enum PoolHealthMonitorMappingStatus {
    @ZoomEnumValue("ACTIVE") ACTIVE,
    @ZoomEnumValue("INACTIVE") INACTIVE,
    @ZoomEnumValue("PENDING_CREATE") PENDING_CREATE,
    @ZoomEnumValue("PENDING_UPDATE") PENDING_UPDATE,
    @ZoomEnumValue("PENDING_DELETE") PENDING_DELETE,
    @ZoomEnumValue("ERROR") ERROR;

    public static PoolHealthMonitorMappingStatus fromProto(
            Topology.Pool.PoolHealthMonitorMappingStatus proto) {
        return PoolHealthMonitorMappingStatus.valueOf(proto.toString());
    }

    public Topology.Pool.PoolHealthMonitorMappingStatus toProto() {
        return Topology.Pool.PoolHealthMonitorMappingStatus.valueOf(toString());
    }
}
