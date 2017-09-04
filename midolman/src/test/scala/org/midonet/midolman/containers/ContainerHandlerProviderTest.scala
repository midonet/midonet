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

import java.util.UUID
import java.util.concurrent.{ExecutorService, ScheduledExecutorService}

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.inject.name.Named
import com.google.inject.{ConfigurationException, Inject}

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.services.MidonetBackend
import org.midonet.containers._
import org.midonet.midolman.containers.ContainerHandlerProviderTest.TestContainer
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.util.logging.Logger

object ContainerHandlerProviderTest {

    @Container(name = "test-handler", version = 1)
    class TestContainer @Inject()(@Named("id") val id: UUID,
                                  val vt: VirtualTopology,
                                  val backend: MidonetBackend,
                                  @Named("container") val containerExecutor: ExecutorService,
                                  @Named("io") val ioExecutor: ScheduledExecutorService)
        extends ContainerHandler {
        override def create(port: ContainerPort): Future[Option[String]] = ???
        override def updated(port: ContainerPort): Future[Option[String]] = ???
        override def delete(): Future[Unit] = ???
        override def cleanup(config: String): Future[Unit] = ???
        override def status: Observable[ContainerStatus] = ???
    }

}

@RunWith(classOf[JUnitRunner])
class ContainerHandlerProviderTest extends FlatSpec with Matchers
                                   with GivenWhenThen {

    private val log = Logger(LoggerFactory.getLogger(getClass))
    private val reflections: Set[Class[_]] =
        new Reflections("org.midonet.midolman.containers")
            .getTypesAnnotatedWith(classOf[Container]).asScala.toSet

    "Container provider" should "load a container with the VT as argument" in {
        Given("A mock virtual topology")
        val backend = Mockito.mock(classOf[MidonetBackend])
        val vt = Mockito.mock(classOf[VirtualTopology])
        val executor = Mockito.mock(classOf[ScheduledExecutorService])
        Mockito.when(vt.backend).thenReturn(backend)

        And("A provider for the current class path")
        val provider = new ContainerHandlerProvider(reflections, vt, executor,
                                                    log)

        Then("The provider should load all classes")
        provider.current.size should be >= 1

        And("The provider should return a handler instance")
        val id = UUID.randomUUID()
        val container = provider.getInstance("test-handler", id, executor)
                                .asInstanceOf[TestContainer]
        container should not be null
        container.id shouldBe id
        container.vt shouldBe vt
        container.backend shouldBe vt.backend
        container.containerExecutor shouldBe executor
        container.ioExecutor shouldBe executor
    }

    "Container provider" should "fail to create container without identifier" in {
        Given("A mock virtual topology")
        val backend = Mockito.mock(classOf[MidonetBackend])
        val vt = Mockito.mock(classOf[VirtualTopology])
        val executor = Mockito.mock(classOf[ScheduledExecutorService])
        Mockito.when(vt.backend).thenReturn(backend)

        And("A provider for the current class path")
        val provider = new ContainerHandlerProvider(reflections, vt, executor,
                                                    log)

        Then("The provider should load all classes")
        provider.current.size should be >= 1

        And("The provider should fail to create a handler instance")
        intercept[ConfigurationException] {
            provider.getInstance("test-handler")
        }
    }
}
