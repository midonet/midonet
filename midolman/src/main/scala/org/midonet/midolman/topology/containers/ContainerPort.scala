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

package org.midonet.midolman.topology.containers

import java.util.UUID

import com.google.common.base.MoreObjects

/**
  * Specifies the configuration of a port bound to a service container.
  */
case class ContainerPort(portId: UUID,
                         hostId: UUID,
                         interfaceName: String,
                         containerId: UUID,
                         configurationId: UUID) {
    override val toString =
        MoreObjects.toStringHelper(this).omitNullValues()
            .add("portId", portId)
            .add("hostId", hostId)
            .add("interfaceName", interfaceName)
            .add("containerId", containerId)
            .add("configurationId", configurationId)
            .toString
}