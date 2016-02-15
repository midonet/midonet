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

package org.midonet.midolman.containers

import java.util.concurrent.{ExecutorService, ScheduledExecutorService}

import scala.concurrent.Future

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.containers.Container
import org.midonet.midolman.containers.ContainerHandlerProviderTest.TestContainer
import org.midonet.midolman.topology.VirtualTopology

object ContainerHandlerProviderTest {

    @Container(name = "test-handler", version = 1)
    class TestContainer @Inject()(val vt: VirtualTopology,
                                  @Named("container") val containerExecutor: ExecutorService,
                                  @Named("io") val ioExecutor: ScheduledExecutorService)
        extends ContainerHandler {
        override def create(port: ContainerPort): Future[Option[ContainerResult]] = ???
        override def updated(port: ContainerPort): Future[Option[ContainerResult]] = ???
        override def delete(): Future[Option[ContainerResult]] = ???
        override def cleanup(config: String): Future[Unit] = ???
        override def health: Observable[ContainerHealth] = ???
    }

}

@RunWith(classOf[JUnitRunner])
class ContainerHandlerProviderTest extends FlatSpec with Matchers
                                   with GivenWhenThen {

    private val log = Logger(LoggerFactory.getLogger(getClass))
    private val reflections = new Reflections("org.midonet.midolman.containers")

    "Container provider" should "load a container with the VT as argument" in {
        Given("A mock virtual topology")
        val vt = Mockito.mock(classOf[VirtualTopology])
        val executor = Mockito.mock(classOf[ScheduledExecutorService])

        And("A provider for the current class path")
        val provider = new ContainerHandlerProvider(reflections, vt, executor,
                                                    executor, log)

        Then("The provider should load all classes")
        provider.current.size should be >= 1

        And("The provider should return a handler instance")
        val container = provider.getInstance("test-handler").asInstanceOf[TestContainer]
        container should not be null
        container.vt shouldBe vt
        container.containerExecutor shouldBe executor
        container.ioExecutor shouldBe executor
    }

}
