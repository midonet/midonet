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

import javax.annotation.Nullable

import com.google.common.base.MoreObjects

import org.midonet.cluster.models.State.{ContainerStatus, ContainerServiceStatus}
import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.cluster.util.UUIDUtil._

package object schedulers {

    private final val ContainerIdField = "containerId"
    private final val ContainerTypeField = "containerType"

    trait SchedulerEvent { def container: ServiceContainer }
    case class Schedule(container: ServiceContainer,
                        hostId: UUID) extends SchedulerEvent {
        override def toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add(ContainerIdField, container.getId.asJava)
            .add(ContainerTypeField, container.getServiceType)
            .toString
    }
    case class Up(container: ServiceContainer,
                  status: ContainerStatus) extends SchedulerEvent {
        override def toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add(ContainerIdField, container.getId.asJava)
            .add(ContainerTypeField, container.getServiceType)
            .toString
    }
    case class Down(container: ServiceContainer,
                    @Nullable status: ContainerStatus) extends SchedulerEvent {
        override def toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add(ContainerIdField, container.getId.asJava)
            .add(ContainerTypeField, container.getServiceType)
            .toString
    }
    case class Unschedule(container: ServiceContainer,
                          host: UUID) extends SchedulerEvent {
        override def toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add(ContainerIdField, container.getId.asJava)
            .add(ContainerTypeField, container.getServiceType)
            .toString
    }

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

    case class PortGroupEvent(portGroupId: UUID, hosts: HostsEvent) {
        override val toString = MoreObjects.toStringHelper(this).omitNullValues()
            .add("portGroupId", portGroupId)
            .add("hosts", hosts)
            .toString
    }
}
