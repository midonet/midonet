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

import org.scalatest.junit.JUnitRunner

import org.junit.runner.RunWith
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.Storage
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
    private var dataClient: DataClient = _

    registerActors(FlowController -> (() => new FlowController))

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
        List(classOf[Host], classOf[TunnelZone]).foreach(clazz =>
            store.registerClass(clazz)
        )
        dataClient = clusterDataClient()
    }

    feature("A host should come with its tunnel zones membership") {
        scenario("The host is in one tunnel zone") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnelZone = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            hostMapper.observable.subscribe(hostObs)

            Then("We obtain a simulation host with the host's tunnel zone membership")
            hostObs.await(1.second, 0) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost, Set(protoTunnelZone))

            And("The host mapper is observing the tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe true
        }

        scenario("The host is in one tunnel zone and then in two") {
            Given("A host member of one tunnel zone")
            var tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnelZone = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("Add a 2nd tunnel zone the host is a member of")
            tuple = addTunnelZoneToHost(protoHost)
            val updatedProtoHost = tuple._1
            val newProtoTunnelZone = tuple._2

            Then("We obtain a simulation host with the host's tunnel zone membership")
            hostObs.await(1.second, 0) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, updatedProtoHost, Set(protoTunnelZone,
                                                        newProtoTunnelZone))

            And("The host mapper is observing the two tunnel zones")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe true
            hostMapper.isObservingTunnel(newProtoTunnelZone.getId) shouldBe true
        }

        scenario("The host is in one tunnel zone and then none") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnelZone = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("We remove the host from the tunnel zone")
            val updatedProtoHost = removeHostFromAllTunnelZones(protoHost)

            Then("We obtain a simulation host that does not belong to any tunnel zones")
            hostObs.await(1.second, 0) shouldBe true
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, updatedProtoHost, Set.empty)

            And("The host mapper is not observing the removed tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe false
        }
    }

    feature("The host mapper handles deletion of hosts appropriately") {
        scenario("Deleting the host") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnelZone = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("We delete the host")
            store.delete(classOf[Host], protoHost.getId.asJava)

            Then("We obtain a simulation host with an onComplete notification")
            hostObs.await(1.second, 0) shouldBe true
            hostObs.getOnCompletedEvents should have size 1

            And("The host mapper is not observing the tunnel zone")
            hostMapper.isObservingTunnel(protoTunnelZone.getId) shouldBe false
        }
    }

    feature("The host mapper handles the alive status of the host correctly") {
        scenario("The alive status of the host is updated appropriately") {
            Given("An alive host")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            setHostAliveStatus(protoHost.getId, true /* alive? */)
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)
            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            hostMapper.observable.subscribe(hostObs)

            Then("We obtain a host that is alive")
            hostObs.await(1.second, 1) shouldBe true
            var simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true

            And("When we set the alive status to false")
            setHostAliveStatus(protoHost.getId, false /* alive? */)

            Then("We obtain the host with an alive status set to false")
            hostObs.await(1.second, 1) shouldBe true
            simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe false

            And("When we set the alive status of the host back to true")
            setHostAliveStatus(protoHost.getId, true /* alive? */)

            Then("We obtain an alive host")
            hostObs.await(1.second, 0)
            simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true
        }
    }

    private def assertEquals(simHost: SimHost, protoHost: Host,
                             tunnelZones: Set[TunnelZone]) = {
        protoHost.getId.asJava shouldBe simHost.id
        protoHost.getPortInterfaceMappingCount shouldBe simHost.portInterfaceMapping.size
        protoHost.getPortInterfaceMappingList.foreach(portInterface => {
            val portId = portInterface.getPortId
            val interface = portInterface.getInterfaceName
            simHost.portInterfaceMapping.get(portId) shouldBe Some(interface)
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
        simHost.tunnelZones should have size membershipSize
    }

    private def setHostAliveStatus(hostId: UUID, alive: Boolean) = {
        val hostZkManager = injector.getInstance(classOf[HostZkManager])
        hostZkManager.ensureHostPathExists(hostId)

        alive match {
            case true => hostZkManager.makeAlive(hostId)
            case false => hostZkManager.makeNotAlive(hostId)
        }
    }

    private def addTunnelZoneToHost(protoHost: Host): (Host, TunnelZone) = {

        val newProtoTunnelZone = newTunnelZone(protoHost.getId.asJava)
        store.create(newProtoTunnelZone)
        val oldTZId = protoHost.getTunnelZoneIds(0).asJava
        val newTZId = newProtoTunnelZone.getId.asJava
        val updatedHost = newHost(protoHost.getId,
                                  Set(oldTZId, newTZId))
        store.update(updatedHost)
        (updatedHost, newProtoTunnelZone)
    }

    private def removeHostFromAllTunnelZones(protoHost: Host): Host = {

        val updatedHost = newHost(protoHost.getId.asJava, Set[UUID]())
        store.update(updatedHost)
        updatedHost
    }

    private def newTunnelZone(hostId: UUID): TunnelZone = {
        val tunnelId = UUID.randomUUID()
        createTunnelZoneBuilder(tunnelId, "foo",
                                Map(hostId -> IPAddr.fromString("192.168.0.1")))
                                .build()
    }

    private def newHost(hostId: UUID, tunnelIds: Set[UUID]): Host = {
        createHostBuilder(hostId, Map(UUID.randomUUID() -> "eth0"), tunnelIds).build()
    }

    private def createZoomObjs: (Host, TunnelZone) = {
        val hostId = UUID.randomUUID()
        val protoTunnelZone = newTunnelZone(hostId)
        val protoHost = newHost(hostId, Set(protoTunnelZone.getId.asJava))
        store.create(protoTunnelZone)
        store.create(protoHost)
        (protoHost, protoTunnelZone)
    }
}
