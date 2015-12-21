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

import org.midonet.cluster.models.Topology.ServiceContainerGroup

/**
  * Builds the correct type of [[HostSelector]] given a service container group.
  */
class HostSelectorProvider(context: Context) {

    private lazy val anywhereHostSelector = new AnywhereHostSelector(context)

    /**
      * Returns a host selector for the specified service container group.
      */
    def selectorOf(group: ServiceContainerGroup): HostSelector = {
        // TODO: Currently we only support an anywhere host selector.
        anywhereHostSelector
    }

}
