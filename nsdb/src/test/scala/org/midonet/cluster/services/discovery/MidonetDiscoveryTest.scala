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

import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.MidonetEventually
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
    private val random = new Random()
    private val timeout = Duration(5, TimeUnit.SECONDS)

    feature("discovery basics") {
        scenario("instantiation") {
            val discovery = new MidonetDiscovery(curator)
            discovery shouldNot be (null)
        }
    }

    feature("server") {
        scenario("server registration - scala") {
            val discovery = new MidonetDiscovery(curator)
            val info =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val provider = discovery.newProvider("testService", info)
            provider shouldNot be (null)
            provider.register()
            provider.unregister()
        }
        scenario("server registration - java") {
            val discovery = new MidonetDiscovery(curator)
            val info =
                new TestJavaServiceDetails(random.nextString(random.nextInt(9)))
            val provider = discovery.newProvider("testService", info)
            provider shouldNot be (null)
            provider.register()
            provider.unregister()
        }
        scenario("client handle - scala") {
            val discovery = new MidonetDiscovery(curator)
            val info =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val provider = discovery.newProvider("testService", info)
            provider shouldNot be (null)
            provider.register()

            val client =
                discovery.newClient("testService", classOf[TestScalaServiceDetails])

            eventually {
                client.getRandomProvider.isDefined shouldBe true
                client.getRandomProvider.get shouldBe info
                client.getProviders.toSet shouldBe Set(info)
            }

            client.stop()
            provider.unregister()
        }
        scenario("client handle - java") {
            val discovery = new MidonetDiscovery(curator)
            val info =
                new TestJavaServiceDetails(random.nextString(random.nextInt(9)))
            val provider = discovery.newProvider("testService", info)
            provider shouldNot be (null)
            provider.register()

            val client =
                discovery.newClient("testService", classOf[TestJavaServiceDetails])

            eventually {
                client.getRandomProvider.isDefined shouldBe true
                client.getRandomProvider.get shouldBe info
                client.getProviders.toSet shouldBe Set(info)
            }

            client.stop()
            provider.unregister()
        }
        scenario("server disconnection") {
            val discovery = new MidonetDiscovery(curator)
            val info1 =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val info2 =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val provider1 = discovery.newProvider("testService", info1)
            val provider2 = discovery.newProvider("testService", info2)
            provider1.register()
            provider2.register()

            val client =
                discovery.newClient("testService", classOf[TestScalaServiceDetails])

            var info: TestScalaServiceDetails = null
            eventually {
                client.getProviders.toList should have length 2
                val server = client.getRandomProvider
                server.isDefined shouldBe true
                info = server.get
            }

            if (info == info1) {
                provider1.unregister()
                eventually {
                    client.getProviders.toList should have length 1
                    client.getRandomProvider.isDefined shouldBe true
                    client.getRandomProvider.get shouldBe info2
                }
                provider2.unregister()
            } else {
                provider2.unregister()
                eventually {
                    client.getProviders.toList should have length 1
                    client.getRandomProvider.isDefined shouldBe true
                    client.getRandomProvider.get shouldBe info1
                }
                provider1.unregister()
            }

            eventually {
                client.getProviders.toList should have length 0
                client.getRandomProvider.isEmpty shouldBe true
            }

            client.stop()
        }
    }
    feature("notifications") {
        scenario("service addition") {
            val discovery = new MidonetDiscovery(curator)
            val info1 =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val info2 =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val provider1 = discovery.newProvider("testService", info1)
            val provider2 = discovery.newProvider("testService", info2)

            val monitor = new TestAwaitableObserver[Array[TestScalaServiceDetails]]

            val client = discovery.newObservableClient(
                "testService", classOf[TestScalaServiceDetails])
            client.observable.subscribe(monitor)

            monitor.awaitOnNext(1, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 0

            provider1.register()
            monitor.awaitOnNext(2, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 1
            monitor.getOnNextEvents.last shouldBe Array(info1)

            provider2.register()
            monitor.awaitOnNext(3, timeout) shouldBe true
            monitor.getOnNextEvents.last should have length 2
            monitor.getOnNextEvents.last.toSet shouldBe Set(info1, info2)

            client.stop()
        }
        scenario("service removal") {
            val discovery = new MidonetDiscovery(curator)
            val info1 =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val info2 =
                new TestScalaServiceDetails(random.nextString(random.nextInt(9)))
            val provider1 = discovery.newProvider("testService", info1)
            val provider2 = discovery.newProvider("testService", info2)

            val monitor = new TestAwaitableObserver[Array[TestScalaServiceDetails]]

            provider1.register()
            provider2.register()

            val client = discovery.newObservableClient(
                "testService", classOf[TestScalaServiceDetails])
            client.observable.subscribe(monitor)

            eventually {
                monitor.getOnNextEvents.lastOption.map(_.toSet) shouldBe
                    Some(Set(info1, info2))
            }

            provider1.unregister()
            eventually {
                monitor.getOnNextEvents.lastOption.map(_.toSet) shouldBe
                    Some(Set(info2))
            }

            provider2.unregister()
            eventually {
                monitor.getOnNextEvents.lastOption.map(_.toSet) shouldBe
                    Some(Set.empty)
            }

            client.stop()
        }
    }
    feature("host:port service") {
        scenario("service discovery") {
            val info = new HostPortInfo("localhost", random.nextInt(65535))
            val discovery = new MidonetDiscovery(curator)

            val provider = discovery.newProvider("test_service", info)
            provider.serviceName shouldBe "test_service"
            provider.register()

            val client = discovery.newClient("test_service", classOf[HostPortInfo])
            client.serviceName shouldBe "test_service"

            eventually {
                client.getRandomProvider.isDefined shouldBe true
                client.getRandomProvider.get shouldBe info
            }

            client.stop()
            provider.unregister()
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
    final class TestScalaServiceDetails(desc: String) {
        def this() = this("")
        private var description = if (desc == null) "" else desc
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

