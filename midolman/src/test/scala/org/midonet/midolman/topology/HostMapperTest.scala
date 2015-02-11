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
import scala.concurrent.duration.DurationInt

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.storage.StorageWithOwnership
import org.midonet.cluster.models.Topology.{Host, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPAddr
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class HostMapperTest extends MidolmanSpec
                     with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: StorageWithOwnership = _
    private final val timeout = 5 seconds

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).ownershipStore
    }

    private def assertThread(): Unit = {
        assert(vt.threadId == Thread.currentThread.getId)
    }

    feature("A host should come with its tunnel zones membership") {
        scenario("The host is in one tunnel zone") {
            Given("A host member of one tunnel zone")
            val (protoHost, protoTunnelZone) = createHostAndTunnelZone
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1, assertThread())
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
            val hostObs = new AwaitableObserver[SimHost](2, assertThread())
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
            val hostObs = new AwaitableObserver[SimHost](1, assertThread())
            observable.subscribe(hostObs)

            And("Waiting for the host")
            hostObs.await(timeout, 1) shouldBe true

            Then("We remove the host from the tunnel zone")
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
            val hostObs = new AwaitableObserver[SimHost](1, assertThread())
            observable.subscribe(hostObs)

            Then("The host must be notified")
            hostObs.await(timeout, 1) shouldBe true

            When("We delete the host")
            store.delete(classOf[Host], protoHost.getId.asJava,
                         protoHost.getId.asJava.toString)

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
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt)

            And("An observable on the host mapper")
            val observable = Observable.create(hostMapper)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1, assertThread())
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
        protoHost.getPortBindingsCount shouldBe simHost.portBindings.size
        protoHost.getPortBindingsList.foreach(binding => {
            val portId = binding.getPortId
            val interface = binding.getInterfaceName
            simHost.portBindings.get(portId) shouldBe Some(interface)
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

    /**
     * This method sets the host alive status by adding/deleting the host
     * owner respectively. Note that the host ownership type is exclusive.
     */
    private def setHostAliveStatus(hostId: UUID, alive: Boolean) = {
        if (alive)
            store.updateOwner(classOf[Host], hostId.asProto, hostId.toString,
                              throwIfExists = true)
        else
            store.deleteOwner(classOf[Host], hostId.asProto, hostId.toString)
    }

    private def addTunnelZoneToHost(protoHost: Host): (Host, TunnelZone) = {

        val newProtoTunnelZone = newTunnelZone(protoHost.getId.asJava)
        store.create(newProtoTunnelZone)
        val oldTZId = protoHost.getTunnelZoneIds(0).asJava
        val newTZId = newProtoTunnelZone.getId.asJava
        val updatedHost = newHost(protoHost.getId,
                                  Set(oldTZId, newTZId))
        store.update(updatedHost, updatedHost.getId.asJava.toString,
                     validator = null)
        (updatedHost, newProtoTunnelZone)
    }

    private def removeHostFromAllTunnelZones(protoHost: Host): Host = {

        val updatedHost = newHost(protoHost.getId.asJava, Set[UUID]())
        store.update(updatedHost, protoHost.getId.asJava.toString,
                     validator = null)
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
        store.create(protoTunnelZone)
        store.create(protoHost, hostId.toString)
        (protoHost, protoTunnelZone)
    }
}
