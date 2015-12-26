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

package org.midonet.cluster.services.containers

import java.util.UUID
import java.util.concurrent.ExecutorService

import javax.annotation.Nullable

import com.google.common.base.MoreObjects
import com.typesafe.scalalogging.Logger

import rx.Scheduler

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.State.{ContainerStatus, ContainerServiceStatus}
import org.midonet.cluster.models.Topology.{ServiceContainer, ServiceContainerGroup}

package object schedulers {

    /**
      * Wraps context variables such as the backend storage, executor and
      * logger.
      */
    case class Context(store: Storage,
                       stateStore: StateStorage,
                       executor: ExecutorService,
                       scheduler: Scheduler,
                       log: Logger)

    trait SchedulerEvent
    case class ScheduleEvent(container: ServiceContainer,
                             hostId: UUID) extends SchedulerEvent
    case class UpEvent(container: ServiceContainer,
                       status: ContainerStatus) extends SchedulerEvent
    case class DownEvent(container: ServiceContainer,
                         @Nullable status: ContainerStatus) extends SchedulerEvent
    case class UnscheduleEvent(container: ServiceContainer,
                               host: UUID) extends SchedulerEvent

    type HostsEvent = Map[UUID, HostEvent]

    type PortsEvent = Map[UUID, PortEvent]

    case class HostEvent(running: Boolean,
                         status: ContainerServiceStatus =
                            ContainerServiceStatus.getDefaultInstance) {
        override val toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add("running", running)
            .add("weight", status.getWeight)
            .toString
    }

    case class PortEvent(hostId: UUID, interfaceName: String, active: Boolean) {
        override val toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add("hostId", hostId)
            .add("interfaceName", interfaceName)
            .add("active", active)
            .toString
    }

    case class HostGroupEvent(hostGroupId: UUID, hosts: HostsEvent) {
        override val toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add("hostGroupId", hostGroupId)
            .add("hosts", hosts)
            .toString
    }

}
