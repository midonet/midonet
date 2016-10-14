/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.discovery

import java.net.URI
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class MidonetDiscoveryTest extends FeatureSpec with Matchers
                                   with CuratorTestFramework
                                   with MidonetEventually with GivenWhenThen {

    private val timeout = Duration(5, TimeUnit.SECONDS)
    private val executor = new SameThreadButAfterExecutorService

    feature("discovery basics") {
        scenario("instantiation") {
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)
            discovery shouldNot be (null)
        }
    }

    feature("server") {
        scenario("server registration - server and port") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)
            When("Registering the service")
            val handler = discovery.registerServiceInstance(
                "testService", "10.0.0.1", 1234)
            Then("It's handler should contain a non-null instance")
            handler.asInstanceOf[MidonetServiceHandlerImpl].instance shouldNot be (null)
            handler.unregister()
            discovery.stop()
        }

        scenario("server registration - uri") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)
            When("Registering the service")
            val handler = discovery.registerServiceInstance(
                "testService", new URI("udp://10.0.0.1:1234"))
            Then("It's handler should contain a non-null instance")
            handler.asInstanceOf[MidonetServiceHandlerImpl].instance shouldNot be (null)
            handler.unregister()
            discovery.stop()
        }

        scenario("client handle - server and port") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)

            And("A service registered and a client of its type")
            val handler = discovery.registerServiceInstance(
                "testService", "10.0.0.1", 1234)
            val client = discovery.getClient[MidonetServiceHostAndPort]("testService")

            Then("the service is of the given type and matches the data")
            eventually {
                val service = client.instances.head
                service.address shouldBe "10.0.0.1"
                service.port shouldBe 1234
                client.instances should have size 1
            }

            handler.unregister()
            client.stop()
            discovery.stop()
        }

        scenario("client handle - uri") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)

            And("A service registered and a client of its type")
            val handler = discovery.registerServiceInstance(
                "testService", new URI("udp://10.0.0.1:1234/path/to/endpoint"))
            val client = discovery.getClient[MidonetServiceURI]("testService")

            Then("the service is of the given type and matches the data")
            eventually {
                val service = client.instances.head
                service.uri.getHost shouldBe "10.0.0.1"
                service.uri.getPort shouldBe 1234
                service.uri.getPath shouldBe "/path/to/endpoint"
                service.uri.getScheme shouldBe "udp"
                client.instances should have size 1
            }

            handler.unregister()
            discovery.stop()
            client.stop()
        }

        scenario("service and client from different types") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)

            And("two services of different types under the same name")
            val handler1 = discovery.registerServiceInstance(
                "testService", new URI("udp://10.0.0.1:1234"))
            val handler2 = discovery.registerServiceInstance(
                "testService", "10.0.0.2", 1234)

            And("Two clients of differents types for the same service name")
            val client1 = discovery.getClient[MidonetServiceHostAndPort]("testService")
            val client2 = discovery.getClient[MidonetServiceURI]("testService")

            Then("Querying each client just returns its type instances")
            eventually {
                client1.instances should have size 1
                client1.instances.head.address shouldBe "10.0.0.2"
                client1.instances.head.port shouldBe 1234
                client2.instances should have size 1
                client2.instances.head.uri.getHost shouldBe "10.0.0.1"
                client2.instances.head.uri.getPort shouldBe 1234
                client2.instances.head.uri.getScheme shouldBe "udp"
            }
            handler1.unregister()
            handler2.unregister()
            client1.stop()
            client2.stop()
            discovery.stop()
        }

        scenario("server disconnection - server and port") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)

            And("Two registered services and a client")
            val address1 = "10.0.0.1"
            val address2 = "20.0.0.2"
            val handler1 = discovery.registerServiceInstance("testService",
                                                             address1, 1234)
            val handler2 = discovery.registerServiceInstance("testService",
                                                             address2, 1234)
            val client = discovery.getClient[MidonetServiceHostAndPort]("testService")
            val log = Logger(LoggerFactory.getLogger("TEST"))
            var address: String = null

            Then("The two services are registered")
            eventually {
                client.instances should have length 2
                val service = client.instances.head
                address = service.address
            }

            if (address == address1) {
                When("Unregistering one of them")
                handler1.unregister()

                Then("The unregistered service is not visible to the client")
                eventually {
                    client.instances should have length 1
                    val service = client.instances.head
                    service.address shouldBe address2
                }

                When("Unregistering the other service")
                handler2.unregister()
            } else {
                handler2.unregister()
                eventually {
                    client.instances should have length 1
                    val service = client.instances.head
                    service.address shouldBe address1
                }
                handler1.unregister()
            }

            Then("No instance is returned by the client")
            eventually {
                client.instances should have length 0
                client.instances.isEmpty shouldBe true
            }

            discovery.stop()
            client.stop()
        }
    }

    feature("notifications") {
        scenario("service addition") {
            Given("A discovery service")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)

            And("A client and a observable")
            val monitor = new TestAwaitableObserver[Seq[MidonetServiceHostAndPort]]
            val client = discovery.getClient[MidonetServiceHostAndPort]("testService")
            client.observable.subscribe(monitor)

            Then("The first event is an empty list")
            monitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 0

            When("Registering one instance")
            val address1 = "10.0.0.1"
            val handler1 = discovery.registerServiceInstance("testService",
                                                             address1, 1234)

            Then("There's a new event with the registered service")
            monitor.awaitOnNext(2, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 1
            monitor.getOnNextEvents.last.get(0).address shouldBe address1

            When("Adding a second service")
            val address2 = "20.0.0.2"
            val handler2 = discovery.registerServiceInstance("testService",
                                                             address2, 1234)

            Then("A new event is emitted with both services")
            monitor.awaitOnNext(3, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 2
            monitor.getOnNextEvents.last.map(s => s.address)
                .toSet shouldBe Set(address1, address2)

            handler1.unregister()
            handler2.unregister()
            discovery.stop()
            client.stop()
        }

        scenario("service removal") {
            Given("A service discovery")
            val discovery = new MidonetDiscoveryImpl(curator, executor, config)

            And("Two services registered")
            val address1 = "10.0.0.1"
            val address2 = "20.0.0.2"
            val handler1 = discovery.registerServiceInstance("testService",
                                                             address1, 1234)
            val handler2 = discovery.registerServiceInstance("testService",
                                                             address2, 1234)

            And("An observable and a client")
            val monitor = new TestAwaitableObserver[Seq[MidonetServiceHostAndPort]]
            val client = discovery.getClient[MidonetServiceHostAndPort]("testService")
            client.observable.subscribe(monitor)

            Then("The first event has two elements")
            monitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.last.map(s => s.address)
                .toSet shouldBe Set(address1, address2)

            When("Unregistering one instances")
            handler1.unregister()
            Then("A new event without this instance is emitted")
            monitor.awaitOnNext(2, timeout) shouldBe true
            monitor.getOnNextEvents.last.map(s => s.address)
                .toSet shouldBe Set(address2)

            When("Unregistering the other instance")
            handler2.unregister()
            Then("A new event is empty with an empty list")
            monitor.awaitOnNext(3, timeout) shouldBe true
            monitor.getOnNextEvents.last.toSet shouldBe Set.empty

            discovery.stop()
            client.stop()
        }
    }
}
