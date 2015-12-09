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

import org.midonet.cluster.models.Topology.ServiceContainerGroup
import org.midonet.cluster.util.UUIDUtil._

object HostSelector {

    /**
      * Specifies the host selection policy.
      */
    trait Policy
    case object AnywherePolicy extends Policy
    case class HostGroupPolicy(hostGroupId: UUID) extends Policy
    case class PortGroupPolicy(portGroupId: UUID) extends Policy

    /** Returns the host selection policy for the given service container group.
      * The policy rule is: host group, if defined, takes precedence over port
      * group. Otherwise, the policy is anywhere.
      */
    def policyOf(group: ServiceContainerGroup): Policy = {
        if (group.hasHostGroupId) HostGroupPolicy(group.getHostGroupId)
        else if (group.hasPortGroupId) PortGroupPolicy(group.getPortGroupId)
        else AnywherePolicy
    }

}

/**
  * Interface for a host selector, which is an object state that emits
  * [[HostsEvent]] notifications with the set of hosts that are currently
  * running the container service.
  */
trait HostSelector extends ObjectTracker[HostsEvent]
