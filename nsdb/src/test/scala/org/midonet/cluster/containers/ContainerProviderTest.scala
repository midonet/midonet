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

package org.midonet.cluster.containers

import java.util.UUID

import javax.inject.Named

import scala.reflect.classTag

import com.google.inject.{Guice, Inject}
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.containers.ContainerProviderTest.{ContainerA2, ContainerB}
import org.midonet.containers.{Container, ContainerProvider}

object ContainerProviderTest {

    @Container(name = "test-a", version = 1)
    class ContainerA1

    @Container(name = "test-a", version = 2)
    class ContainerA2

    @Container(name = "test-b", version = 1)
    class ContainerB

    @Container(name = "test-c", version = 1)
    class ContainerC @Inject()(@Named("id") val id: UUID)

}

@RunWith(classOf[JUnitRunner])
class ContainerProviderTest extends FlatSpec with Matchers
                                           with GivenWhenThen {

    val reflections = new Reflections("org.midonet.cluster.containers")

    private class TestContainerProvider(log: Logger)
        extends ContainerProvider(reflections, log)(classTag[AnyRef]) {

        protected override val injector = Guice.createInjector()

    }

    private val log = Logger(LoggerFactory.getLogger(getClass))

    "Container provider" should "load all containers" in {
        Given("A provider for the current class path")
        val provider = new TestContainerProvider(log)

        Then("The provider should load all classes")
        provider.current.size should be >= 3
        provider.all.size should be >= 4

        And("The loaded classes should have the last version")
        provider.getInstance("test-a").getClass shouldBe classOf[ContainerA2]
        provider.getInstance("test-b").getClass shouldBe classOf[ContainerB]

        And("The provider fails on non-existing classes")
        intercept[NoSuchElementException] {
            provider.getInstance("test-none")
        }
    }

}

