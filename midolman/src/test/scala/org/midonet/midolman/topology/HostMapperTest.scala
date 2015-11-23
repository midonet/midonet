/*
 * Copyright 2014 Midokura SARL
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

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import com.codahale.metrics.MetricRegistry

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{CreateOp, StateStorage, Storage}
import org.midonet.cluster.models.Commons.{IPVersion, IPAddress}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{Host, Port, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.AliveKey
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.TopologyTest.DeviceObserver
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPAddr
import org.midonet.util.concurrent._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class HostMapperTest extends MidolmanSpec
                     with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var metricRegistry: MetricRegistry = _
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private final val timeout = 5 seconds

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        metricRegistry = injector.getInstance(classOf[MetricRegistry])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
    }

    feature("A host should come with its tunnel zones membership") {
        scenario("The host is in one tunnel zone") {
            Given("A host member of one tunnel zone")
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a simulation host with the host's tunnel zone membership")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost, Set(protoTunnelZone))

            And("The host mapper is observing the tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe true
        }

        scenario("The host is in one tunnel zone and then in two") {
            Given("A host member of one tunnel zone")
            val (protoHost1, protoTunnelZone1) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost1.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a host with one tunnel zone")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            var simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost1, Set(protoTunnelZone1))
            hostMapper.isObservingTunnel(protoTunnelZone1.getId) shouldBe true

            And("Add a 2nd tunnel zone the host is a member of")
            val (protoHost2, protoTunnelZone2) = addTunnelZoneToHost(protoHost1)

            Then("We obtain a simulation host with the host's tunnel zone membership")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost2,
                         Set(protoTunnelZone1, protoTunnelZone2))

            And("The host mapper is observing the two tunnel zones")
            hostMapper.isObservingTunnel(protoTunnelZone1.getId) shouldBe true
            hostMapper.isObservingTunnel(protoTunnelZone2.getId) shouldBe true
        }

        scenario("The host is in one tunnel zone and then none") {
            Given("A host member of one tunnel zone")
            val (protoHost1, protoTunnelZone) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost1.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            Then("We remove the host from the tunnel zone")
            val protoHost2 = removeHostFromAllTunnelZones(protoHost1)

            Then("We obtain a simulation host that does not belong to any tunnel zones")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost2, Set.empty)

            And("The host mapper is not observing the removed tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe false
        }

        scenario("The host mapper discards tunnel zones with no hostId " +
                 "back-reference") {
            Given("A host member of one tunnel zone")
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            When("We clear the host id field in the tunnel zone")
            val updatedTunnelZone = protoTunnelZone.toBuilder
                .clearHostIds()
                .build()
            store.update(updatedTunnelZone)

            Then("We receive a single host notification")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2
        }

        scenario("The host mapper discards tunnel zones that emit errors") {
            Given("A host member of one tunnel zone")
            val (host, tunnelZone1) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(host.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the tunnel-zone with invalid input")
            val tunnelZone2 = tunnelZone1.toBuilder
                .addHostIds(host.getId)
                .clearHosts()
                .addHosts(HostToIp.newBuilder().build())
                .build()
            store.update(tunnelZone2)

            Then("We receive a single host notification without the tunnel zone")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents.get(1).tunnelZones shouldBe empty

            When("Updating the host by binding a port")
            val Seq(port) = createRouterWithPorts(1)
            bindPort(port, host, "eth0")

            Then("We receive a single host notification without the tunnel zone")
            hostObs.awaitOnNext(3, timeout) shouldBe true
            hostObs.getOnNextEvents.get(2).tunnelZones shouldBe empty
        }
    }

    feature("A host's port bindings should be handled") {
        scenario("The host has one port binding on creation") {
            Given("A host with one port binding")
            val (protoHost, _) = createHostAndTunnelZone()
            val port = createRouterWithPorts(1).head
            bindPort(port, protoHost, "eth0")
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            Then("We obtain a simulation host with the port bound.")
            hostObs.getOnNextEvents.last.portBindings should
                contain only port.getId.asJava -> "eth0"

            And("The host mapper is observing the port.")
            hostMapper.isObservingPort(port.getId) shouldBe true
        }

        scenario("An additional port is bound while mapper is watching host") {
            Given("A host with one port binding")
            val (protoHost, _) = createHostAndTunnelZone()
            val Seq(port1, port2) = createRouterWithPorts(2)
            bindPort(port1, protoHost, "eth0")
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a simulation host with one port binding")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 1

            And("When we bind an additional port to the host")
            bindPort(port2, protoHost, "eth1")

            Then("We obtain a simulation host with both port bindings")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2

            hostObs.getOnNextEvents.last.portBindings should contain
                only(port1.getId.asJava -> "eth0", port2.getId.asJava -> "eth1")

            And("The host mapper is watching both ports")
            hostMapper.isObservingPort(port1.getId) shouldBe true
            hostMapper.isObservingPort(port2.getId) shouldBe true
        }

        scenario("The host mapper discards ports with no hostId back-reference") {
            Given("A host with one port binding")
            val (protoHost, _) = createHostAndTunnelZone()

            Given("A host member with one port")
            val Seq(port1) = createRouterWithPorts(1)
            bindPort(port1, protoHost, "eth0")
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            When("We clear the host id field in the port")
            store.update(port1.toBuilder.clearHostId().build())

            Then("We receive a single host notification")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2
        }

        scenario("A port is deleted while mapper is watching host") {
            Given("A host with two port bindings")
            val (protoHost, _) = createHostAndTunnelZone()
            val Seq(port1, port2) = createRouterWithPorts(2)
            bindPort(port1, protoHost, "eth0")
            bindPort(port2, protoHost, "eth1")
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We receive a host update with two port bindings")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 1
            hostObs.getOnNextEvents.last.portBindings should contain only
                (port1.getId.asJava -> "eth0", port2.getId.asJava -> "eth1")

            And("The host mapper is watching both ports")
            hostMapper.isObservingPort(port1.getId) shouldBe true
            hostMapper.isObservingPort(port2.getId) shouldBe true

            And("When we delete one of the bound ports")
            store.delete(classOf[Port], port1.getId)

            Then("We obtain a simulation host with one binding")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2

            hostObs.getOnNextEvents.last.portBindings should
                contain only port2.getId.asJava -> "eth1"

            And("The host mapper is not watching the deleted port")
            hostMapper.isObservingPort(port1.getId) shouldBe false
            hostMapper.isObservingPort(port2.getId) shouldBe true
        }

        scenario("The host mapper discards ports that emit errors") {
            Given("A host member of one tunnel zone")
            val (host, _) = createHostAndTunnelZone()
            val Seq(port1) = createRouterWithPorts(1)
            bindPort(port1, host, "eth0")
            val hostMapper = new HostMapper(host.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the port with invalid input")
            val port2 = port1.toBuilder
                             .setPortAddress(IPAddress.newBuilder()
                                                      .setVersion(IPVersion.V4)
                                                      .setAddress("")
                                                      .build())
                             .build()
            store.update(port2)

            Then("We receive a single host notification without the port")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents.get(1).portBindings shouldBe empty
            hostObs.getOnNextEvents.get(1).portIds shouldBe empty

            When("Updating the host by binding a port")
            val Seq(port3) = createRouterWithPorts(1)
            bindPort(port3, host, "eth0")

            Then("We receive a single host notification without the first port")
            hostObs.awaitOnNext(3, timeout) shouldBe true
            hostObs.getOnNextEvents.get(2).portBindings should have size 1
            hostObs.getOnNextEvents.get(2).portIds should have size 1
            hostObs.getOnNextEvents.get(2).portBindings should not contain key(port1.getId.asJava)
            hostObs.getOnNextEvents.get(2).portIds should not contain port1.getId.asJava
        }
    }

    feature("The host mapper handles deletion of hosts appropriately") {
        scenario("Deleting the host") {
            Given("A host member of one tunnel zone with one bound port")
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone()
            val port = createRouterWithPorts(1).head
            bindPort(port, protoHost, "eth0")
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("The host must be notified")
            hostObs.awaitOnNext(1, timeout) shouldBe true

            When("We delete the host")
            store.delete(classOf[Host], protoHost.getId.asJava)

            Then("We obtain a simulation host with an onComplete notification")
            hostObs.awaitCompletion(timeout)
            hostObs.getOnCompletedEvents should have size 1

            And("The host mapper is not observing the tunnel zone or port")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe false
            hostMapper.isObservingPort(port.getId) shouldBe false
        }
    }

    feature("The host mapper handles the alive status of the host correctly") {
        scenario("The alive status of the host is updated appropriately") {
            Given("An alive host")
            val (protoHost, _) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a host that is alive")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            var simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true

            And("When we set the alive status to false")
            setHostAliveStatus(protoHost.getId, alive = false)

            Then("We obtain the host with an alive status set to false")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe false

            And("When we set the alive status of the host back to true")
            setHostAliveStatus(protoHost.getId, alive = true)

            Then("We obtain an alive host")
            hostObs.awaitOnNext(3, timeout)
            simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true
        }
    }

    feature("The host mapper discards updates that do not modify the host") {
        scenario("A host update with no modifications") {
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a host update")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 1

            And("When we update the host with the same object and add " +
                "a tunnel zone")
            store.update(protoHost)
            addTunnelZoneToHost(protoHost)

            Then("We obtain a single host notification")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2
        }

        scenario("A tunnel zone update with no modifications") {
            var (protoHost, protoTunnelZone) = createHostAndTunnelZone()
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a host update")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 1

            And("When we update the tunnel zone with the same object")
            protoTunnelZone = Await.result(
                store.get(classOf[TunnelZone], protoTunnelZone.getId), timeout)
            store.update(protoTunnelZone)

            And("We add a 2nd tunnel zone to the host")
            addTunnelZoneToHost(protoHost)

            Then("We obtain a single host notification")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2
        }

        scenario("A port update with no modifications") {
            val (protoHost, _) = createHostAndTunnelZone()
            var Seq(port) = createRouterWithPorts(1)
            bindPort(port, protoHost, "eth0")
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, metricRegistry)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new DeviceObserver[SimHost](vt)
            observable.subscribe(hostObs)

            Then("We obtain a host update")
            hostObs.awaitOnNext(1, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 1

            And("When we update the port with the same object")
            port = Await.result(store.get(classOf[Port], port.getId), timeout)
            store.update(port)

            And("We add a tunnel zone to the host")
            addTunnelZoneToHost(protoHost)

            Then("We obtain a single host update")
            hostObs.awaitOnNext(2, timeout) shouldBe true
            hostObs.getOnNextEvents should have size 2
        }
    }

    private def assertEquals(simHost: SimHost, protoHost: Host,
                             tunnelZones: Set[TunnelZone]) = {
        protoHost.getId.asJava shouldBe simHost.id
        fromProtoListToScala(protoHost.getPortIdsList) should
            contain theSameElementsAs simHost.portBindings.keys
        fromProtoListToScala(protoHost.getTunnelZoneIdsList) should
            contain theSameElementsAs simHost.tunnelZoneIds
        var membershipSize = 0
        for (tz <- tunnelZones) {
            tz.getHostsList.foreach(hostToIp => {
                val hostId = hostToIp.getHostId
                if (hostId.asJava.equals(simHost.id)) {
                    val ip = IPAddressUtil.toIPAddr(hostToIp.getIp)
                    simHost.tunnelZones.get(tz.getId.asJava).get shouldBe ip
                    membershipSize += 1
                }
            })
        }
        simHost.tunnelZones.keySet should have size membershipSize
    }

    /**
     * This method sets the host alive status by adding/deleting the host
     * owner respectively. Note that the host ownership type is exclusive.
     */
    private def setHostAliveStatus(hostId: UUID, alive: Boolean) = {
        if (alive)
            stateStore.addValue(classOf[Host], hostId, AliveKey, AliveKey)
                      .await(timeout)
        else
            stateStore.removeValue(classOf[Host], hostId, AliveKey, null)
                      .await(timeout)
    }

    private def addTunnelZoneToHost(protoHost: Host): (Host, TunnelZone) = {

        val newProtoTunnelZone = newTunnelZone(protoHost.getId.asJava)
        store.create(newProtoTunnelZone)
        val oldTZId = protoHost.getTunnelZoneIds(0).asJava
        val newTZId = newProtoTunnelZone.getId.asJava
        val updatedHost = newHost(protoHost.getId,
                                  Set(oldTZId, newTZId))
        (updatedHost, newProtoTunnelZone)
    }

    private def removeHostFromAllTunnelZones(protoHost: Host): Host = {

        val updatedHost = newHost(protoHost.getId.asJava, Set[UUID]())
        store.update(updatedHost)
        updatedHost
    }

    private def newPort(bridgeId: Option[UUID] = None,
                        routerId: Option[UUID] = None,
                        hostId: Option[UUID] = None,
                        ifName: Option[String] = None): Port = {
        assert(bridgeId.isDefined != routerId.isDefined)
        if (bridgeId.isDefined) {
            createBridgePort(bridgeId = bridgeId,
                             hostId = hostId, interfaceName = ifName)
        } else {
            createRouterPort(routerId = routerId,
                             hostId = hostId, interfaceName = ifName)
        }
    }

    private def newTunnelZone(hostId: UUID): TunnelZone = {
        createTunnelZone(tzType = TunnelZone.Type.GRE, name = Some("foo"),
                         hosts = Map(hostId -> IPAddr.fromString("192.168.0.1")))
    }

    private def newHost(hostId: UUID, tunnelIds: Set[UUID]): Host = {
        createHost(hostId, tunnelZoneIds = tunnelIds)
    }

    private def createHostAndTunnelZone(): (Host, TunnelZone) = {
        val hostId = UUID.randomUUID
        val tunnelZone = newTunnelZone(hostId)
        val host = createHost(hostId)
        store.multi(Seq(CreateOp(host), CreateOp(tunnelZone)))
        stateStore.addValue(classOf[Host], hostId, AliveKey, AliveKey)
                  .await(timeout)
        (store.get(classOf[Host], hostId).await(), tunnelZone)
    }

    private def createRouterWithPorts(numPorts: Int): Seq[Port] = {
        val r = createRouter()
        val ps = for(_ <- 1 to numPorts)
            yield createRouterPort(routerId = Some(r.getId))
        store.create(r)
        for (p <- ps) store.create(p)
        ps
    }

    private def bindPort(port: Port, host: Host, ifName: String): Port = {
        val updatedPort = port.toBuilder.setHostId(host.getId)
                                        .setInterfaceName(ifName).build()
        store.update(updatedPort)
        updatedPort
    }

    private def unbindPort(port: Port, host: Host): Port = {
        assert(port.getHostId == host.getId)
        val updatedPort = port.toBuilder.clearHostId().build()
        store.update(updatedPort)
        updatedPort
    }
}
