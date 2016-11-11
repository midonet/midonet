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

package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.models.Topology.{Port, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{LocalPortActive, TunnelZoneMemberOp, TunnelZoneUpdate}
import org.midonet.midolman.topology.devices.{Host, PortBinding, TunnelZoneType}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class VirtualToPhysicalMapperTest extends MidolmanSpec with TopologyBuilder
                                  with TopologyMatchers {
    import TopologyBuilder._

    private val timeout = 5 seconds
    private var store: Storage = _
    private var stateStore: StateStorage = _

    override def beforeTest(): Unit = {
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
    }

    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

    feature("The virtual to physical mapper emits host updates") {
        scenario("Observable emits error for non-existing host") {
            Given("A random host identifier")
            val hostId = UUID.randomUUID()

            And("A host observer")
            val obs = new TestObserver[Host]

            When("Subscribing to the host")
            VirtualToPhysicalMapper.hosts(hostId).subscribe(obs)

            Then("The mapper should emit an error")
            obs.getOnErrorEvents should have size 1
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
        }

        scenario("Observable emits existing host") {
            Given("A host in storage")
            val host = createHost()
            store.create(host)

            And("A host observer")
            val obs = new TestObserver[Host]

            When("Subscribing to the host")
            VirtualToPhysicalMapper.hosts(host.getId).subscribe(obs)

            Then("The mapper should emit the host")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeDeviceOf host
            obs.getOnNextEvents.get(0).alive shouldBe false

            When("The host is set as active")
            makeHostAlive(host.getId)

            Then("The mapper should emit the host as active")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDeviceOf host
            obs.getOnNextEvents.get(1).alive shouldBe true
        }

        scenario("Observable emits existing host with the port bindings") {
            Given("A host in storage")
            val host = createHost()
            store.create(host)

            And("A host observer")
            val obs = new TestObserver[Host]

            When("Subscribing to the host")
            VirtualToPhysicalMapper.hosts(host.getId).subscribe(obs)

            Then("The mapper should emit the host")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeDeviceOf host
            obs.getOnNextEvents.get(0).portBindings shouldBe empty

            When("Adding a first port binding to this host")
            val bridge = createBridge()
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("eth0"))
            store.multi(Seq(CreateOp(bridge), CreateOp(port1)))

            Then("The mapper should emit the host with the port bound")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDeviceOf host.addPortId(port1.getId)
            obs.getOnNextEvents.get(1).portBindings shouldBe Map(
                port1.getId.asJava -> PortBinding(port1.getId.asJava, null, -1,
                                                  "eth0"))

            When("Adding a second port binding to this host")
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("eth1"))
            store.create(port2)

            Then("The mapper should emit the host with both ports bound")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBeDeviceOf host.addPortId(port1.getId)
                                                            .addPortId(port2.getId)
            obs.getOnNextEvents.get(2).portBindings shouldBe Map(
                port1.getId.asJava -> PortBinding(port1.getId.asJava, null, -1,
                                                  "eth0"),
                port2.getId.asJava -> PortBinding(port2.getId.asJava, null, -1,
                                                  "eth1"))

            When("Deleting the first port binding")
            store.delete(classOf[Topology.Port], port1.getId)

            Then("The mapper should emit the host with the second port bound")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeDeviceOf host.addPortId(port2.getId)
            obs.getOnNextEvents.get(3).portBindings shouldBe Map(
                port2.getId.asJava -> PortBinding(port2.getId.asJava, null, -1,
                                                  "eth1"))

            When("Deleting the second port binding")
            store.delete(classOf[Topology.Port], port2.getId)

            Then("The mapper should emit the host with no ports bound")
            obs.getOnNextEvents should have size 5
            obs.getOnNextEvents.get(4) shouldBeDeviceOf host
            obs.getOnNextEvents.get(4).portBindings shouldBe empty
        }

        scenario("Observable emits existing host with the tunnel-zones") {
            Given("A host in storage")
            val host = createHost()
            store.create(host)

            And("A host observer")
            val obs = new TestObserver[Host]

            When("Subscribing to the host")
            VirtualToPhysicalMapper.hosts(host.getId).subscribe(obs)

            Then("The mapper should emit the host")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeDeviceOf host
            obs.getOnNextEvents.get(0).tunnelZones shouldBe empty

            When("Adding the host to a first tunnel-zone")
            val tz1 = createTunnelZone(tzType = TunnelZone.Type.GRE,
                                       hosts = Map(host.getId.asJava -> "10.0.0.1"))
            store.create(tz1)

            Then("The mapper should emit the host with the first tunnel-zone")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDeviceOf host.addTunnelZoneId(tz1.getId)
            obs.getOnNextEvents.get(1).tunnelZones shouldBe Map(
                tz1.getId.asJava -> asIPAddress("10.0.0.1"))

            When("Adding the host to a second tunnel-zone")
            val tz2 = createTunnelZone(tzType = TunnelZone.Type.GRE,
                                       hosts = Map(host.getId.asJava -> "10.0.0.1"))
            store.create(tz2)

            Then("The mapper should emit the host with both tunnel-zones")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBeDeviceOf host.addTunnelZoneId(tz1.getId)
                                                            .addTunnelZoneId(tz2.getId)
            obs.getOnNextEvents.get(2).tunnelZones shouldBe Map(
                tz1.getId.asJava -> asIPAddress("10.0.0.1"),
                tz2.getId.asJava -> asIPAddress("10.0.0.1"))

            When("Removing the host from the first tunnel-zone")
            store.delete(classOf[TunnelZone], tz1.getId)

            Then("The mapper should emit the host with the second tunnel-zone")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeDeviceOf host.addTunnelZoneId(tz2.getId)
            obs.getOnNextEvents.get(3).tunnelZones shouldBe Map(
                tz2.getId.asJava -> asIPAddress("10.0.0.1"))

            When("Removing the host from the second tunnel-zone")
            store.delete(classOf[TunnelZone], tz2.getId)

            Then("The mapper should emit the host with the second tunnel-zone")
            obs.getOnNextEvents should have size 5
            obs.getOnNextEvents.get(4) shouldBeDeviceOf host
            obs.getOnNextEvents.get(4).tunnelZones shouldBe empty
        }

        scenario("Observable completes when host is deleted") {
            Given("A host in storage")
            val host = createHost()
            store.create(host)

            And("A host observer")
            val obs = new TestObserver[Host]

            When("Subscribing to the host")
            VirtualToPhysicalMapper.hosts(host.getId).subscribe(obs)

            Then("The mapper should emit the host")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeDeviceOf host

            When("The host is deleted")
            store.delete(classOf[Topology.Host], host.getId)

            Then("The mapper observable should complete")
            obs.getOnCompletedEvents should not be empty
        }

        scenario("Observable recovers when reading the host fails") {
            // TODO: Needs an in-memory storage backdoor to emulate errors (MI-97)
        }
    }

    feature("The virtual to physical mapper emits tunnel zone updates") {
        scenario("Observable emits error for non-existing tunnel-zone") {
            Given("A random tunnel-zone identifier")
            val tzId = UUID.randomUUID()

            And("A tunnel-zone observer")
            val obs = new TestObserver[TunnelZoneUpdate]

            When("Subscribing to the tunnel-zone")
            VirtualToPhysicalMapper.tunnelZones(tzId).subscribe(obs)

            Then("The mapper should emit an error")
            obs.getOnErrorEvents should have size 1
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
        }

        scenario("Observable emits existing tunnel-zone without members") {
            Given("A tunnel-zone in storage")
            val tz = createTunnelZone(tzType = TunnelZone.Type.GRE)
            store.create(tz)

            And("A tunnel-zone observer")
            val obs = new TestObserver[TunnelZoneUpdate]

            When("Subscribing to the tunnel-zone")
            VirtualToPhysicalMapper.tunnelZones(tz.getId).subscribe(obs)

            Then("The mapper should not emit an update")
            obs.getOnNextEvents shouldBe empty

            When("Adding a first tunnel-zone member")
            val host1 = createHost()
            store.multi(Seq(CreateOp(host1),
                            UpdateOp(tz.addMember(host1.getId, "10.0.0.1"))))

            Then("The mapper should emit the tunnel-zone update")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.GRE, host1.getId, "10.0.0.1",
                TunnelZoneMemberOp.Added)

            When("Adding a second tunnel-zone member")
            val host2 = createHost()
            store.multi(Seq(CreateOp(host2),
                            UpdateOp(tz.addMember(host1.getId, "10.0.0.1")
                                       .addMember(host2.getId, "10.0.0.2"))))

            Then("The mapper should emit the tunnel-zone update")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.GRE, host2.getId, "10.0.0.2",
                TunnelZoneMemberOp.Added)
        }

        scenario("Observable emits existing tunnel-zone with members") {
            Given("A tunnel-zone in storage")
            val host1 = createHost()
            val tz = createTunnelZone(tzType = TunnelZone.Type.GRE,
                                      hosts = Map(host1.getId.asJava -> "10.0.0.1"))
            store.multi(Seq(CreateOp(host1), CreateOp(tz)))

            And("A tunnel-zone observer")
            val obs = new TestObserver[TunnelZoneUpdate]

            When("Subscribing to the tunnel-zone")
            VirtualToPhysicalMapper.tunnelZones(tz.getId).subscribe(obs)

            Then("The mapper should emit the tunnel-zone update")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.GRE, host1.getId, "10.0.0.1",
                TunnelZoneMemberOp.Added)
        }

        scenario("Observable emits updates when a member address has changed") {
            Given("A tunnel-zone in storage")
            val host1 = createHost()
            val host2 = createHost()
            val tz = createTunnelZone(tzType = TunnelZone.Type.GRE,
                                      hosts = Map(host1.getId.asJava -> "10.0.0.1",
                                                  host2.getId.asJava -> "10.0.0.2"))
            store.multi(Seq(CreateOp(host1), CreateOp(host2), CreateOp(tz)))

            And("A tunnel-zone observer")
            val obs = new TestObserver[TunnelZoneUpdate]

            When("Subscribing to the tunnel-zone")
            VirtualToPhysicalMapper.tunnelZones(tz.getId).subscribe(obs)

            Then("The mapper should emit the tunnel-zone update")
            obs.getOnNextEvents should have size 2

            When("The address of a member has changed")
            store.update(tz.clearMembers()
                           .addMember(host1.getId, "10.0.0.3")
                           .addMember(host2.getId, "10.0.0.2"))

            Then("The mapper should emit the tunnel-zone update")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.GRE, host1.getId, "10.0.0.1",
                TunnelZoneMemberOp.Deleted)
            obs.getOnNextEvents.get(3) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.GRE, host1.getId, "10.0.0.3",
                TunnelZoneMemberOp.Added)
        }

        scenario("Observable emits updates when tunnel-zone type has changed") {
            Given("A tunnel-zone in storage")
            val host1 = createHost()
            val tz = createTunnelZone(tzType = TunnelZone.Type.GRE,
                                      hosts = Map(host1.getId.asJava -> "10.0.0.1"))
            store.multi(Seq(CreateOp(host1), CreateOp(tz)))

            And("A tunnel-zone observer")
            val obs = new TestObserver[TunnelZoneUpdate]

            When("Subscribing to the tunnel-zone")
            VirtualToPhysicalMapper.tunnelZones(tz.getId).subscribe(obs)

            Then("The mapper should emit the tunnel-zone update")
            obs.getOnNextEvents should have size 1

            When("The tunnel-zone type changes")
            store.update(tz.setType(TunnelZone.Type.VXLAN))
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.GRE, host1.getId, "10.0.0.1",
                TunnelZoneMemberOp.Deleted)
            obs.getOnNextEvents.get(2) shouldBe TunnelZoneUpdate(
                tz.getId, TunnelZoneType.VXLAN, host1.getId, "10.0.0.1",
                TunnelZoneMemberOp.Added)
        }

        scenario("Observable recovers when reading the tunnel-zone fails") {
            Given("A corrupted tunnel-zone in storage")
            val tz = TunnelZone
                .newBuilder()
                .setId(randomUuidProto)
                .addHosts(TunnelZone.HostToIp
                              .newBuilder()
                              .setHostId(randomUuidProto)
                              .setIp(Commons.IPAddress
                                         .newBuilder()
                                         .setAddress("bad-ip")
                                         .setVersion(Commons.IPVersion.V4)))
                .build()
            store.create(tz)

            // TODO: Provide a configurable maximum retry/backoff mechanism
            // TODO: Issue: MI-97

            //And("A tunnel-zone observer")
            //val obs = new TestObserver[TunnelZoneUpdate]

            //When("Subscribing to the tunnel-zone")
            //VirtualToPhysicalMapper.tunnelZones(tz.getId).subscribe(obs)

            //Then("The mapper should not emit an update")
            //obs.getOnNextEvents shouldBe empty
        }
    }

    feature("The virtual to physical mapper manages the active local ports") {
        scenario("Port active state is written to storage") {
            Given("A bridge port")
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId))
            store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            Then("There is no state in store")
            stateStore.getKey(classOf[Port], port.getId.asJava,
                              MidonetBackend.ActiveKey).await(timeout).nonEmpty shouldBe false

            When("Setting the port as active")
            VirtualToPhysicalMapper.setPortActive(port.getId, portNumber = -1,
                                                  active = true,
                                                  tunnelKey = 0L)

            Then("The state should be saved in store")
            stateStore.getKey(classOf[Port], port.getId.asJava,
                              MidonetBackend.ActiveKey).await(timeout).nonEmpty shouldBe true

            When("Setting the port as inactive")
            VirtualToPhysicalMapper.setPortActive(port.getId, portNumber = -1,
                                                  active = false,
                                                  tunnelKey = 0L)

            Then("There is no state in store")
            stateStore.getKey(classOf[Port], port.getId.asJava,
                              MidonetBackend.ActiveKey).await(timeout).nonEmpty shouldBe false
        }

        scenario("Port active state is notified") {
            Given("A bridge port")
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId))
            store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            And("A ports status observer")
            val obs = new TestObserver[LocalPortActive]
            VirtualToPhysicalMapper.portsActive.subscribe(obs)

            When("Setting the port as active")
            VirtualToPhysicalMapper.setPortActive(port.getId, portNumber = -1,
                                                  active = true,
                                                  tunnelKey = 0L)

            Then("The observer should receive the new port status")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe LocalPortActive(port.getId,
                                                                portNumber = -1,
                                                                active = true)

            When("Setting the port as inactive")
            VirtualToPhysicalMapper.setPortActive(port.getId, portNumber = -1,
                                                  active = false,
                                                  tunnelKey = 0L)

            Then("The observer should receive the new port status")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe LocalPortActive(port.getId,
                                                                portNumber = -1,
                                                                active = false)
        }
    }

    feature("The virtual to physical mapper manager sub-services") {
        scenario("The gateway mapping service") {
            Then("Calling the singleton method should succeed")
            GatewayMappingService.gateways.hasMoreElements
        }
    }

}
