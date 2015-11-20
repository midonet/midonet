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

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.{GivenWhenThen, Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{ServiceContainerGroup, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.containers.ContainerDelegateProviderTest.TestContainer
import org.midonet.containers.Container

object ContainerDelegateProviderTest {

    @Container(name = "test-delegate", version = 1)
    class TestContainer @Inject()(backend: MidonetBackend,
                                  config: ClusterConfig) extends ContainerDelegate {
        def onCreate(container: ServiceContainer, group: ServiceContainerGroup,
                     hostId: UUID): Unit = { }
        def onUp(container: ServiceContainer, group: ServiceContainerGroup,
                 status: ContainerStatus): Unit = { }
        def onDown(container: ServiceContainer, group: ServiceContainerGroup,
                   status: ContainerStatus): Unit = { }
        def onDelete(container: ServiceContainer, group: ServiceContainerGroup,
                     hostId: UUID): Unit = { }
    }

}

@RunWith(classOf[JUnitRunner])
class ContainerDelegateProviderTest extends FlatSpec with Matchers
                                    with GivenWhenThen {

    private val log = Logger(LoggerFactory.getLogger(getClass))

    "Container provider" should "load a container with the VT as argument" in {
        Given("A mock cluster configuration and backend")
        val backend = Mockito.mock(classOf[MidonetBackend])
        val config = Mockito.mock(classOf[ClusterConfig])

        And("A provider for the current class path")
        val provider = new ContainerDelegateProvider(backend, config, log)

        Then("The provider should load all classes")
        provider.current.size should be >= 1

        And("The loaded classes should have the last version")
        provider.getInstance("test-delegate").getClass shouldBe classOf[TestContainer]
    }

}
