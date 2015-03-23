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

import org.midonet.midolman.topology.devices.PoolHealthMonitorMap

/**
 * A device mapper that exposes an observable with change notifications
 * for mappings between pools and their associated pool health monitors.
 */
class PoolHealthMonitorMapper(id: UUID, vt: VirtualTopology)
    // TODO: the id does not seem to make much sense here...
    extends DeviceMapper[PoolHealthMonitorMap](id, vt) {

    // The output device observable for the pool health monitor mapper
    protected override lazy val observable: Observable[PoolHealthMonitorMap] = ???
}
