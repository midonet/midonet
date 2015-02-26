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

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{CreateOp, Storage}
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPAddr
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class HostMapperTest extends MidolmanSpec
                     with TopologyBuilder {

    private var vt: VirtualTopology = _
    private implicit var store: Storage = _
    private final val timeout = 5 seconds

    registerActors(FlowController -> (() => new FlowController))

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
    }

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        super.fillConfig(config)

        // Tests to cover the cases when the new cluster is disabled are
        // present in VirtualToPhysicalMapperTest
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config
    }

    feature("A host should come with its tunnel zones membership") {
        scenario("The host is in one tunnel zone") {
            Given("A host member of one tunnel zone")
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            observable.subscribe(hostObs)

            Then("We obtain a simulation host with the host's tunnel zone membership")
            hostObs.await(timeout, 0) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost, Set(protoTunnelZone))

            And("The host mapper is observing the tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe true
        }

        scenario("The host is in one tunnel zone and then in two") {
            Given("A host member of one tunnel zone")
            val (protoHost1, protoTunnelZone1) = createHostAndTunnelZone
            val hostMapper = new HostMapper(protoHost1.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            observable.subscribe(hostObs)

            And("Add a 2nd tunnel zone the host is a member of")
            val (protoHost2, protoTunnelZone2) = addTunnelZoneToHost(protoHost1)

            Then("We obtain a simulation host with the host's tunnel zone membership")
            hostObs.await(timeout, 0) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost2,
                         Set(protoTunnelZone1, protoTunnelZone2))

            And("The host mapper is observing the two tunnel zones")
            hostMapper.isObservingTunnel(protoTunnelZone1.getId) shouldBe true
            hostMapper.isObservingTunnel(protoTunnelZone2.getId) shouldBe true
        }

        scenario("The host is in one tunnel zone and then none") {
            Given("A host member of one tunnel zone")
            val (protoHost1, protoTunnelZone) = createHostAndTunnelZone
            val hostMapper = new HostMapper(protoHost1.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            observable.subscribe(hostObs)

            And("We remove the host from the tunnel zone")
            val protoHost2 = removeHostFromAllTunnelZones(protoHost1)

            Then("We obtain a simulation host that does not belong to any tunnel zones")
            hostObs.await(timeout, 0) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost2, Set.empty)

            And("The host mapper is not observing the removed tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe false
        }
    }

    feature("The host mapper handles deletion of hosts appropriately") {
        scenario("Deleting the host") {
            Given("A host member of one tunnel zone")
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            observable.subscribe(hostObs)

            Then("The host must be notified")
            hostObs.await(timeout, 1) shouldBe true

            When("We delete the host")
            store.delete(classOf[Host], protoHost.getId.asJava)

            Then("We obtain a simulation host with an onComplete notification")
            hostObs.await(timeout, 0) shouldBe true
            hostObs.getOnCompletedEvents should have size 1

            And("The host mapper is not observing the tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe false
        }
    }

    feature("The host mapper handles the alive status of the host correctly") {
        scenario("The alive status of the host is updated appropriately") {
            Given("An alive host")
            val (protoHost, _) = createHostAndTunnelZone
            setHostAliveStatus(protoHost.getId, alive = true)
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            observable.subscribe(hostObs)

            Then("We obtain a host that is alive")
            hostObs.await(timeout, 1) shouldBe true
            var simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true

            And("When we set the alive status to false")
            setHostAliveStatus(protoHost.getId, alive = false)

            Then("We obtain the host with an alive status set to false")
            hostObs.await(timeout, 1) shouldBe true
            simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe false

            And("When we set the alive status of the host back to true")
            setHostAliveStatus(protoHost.getId, alive = true)

            Then("We obtain an alive host")
            hostObs.await(timeout, 0)
            simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true
        }
    }

    private def assertEquals(simHost: SimHost, protoHost: Host,
                             tunnelZones: Set[TunnelZone]) = {
        protoHost.getId.asJava shouldBe simHost.id
        protoHost.getPortInterfaceMappingCount shouldBe simHost.bindings.size
        protoHost.getPortInterfaceMappingList.foreach(portInterface => {
            val portId = portInterface.getPortId
            val interface = portInterface.getInterfaceName
            simHost.bindings.get(portId) shouldBe Some(interface)
        })
        protoHost.getTunnelZoneIdsCount shouldBe simHost.tunnelZoneIds.size
        protoHost.getTunnelZoneIdsList.foreach(tunnelId =>
            simHost.tunnelZoneIds should contain(tunnelId.asJava)
        )
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

    private def setHostAliveStatus(hostId: UUID, alive: Boolean) = {
        val hostZkManager = injector.getInstance(classOf[HostZkManager])
        hostZkManager.ensureHostPathExists(hostId)

        if (alive) hostZkManager.makeAlive(hostId)
        else hostZkManager.makeNotAlive(hostId)
    }

    private def addTunnelZoneToHost(protoHost: Host): (Host, TunnelZone) = {

        val newProtoTunnelZone = newTunnelZone(protoHost.getId.asJava)
        store.create(newProtoTunnelZone)
        Await.result(store.get(classOf[Host], protoHost.getId), timeout)
        val oldTZId = protoHost.getTunnelZoneIds(0).asJava
        val newTZId = newProtoTunnelZone.getId.asJava
        val updatedHost = newHost(protoHost.getId,
                                  Set(oldTZId, newTZId))
        store.update(updatedHost)
        Await.result(store.get(classOf[Host], protoHost.getId), timeout)

        (updatedHost, newProtoTunnelZone)
    }

    private def removeHostFromAllTunnelZones(protoHost: Host): Host = {

        val updatedHost = newHost(protoHost.getId.asJava, Set[UUID]())
        store.update(updatedHost)
        updatedHost
    }

    private def newTunnelZone(hostId: UUID): TunnelZone = {
        createTunnelZone(tzType = TunnelZone.Type.GRE, name = Some("foo"),
                         hosts = Map(hostId -> IPAddr.fromString("192.168.0.1")))
    }

    private def newHost(hostId: UUID, tunnelIds: Set[UUID]): Host = {
        createHost(hostId, Map(UUID.randomUUID() -> "eth0"), tunnelIds)
    }

    private def createHostAndTunnelZone: (Host, TunnelZone) = {
        val hostId = UUID.randomUUID()
        val protoTunnelZone = newTunnelZone(hostId)
        val protoHost = newHost(hostId, Set(protoTunnelZone.getId.asJava))
        store.multi(Seq(CreateOp(protoTunnelZone), CreateOp(protoHost)))
        Await.result(store.get(classOf[Host], protoHost.getId), timeout)
        Await.result(store.get(classOf[TunnelZone], protoTunnelZone.getId),
                     timeout)
        (protoHost, protoTunnelZone)
    }
}
