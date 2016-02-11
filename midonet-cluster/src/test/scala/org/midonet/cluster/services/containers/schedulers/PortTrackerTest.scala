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

import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, Matchers, BeforeAndAfter, FlatSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class PortTrackerTest extends FlatSpec with SchedulersTest with BeforeAndAfter
                      with Matchers with GivenWhenThen {

    "Port tracker" should "receive an error for non-existent port" in {
        Given("A random port identifier")
        val portId = UUID.randomUUID()

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(portId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive an error notification")
        obs.getOnErrorEvents should have size 1
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]

        And("The tracker should not be ready")
        tracker.isReady shouldBe false
        tracker.last shouldBe null
    }

    "Port tracker" should "emit existing port without state" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortEvent(port.getHostId,
                                                      port.getInterfaceName,
                                                      active = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port.getHostId, port.getInterfaceName,
                                        active = false)
    }

    "Port tracker" should "emit existing port with state" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))
        activatePort(port)

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortEvent(port.getHostId,
                                                      port.getInterfaceName,
                                                      active = true)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port.getHostId, port.getInterfaceName,
                                        active = true)
    }

    "Port tracker" should "emit existing port not bound to a host" in {
        Given("A port at a host")
        val port = createPort()

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortEvent(hostId = null,
                                                      port.getInterfaceName,
                                                      active = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(hostId = null, port.getInterfaceName,
                                        active = false)
    }

    "Port tracker" should "complete when port without state is deleted" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port is deleted")
        store.delete(classOf[Port], port.getId)

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

    "Port tracker" should "complete when port with state is deleted" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))
        activatePort(port)

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port is deleted")
        store.delete(classOf[Port], port.getId)

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

    "Port tracker" should "emit notification when port is activated" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("Activating the port")
        activatePort(port)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortEvent(port.getHostId,
                                                      port.getInterfaceName,
                                                      active = true)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port.getHostId, port.getInterfaceName,
                                        active = true)
    }

    "Port tracker" should "emit notification when port is deactivated" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))
        activatePort(port)

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("Deactivating the port")
        deactivatePort(port)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortEvent(port.getHostId,
                                                      port.getInterfaceName,
                                                      active = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port.getHostId, port.getInterfaceName,
                                        active = false)
    }

    "Port tracker" should "emit notification when port is bound" in {
        Given("A port at a host")
        val port1 = createPort()

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port is bound to a host")
        val host = createHost()
        val port2 = port1.toBuilder.setHostId(host.getId).build()
        store.update(port2)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortEvent(port2.getHostId,
                                                      port2.getInterfaceName,
                                                      active = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port2.getHostId, port2.getInterfaceName,
                                        active = false)

        When("Activating the port at the host")
        activatePort(port2)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe PortEvent(port2.getHostId,
                                                      port2.getInterfaceName,
                                                      active = true)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port2.getHostId, port2.getInterfaceName,
                                        active = true)
    }

    "Port tracker" should "emit notification when port is unbound" in {
        Given("A port at a host")
        val host = createHost()
        val port1 = createPort(hostId = Some(host.getId),
                               interfaceName = Some(random.nextString(4)))
        activatePort(port1)

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("Unbinding the port")
        val port2 = port1.toBuilder.clearHostId().build()
        store.update(port2)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortEvent(null,
                                                      port2.getInterfaceName,
                                                      active = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(null, port2.getInterfaceName,
                                        active = false)
    }

    "Port tracker" should "emit notification when port is migrated" in {
        Given("A port at a host")
        val host1 = createHost()
        val port1 = createPort(hostId = Some(host1.getId),
                               interfaceName = Some(random.nextString(4)))
        activatePort(port1)

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port is migrated to a different host")
        val host2 = createHost()
        val port2 = port1.toBuilder.setHostId(host2.getId).build()
        store.update(port2)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortEvent(port2.getHostId,
                                                      port2.getInterfaceName,
                                                      active = false)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port2.getHostId, port2.getInterfaceName,
                                        active = false)

        When("Activating the port at the host")
        activatePort(port2)

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe PortEvent(port2.getHostId,
                                                      port2.getInterfaceName,
                                                      active = true)

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortEvent(port2.getHostId, port2.getInterfaceName,
                                        active = true)
    }

    "Port tracker" should "not emit duplicate notifications" in {
        Given("A port at a host")
        val port1 = createPort()

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port is updated")
        val port2 = port1.toBuilder.setTunnelKey(random.nextLong()).build()
        store.update(port2)

        Then("The tracker should not emit another notification")
        obs.getOnNextEvents should have size 1
    }

    "Port tracker" should "emit error when port emits error" in {
        Given("A port at a host")
        val port = createPort()

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port observable emits an error")
        store.observableError(classOf[Port], port.getId, new Throwable)

        Then("The observer should receive an error")
        obs.getOnErrorEvents should have size 1
    }

    "Port tracker" should "emit error when port state emits an error" in {
        Given("A port at a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId),
                              interfaceName = Some(random.nextString(4)))
        activatePort(port)

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("The port observable emits an error")
        store.keyObservableError(host.getId.asJava.toString, classOf[Port],
                                 port.getId, ActiveKey, new Throwable)

        Then("The observer should receive an error")
        obs.getOnErrorEvents should have size 1
    }

    "Port tracker" should "complete when complete method is called" in {
        Given("A port at a host")
        val port = createPort()

        And("A port tracker observer")
        val obs = new TestObserver[PortEvent]

        When("Creating a port tracker")
        val tracker = new PortTracker(port.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a port event notification")
        obs.getOnNextEvents should have size 1

        When("Calling the complete method")
        tracker.complete()

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

}
