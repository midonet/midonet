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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, Matchers, BeforeAndAfter, FlatSpec}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Topology.{PortGroup, Port, Host}
import org.midonet.cluster.services.MidonetBackend.{ActiveKey, ContainerKey}
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class PortGroupTrackerTest extends FlatSpec with SchedulersTest with BeforeAndAfter
                           with Matchers with GivenWhenThen {

    "Port group tracker" should "receive an error for non-existent group" in {
        Given("A random port group identifier")
        val portGroupId = UUID.randomUUID()

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroupId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive an error")
        obs.getOnErrorEvents should have size 1
        obs.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]

        And("The tracker should not be ready")
        tracker.isReady shouldBe false
        tracker.last shouldBe null
    }

    "Port group tracker" should "emit group with no members" in {
        Given("A port group")
        val portGroup = createPortGroup()

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "include active ports bound to a host" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = false)))
    }

    "Port group tracker" should "exclude active ports not bound to a host" in {
        Given("A port group")
        val port = createPort()
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit for bound port when activated" in {
        Given("A port group")
        val portGroup1 = createPortGroup()

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(portGroup1.getId, Map())

        When("Adding a new port bound to a host")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup2 = portGroup1.toBuilder.addPortIds(port.getId).build()
        store update portGroup2

        Then("The observer should not receive a notification")
        obs.getOnNextEvents should have size 1

        When("Activating the port")
        activatePort(port)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(
            portGroup1.getId, Map(host.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup1.getId, Map(host.getId.asJava -> HostEvent(running = false)))
    }

    "Port group tracker" should "emit for port when bound" in {
        Given("A port group")
        val portGroup1 = createPortGroup()

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Adding a new port")
        val port1 = createPort()
        val portGroup2 = portGroup1.toBuilder.addPortIds(port1.getId).build()
        store update portGroup2

        Then("The observer should not receive a notification")
        obs.getOnNextEvents should have size 1

        When("Binding the port to a host")
        val host = createHost()
        val port2 = port1.toBuilder.setHostId(host.getId)
                                   .addPortGroupIds(portGroup2.getId).build()
        store update port2

        Then("The observer should not receive a notification")
        obs.getOnNextEvents should have size 1

        When("Activating the port")
        activatePort(port2)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(
            portGroup1.getId, Map(host.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup1.getId, Map(host.getId.asJava -> HostEvent(running = false)))
    }

    "Port group tracker" should "emit for port when deactivated" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port is deactivated")
        deactivatePort(port)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit for port when unbound" in {
        Given("A port group")
        val host = createHost()
        val port1 = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port1.getId)
        activatePort(port1)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port is unbound")
        val port2 = port1.toBuilder.clearHostId()
                                   .addPortGroupIds(portGroup.getId).build()
        store update port2

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit when port is removed from the group" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port is removed from the group")
        store update port

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit when port migrates to a different host" in {
        Given("A port group")
        val host1 = createHost()
        val port1 = createPort(hostId = Some(host1.getId))
        val portGroup = createPortGroup(port1.getId)
        activatePort(port1)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port migrates to a different host")
        val host2 = createHost()
        val port2 = port1.toBuilder.setHostId(host2.getId)
                                   .addPortGroupIds(portGroup.getId).build()
        store update port2

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())

        When("Activating the port")
        activatePort(port2)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 3
        obs.getOnNextEvents.get(2) shouldBe PortGroupEvent(
            portGroup.getId, Map(host2.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host2.getId.asJava -> HostEvent(running = false)))
    }

    "Port group tracker" should "emit when creating host status" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Creating the host status")
        val status = createHostStatus(host.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status)))
    }

    "Port group tracker" should "emit when changing host status" in {
        Given("A port group")
        val host = createHost()
        val status1 = createHostStatus(host.getId)
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status1)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status1)))

        When("Changing the host status")
        val status2 = createHostStatus(host.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status2)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status2)))
    }

    "Port group tracker" should "emit when deleting host status" in {
        Given("A port group")
        val host = createHost()
        val status = createHostStatus(host.getId)
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = true, status)))

        When("Deleting the host status")
        deleteHostStatus(host.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = false)))
    }

    "Port group tracker" should "emit when host is deleted" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host is deleted")
        store.delete(classOf[Host], host.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit when port is deleted" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port is deleted")
        store.delete(classOf[Port], port.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "combine ports with the same host on add" in {
        Given("A port group")
        val host = createHost()
        val port1 = createPort(hostId = Some(host.getId))
        val portGroup1 = createPortGroup(port1.getId)
        activatePort(port1)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup1.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Adding a new port to the same host")
        val port2 = createPort(hostId = Some(host.getId))
        val portGroup2 = portGroup1.toBuilder.addPortIds(port2.getId).build()
        store update portGroup2

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
    }

    "Port group tracker" should "combine ports with the same host on update" in {
        Given("A port group")
        val host1 = createHost()
        val host2 = createHost()
        val port1 = createPort(hostId = Some(host1.getId))
        val port2 = createPort(hostId = Some(host2.getId))
        val portGroup = createPortGroup(port1.getId, port2.getId)
        activatePort(port1)
        activatePort(port2)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(
            portGroup.getId, Map(host1.getId.asJava -> HostEvent(running = false),
                                 host2.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host1.getId.asJava -> HostEvent(running = false),
                                 host2.getId.asJava -> HostEvent(running = false)))

        When("The second port migrates to the first host")
        val port3 = port2.toBuilder.setHostId(host1.getId)
                                   .addPortGroupIds(portGroup.getId).build()
        store update port3

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(
            portGroup.getId, Map(host1.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host1.getId.asJava -> HostEvent(running = false)))
    }

    "Port group tracker" should "combine ports with the same host on delete" in {
        Given("A port group")
        val host = createHost()
        val port1 = createPort(hostId = Some(host.getId))
        val port2 = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port1.getId, port2.getId)
        activatePort(port1)
        activatePort(port2)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1
        obs.getOnNextEvents.get(0) shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = false)))

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(
            portGroup.getId, Map(host.getId.asJava -> HostEvent(running = false)))

        When("A port is deleted")
        store.delete(classOf[Port], port1.getId)

        Then("The observer should not receive a notification")
        obs.getOnNextEvents should have size 1

        When("The second port is deleted")
        store.delete(classOf[Port], port2.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit when host is deleted for multiple hosts" in {
        Given("A port group")
        val host = createHost()
        val port1 = createPort(hostId = Some(host.getId))
        val port2 = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port1.getId, port2.getId)
        activatePort(port1)
        activatePort(port2)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host is deleted")
        store.delete(classOf[Host], host.getId)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "complete when port group deleted" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port group is deleted")
        store.delete(classOf[PortGroup], portGroup.getId)

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

    "Port group tracker" should "complete when complete method is called" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("Calling the complete method")
        tracker.complete()

        Then("The observer should receive a completed notification")
        obs.getOnCompletedEvents should have size 1
    }

    "Port group tracker" should "handle host state error" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The host emits an error")
        store.keyObservableError(host.getId.asJava.toString, classOf[Host],
                                 host.getId, ContainerKey, new Throwable)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "handle port error" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port emits an error")
        store.observableError(classOf[Port], port.getId, new Throwable)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "handle port state error" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port emits an error")
        store.keyObservableError(host.getId.asJava.toString, classOf[Port],
                                 port.getId, ActiveKey, new Throwable)

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 2
        obs.getOnNextEvents.get(1) shouldBe PortGroupEvent(portGroup.getId, Map())

        And("The tracker should be ready")
        tracker.isReady shouldBe true
        tracker.last shouldBe PortGroupEvent(portGroup.getId, Map())
    }

    "Port group tracker" should "emit port group error" in {
        Given("A port group")
        val host = createHost()
        val port = createPort(hostId = Some(host.getId))
        val portGroup = createPortGroup(port.getId)
        activatePort(port)

        And("A port group tracker observer")
        val obs = new TestObserver[PortGroupEvent]

        When("Creating a port group tracker")
        val tracker = new PortGroupTracker(portGroup.getId, context)

        And("The observer subscribes to the tracker")
        tracker.observable subscribe obs

        Then("The observer should receive a notification")
        obs.getOnNextEvents should have size 1

        When("The port group emits an error")
        store.observableError(classOf[PortGroup], portGroup.getId, new Throwable)

        Then("The observer should receive the error")
        obs.getOnErrorEvents should have size 1
    }

}
