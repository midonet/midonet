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

import java.util.NoSuchElementException

import scala.concurrent.Future

import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.containers.Container
import org.midonet.midolman.containers.ContainerHandlerProviderTest.{ContainerB, ContainerA2}
import org.midonet.midolman.topology.VirtualTopology

object ContainerHandlerProviderTest {

    class TestContainer extends ContainerHandler {
        override def create(port: ContainerPort): Future[String] = ???
        override def updated(port: ContainerPort): Future[Unit] = ???
        override def delete(): Future[Unit] = ???
        override def health: Observable[ContainerStatus.Code] = ???
    }

    @Container(name = "test-a", version = 1)
    class ContainerA1 @Inject() extends TestContainer

    @Container(name = "test-a", version = 2)
    class ContainerA2 extends TestContainer

    @Container(name = "test-b", version = 1)
    class ContainerB extends TestContainer

}

@RunWith(classOf[JUnitRunner])
class ContainerHandlerProviderTest extends FlatSpec with Matchers
                                   with GivenWhenThen {

    private val log = Logger(LoggerFactory.getLogger(getClass))

    "Container provider" should "load all containers" in {
        Given("A mock cluster configuration and backend")
        val vt = Mockito.mock(classOf[VirtualTopology])

        And("A provider for the current class path")
        val provider = new ContainerHandlerProvider(
            "org.midonet.midolman.containers", vt, log)

        Then("The provider should load all classes")
        provider.current.size should be >= 2

        And("The loaded classes should have the last version")
        provider.getInstance("test-a").getClass shouldBe classOf[ContainerA2]
        provider.getInstance("test-b").getClass shouldBe classOf[ContainerB]

        And("The provider fails on non-existing classes")
        intercept[NoSuchElementException] {
            provider.getInstance("test-none")
        }
    }

}
