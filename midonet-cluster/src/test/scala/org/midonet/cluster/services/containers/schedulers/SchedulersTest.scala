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

import scala.collection.JavaConverters._
import scala.util.Random

import com.typesafe.scalalogging.Logger

import org.scalatest.{Matchers, BeforeAndAfter, Suite}
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.State.{ContainerStatus, ContainerServiceStatus}
import org.midonet.cluster.models.Topology.{ServiceContainer, ServiceContainerGroup, Host, HostGroup}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.schedulers.ContainerScheduler.{Up, Scheduled, State}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex._

trait SchedulersTest extends Suite with BeforeAndAfter {

    protected var store: InMemoryStorage = _
    protected var context: Context = _
    protected val random = new Random()

    protected class SchedulerEventWrapper(event: SchedulerEvent)
        extends Matchers {
        def shouldBeScheduleFor(container: ServiceContainer,
                                group: ServiceContainerGroup,
                                hostId: UUID): Unit = {
            event match {
                case ScheduleEvent(c, g, h) =>
                    c shouldBe container
                    g.getId shouldBe group.getId
                    g.getPortGroupId shouldBe group.getPortGroupId
                    g.getHostGroupId shouldBe group.getHostGroupId
                    h shouldBe hostId
                case _ => fail()
            }
        }

        def shouldBeUnscheduleFor(container: ServiceContainer,
                                group: ServiceContainerGroup,
                                hostId: UUID): Unit = {
            event match {
                case UnscheduleEvent(c, g, h) =>
                    c shouldBe container
                    g.getId shouldBe group.getId
                    g.getPortGroupId shouldBe group.getPortGroupId
                    g.getHostGroupId shouldBe group.getHostGroupId
                    h shouldBe hostId
                case _ => fail()
            }
        }

        def shouldBeUpFor(container: ServiceContainer,
                          group: ServiceContainerGroup,
                          hostId: UUID): Unit = {
            event match {
                case UpEvent(c, g, s) =>
                    c shouldBe container
                    g.getId shouldBe group.getId
                    g.getPortGroupId shouldBe group.getPortGroupId
                    g.getHostGroupId shouldBe group.getHostGroupId
                    s.getHostId.asJava shouldBe hostId
                case _ => fail()
            }
        }

        def shouldBeDownFor(container: ServiceContainer,
                            group: ServiceContainerGroup,
                            hostId: UUID): Unit = {
            event match {
                case DownEvent(c, g, s) =>
                    c shouldBe container
                    g.getId shouldBe group.getId
                    g.getPortGroupId shouldBe group.getPortGroupId
                    g.getHostGroupId shouldBe group.getHostGroupId
                    if (s ne null) s.getHostId.asJava shouldBe hostId
                case _ => fail()
            }
        }
    }

    protected class StateWrapper(state: State) extends Matchers {
        def shouldBeScheduledFor(container: ServiceContainer,
                                 group: ServiceContainerGroup,
                                 hostId: UUID,
                                 isUnsubcribed: Boolean = false): Unit = {
            state match {
                case Scheduled(h, c, g, s) =>
                    c shouldBe container
                    g.getId shouldBe group.getId
                    g.getPortGroupId shouldBe group.getPortGroupId
                    g.getHostGroupId shouldBe group.getHostGroupId
                    h shouldBe hostId
                    s.isUnsubscribed shouldBe isUnsubcribed
                case _ => fail()
            }
        }

        def shouldBeUpFor(container: ServiceContainer,
                          group: ServiceContainerGroup,
                          hostId: UUID): Unit = {
            state match {
                case Up(h, c, g) =>
                    c shouldBe container
                    g.getId shouldBe group.getId
                    g.getPortGroupId shouldBe group.getPortGroupId
                    g.getHostGroupId shouldBe group.getHostGroupId
                    h shouldBe hostId
                case _ => fail()
            }
        }
    }

    before {
        val executor = new SameThreadButAfterExecutorService
        val log = Logger(LoggerFactory.getLogger("containers"))
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, store)
        context = Context(store, store, executor, Schedulers.from(executor), log)
        beforeTest()
    }

    protected implicit def asWrapper(event: SchedulerEvent): SchedulerEventWrapper = {
        new SchedulerEventWrapper(event)
    }

    protected implicit def asWrapper(state: State): StateWrapper = {
        new StateWrapper(state)
    }

    protected def beforeTest(): Unit = { }

    protected def createHost(): Host = {
        val host = Host.newBuilder()
            .setId(randomUuidProto)
            .build()
        store create host
        host
    }

    protected def createHostGroup(hostIds: UUID*): HostGroup = {
        val hostGroup = HostGroup.newBuilder()
            .setId(randomUuidProto)
            .addAllHostIds(hostIds.map(_.asProto).asJava)
            .build()
        store create hostGroup
        hostGroup
    }

    protected def createHostStatus(hostId: UUID,
                                   weight: Int = random.nextInt())
    : ContainerServiceStatus = {
        val status = ContainerServiceStatus.newBuilder()
            .setWeight(weight)
            .build()
        store.addValueAs(hostId.toString, classOf[Host], hostId,
                         ContainerKey, status.toString).await()
        status
    }

    protected def deleteHostStatus(hostId: UUID): Unit = {
        store.removeValueAs(hostId.toString, classOf[Host], hostId,
                            ContainerKey, null).await()
    }

    protected def createGroup(): ServiceContainerGroup = {
        val group = ServiceContainerGroup.newBuilder()
            .setId(randomUuidProto)
            .build()
        store.create(group)
        group
    }

    protected def createContainer(groupId: UUID): ServiceContainer = {
        val container = ServiceContainer.newBuilder()
            .setId(randomUuidProto)
            .setServiceGroupId(groupId.asProto)
            .build()
        store.create(container)
        container
    }

    protected def createContainerStatus(containerId: UUID,
                                        code: Code,
                                        hostId: UUID): ContainerStatus = {
        val status = ContainerStatus.newBuilder()
                                    .setStatusCode(code)
                                    .setHostId(hostId)
                                    .build()
        store.addValueAs(hostId.toString, classOf[ServiceContainer], containerId,
                         StatusKey, status.toString).await()
        status
    }

    protected def deleteContainerStatus(containerId: UUID, hostId: UUID): Unit = {
        store.removeValueAs(hostId.toString, classOf[ServiceContainer],
                            containerId, StatusKey, null).await()
    }

}
