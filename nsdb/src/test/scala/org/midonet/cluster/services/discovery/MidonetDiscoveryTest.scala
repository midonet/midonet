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
import java.security.InvalidParameterException
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.util.Random

import org.apache.curator.x.discovery.ServiceInstance
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex.TestAwaitableObserver

/*
 * Note: For these tests we use service descriptions implemented as Java
 * and Scala classes because there are some differences in how json
 * serializer treats them, so we make sure it works for both languages.
 * (It also serves as example: note that 'TestJavaServiceDetails' definition
 * requires an annotation...)
 */
@RunWith(classOf[JUnitRunner])
class MidonetDiscoveryTest extends FeatureSpec with Matchers
                                   with CuratorTestFramework
                                   with MidonetEventually {
    import MidonetDiscoveryTest._
    private val timeout = Duration(5, TimeUnit.SECONDS)
    private val executor = new SameThreadButAfterExecutorService

    private val server1 = "127.0.0.1"
    private val server2 = "127.0.0.2"
    private val port = 1234

    feature("discovery basics") {
        scenario("instantiation") {
            val discovery = new MidonetDiscovery(curator, executor)
            discovery shouldNot be (null)
        }
    }

    feature("server") {
        scenario("server registration - scala") {
            val discovery = new MidonetDiscovery[TestScalaServiceDetails](curator, executor)
            val info = new TestScalaServiceDetails(null)
            val provider = discovery.createServiceInstance("testService", Option(server1), Option(port), Option(info))
            provider.serviceInstance shouldBe null
            provider.register()
            provider.serviceInstance shouldNot be (null)
            provider.unregister()
            discovery.stop()
        }

        scenario("server registration - java") {
            val discovery = new MidonetDiscovery[TestJavaServiceDetails](curator, executor)
            val info = new TestJavaServiceDetails(null)
            val provider = discovery.createServiceInstance("testService", Option(server1), Option(port), Option(info))
            provider.serviceInstance shouldBe null
            provider.register()
            provider.serviceInstance shouldNot be (null)
            provider.unregister()
            discovery.stop()
        }

        scenario("client handle - scala") {
            val discovery = new MidonetDiscovery[TestScalaServiceDetails](curator, executor)
            val info = new TestScalaServiceDetails(null)
            val provider = discovery.createServiceInstance("testService", Option(server1), Option(port), Option(info))
            provider.register()

            val client = discovery.getClient("testService")

            eventually {
                client.getRandomInstance.isDefined shouldBe true
                val service = client.getRandomInstance.get
                service.getAddress shouldBe server1
                service.getPort shouldBe port
                service.getPayload shouldBe info
                client.getAllInstances.toSet should have size 1
            }

            provider.unregister()
            client.stop()
            discovery.stop()
        }

        scenario("client handle - java") {
            val discovery = new MidonetDiscovery[TestJavaServiceDetails](curator, executor)
            val info = new TestJavaServiceDetails(null)
            val provider = discovery.createServiceInstance("testService", Option(server1), Option(port), Option(info))
            provider.register()

            val client = discovery.getClient("testService")

            eventually {
                client.getRandomInstance.isDefined shouldBe true
                val service = client.getRandomInstance.get
                service.getAddress shouldBe server1
                service.getPort shouldBe port
                service.getPayload shouldBe info
                client.getAllInstances.toSet should have size 1
            }

            provider.unregister()
            discovery.stop()
            client.stop()
        }

        scenario("server disconnection") {
            val discovery = new MidonetDiscovery[TestScalaServiceDetails](curator, executor)
            val info1 = new TestScalaServiceDetails(null)
            val info2 = new TestScalaServiceDetails(null)
            val provider1 = discovery.createServiceInstance(
                "testService", Option(server1), Option(port), Option(info1))
            val provider2 = discovery.createServiceInstance(
                "testService", Option(server2), Option(port), Option(info2))
            provider1.register()
            provider2.register()

            val client = discovery.getClient("testService")

            var info: TestScalaServiceDetails = null
            eventually {
                client.getAllInstances should have length 2
                val server = client.getRandomInstance
                server.isDefined shouldBe true
                info = server.get.getPayload
            }

            if (info == info1) {
                provider1.unregister()
                eventually {
                    client.getAllInstances should have length 1
                    val server = client.getRandomInstance
                    server.isDefined shouldBe true
                    server.get.getPayload shouldBe info2
                }
                provider2.unregister()
            } else {
                provider2.unregister()
                eventually {
                    client.getAllInstances should have length 1
                    val server = client.getRandomInstance
                    server.isDefined shouldBe true
                    server.get.getPayload shouldBe info1
                }
                provider1.unregister()
            }

            eventually {
                client.getAllInstances should have length 0
                client.getRandomInstance.isEmpty shouldBe true
            }

            discovery.stop()
            client.stop()
        }
    }

    feature("notifications") {
        scenario("service addition") {
            val discovery = new MidonetDiscovery(curator, executor)

            val monitor = new TestAwaitableObserver[List[ServiceInstance[Nothing]]]
            val client = discovery.getClient("testService")
            client.observable.subscribe(monitor)

            val provider1 = discovery.createServiceInstance(
                "testService", Option(server1), Option(port), Option.empty)
            val provider2 = discovery.createServiceInstance(
                "testService", Option(server2), Option(port), Option.empty)
            provider1.register()
            provider2.register()

            monitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 0

            monitor.awaitOnNext(2, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 1
            monitor.getOnNextEvents.last.get(0).getAddress shouldBe server1

            monitor.awaitOnNext(3, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 2
            monitor.getOnNextEvents.last.map(s => s.getAddress)
                .toSet shouldBe Set(server1, server2)

            provider1.unregister()
            provider2.unregister()
            discovery.stop()
            client.stop()
        }

        scenario("service removal") {
            val discovery = new MidonetDiscovery(curator, executor)

            val provider1 = discovery.createServiceInstance(
                "testService", Option(server1), Option(port), Option.empty)
            val provider2 = discovery.createServiceInstance(
                "testService", Option(server2), Option(port), Option.empty)
            provider1.register()
            provider2.register()

            val monitor = new TestAwaitableObserver[List[ServiceInstance[Nothing]]]
            val client = discovery.getClient("testService")
            client.observable.subscribe(monitor)

            monitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.last.map(s => s.getAddress)
                .toSet shouldBe Set(server1, server2)

            provider1.unregister()
            monitor.awaitOnNext(2, timeout) shouldBe true
            monitor.getOnNextEvents.last.map(s => s.getAddress)
                .toSet shouldBe Set(server2)

            provider2.unregister()
            monitor.awaitOnNext(3, timeout) shouldBe true
            monitor.getOnNextEvents.last.toSet shouldBe Set.empty

            discovery.stop()
            client.stop()
        }
    }

    feature("URI as payload without server/port") {
        scenario("service discovery") {
            val info = new URI("localhost:65535")
            val discovery = new MidonetDiscovery[URI](curator, executor)

            val provider = discovery.createServiceInstance("test_service", Option.empty, Option.empty, Option(info))
            provider.register()

            val client = discovery.getClient("test_service")

            eventually {
                client.getRandomInstance.isDefined shouldBe true
                client.getRandomInstance.get.getPayload shouldBe info
            }

            provider.unregister()
            discovery.stop()
            client.stop()
        }

        scenario("service with only one parameter specified") {
            val discovery = new MidonetDiscovery(curator, executor)
            intercept[InvalidParameterException] {
                val provider = discovery
                    .createServiceInstance("test_service", Option(server1),
                                           Option.empty, Option.empty)
            }

        }
    }
}

object MidonetDiscoveryTest {
    /**
     * Test class for service discovery details in scala.
     * The class must be static, and have a default constructor without
     * parameters (note: setting a default value in the main constructor is
     * not enough).
     * Methods 'equals' and 'hashcode' must be defined
     */
    val random = new Random()

    final class TestScalaServiceDetails(desc: String) {
        def this() = this("")
        private var description =
            if (desc == null) random.nextString(random.nextInt(8)) else desc
        def setDescription(desc: String): Unit =
            description = if (desc == null) "" else desc
        def getDescription: String = description
        override def equals(o: Any): Boolean = o match {
            case that: TestScalaServiceDetails => description == that.description
            case _ => false
        }
        override def hashCode = description.hashCode
    }
}

