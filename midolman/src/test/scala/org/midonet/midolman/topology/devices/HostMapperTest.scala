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

package org.midonet.midolman.topology.devices

import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.topology.{HostMapper, TopologyBuilder, VirtualTopology}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.IPAddr
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class HostMapperTest extends MidolmanSpec
                     with TopologyBuilder {

    private var vt: VirtualTopology = _
    private implicit var store: Storage = _
    private var dataClient: DataClient = _

    registerActors(FlowController -> (() => new FlowController
                                                with MessageAccumulator))

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
        List(classOf[Topology.Host], classOf[Topology.TunnelZone]).foreach(clazz =>
            store.registerClass(clazz)
        )
        dataClient = clusterDataClient()
    }

    feature("A host should come with its tunnel zones membership") {
        scenario("The host is in one tunnel zone") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            hostMapper.observable.subscribe(hostObs)

            Then("We obtain a simulation host with the host's tunnel membership")
            hostObs.await(1.second, 0)
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, protoHost, Set(protoTunnel))

            And("The host mapper is subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) should not be(None)
            And("The host mapper is subscribed to the tunnel zone")
            hostMapper.subscription(protoTunnel.getId.asJava) should not be(None)
        }

        scenario("The host is in one tunnel zone and then in two") {
            Given("A host member of one tunnel zone")
            var tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("Add a 2nd tunnel zone the host is a member of")
            tuple = addTunnelToHost(protoHost)
            val updatedProtoHost = tuple._1
            val newProtoTunnel = tuple._2

            Then("We obtain a simulation host with the host's tunnel membership")
            hostObs.await(1.second, 0)
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, updatedProtoHost, Set(protoTunnel, newProtoTunnel))

            And("The host mapper is subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) should not be(None)

            And("The host mapper is subscribed to the two tunnel zones")
            hostMapper.subscription(protoTunnel.getId.asJava) should not be(None)
            hostMapper.subscription(newProtoTunnel.getId.asJava) should not be(None)
        }

        scenario("The host is in one tunnel zone and then none") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("We remove the host from the tunnel zone")
            val updatedProtoHost = removeHostFromAllTunnels(protoHost)

            Then("We obtain a simulation host that does not belong to any tunnels")
            hostObs.await(1.second, 0)
            val simHost = hostObs.getOnNextEvents.last
            assertEquals(simHost, updatedProtoHost, Set.empty)

            And("The host mapper is subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) should not be(None)
            And("The host mapper is not subscribed to the removed tunnel zone")
            hostMapper.subscription(protoTunnel.getId.asJava) shouldBe None
        }
    }

    feature("The host mapper handles deletion of hosts appropriately") {
        scenario("Deleting the host") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, vt, dataClient)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("We delete the host")
            store.delete(classOf[Topology.Host], protoHost.getId.asJava)

            Then("We obtain a simulation host with an onComplete notification")
            hostObs.await(1.second, 0)
            hostObs.getOnCompletedEvents should have size 1

            And("The host mapper is not subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) shouldBe None
            And("The host mapper is not subscribed to the tunnel zone")
            hostMapper.subscription(protoTunnel.getId.asJava) shouldBe None
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
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            Then("We obtain a host that is alive")
            hostObs.await(1.second, 1)
            var simHost = hostObs.getOnNextEvents.last
            simHost.alive shouldBe true

            And("When we set the alive status to false")
            setHostAliveStatus(protoHost.getId, false /* alive? */)

            Then("We obtain the host with an alive status set to false")
            hostObs.await(1.second, 1)
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

    private def assertEquals(simHost: SimHost, protoHost: Topology.Host,
                             tunnels: Set[Topology.TunnelZone]) = {
        protoHost.getId.asJava shouldBe simHost.id
        protoHost.getName shouldBe simHost.name
        protoHost.getAddressesCount shouldBe  simHost.addresses.size
        protoHost.getAddressesList.foreach(ip =>
            simHost.addresses should contain(IPAddressUtil.toIPAddr(ip))
        )
        protoHost.getPortInterfaceMappingCount shouldBe simHost.portInterfaceMapping.size
        protoHost.getPortInterfaceMappingList.foreach(portInterface => {
            val portId = portInterface.getPortId
            val interface = portInterface.getInterfaceName
            simHost.portInterfaceMapping.get(portId) shouldBe Some(interface)
        })
        protoHost.getFloodingProxyWeight shouldBe simHost.floodingProxyWeight
        protoHost.getTunnelZoneIdsCount shouldBe simHost.tunnelZoneIds.size
        protoHost.getTunnelZoneIdsList.foreach(tunnelId =>
            simHost.tunnelZoneIds should contain(tunnelId.asJava)
        )
        var membershipSize = 0
        for (tunnel <- tunnels) {
            tunnel.getHostsList.foreach(hostToIp => {
                val hostId = hostToIp.getHostId
                if (hostId.asJava.equals(simHost.id)) {
                    val ip = IPAddressUtil.toIPAddr(hostToIp.getIp)
                    simHost.tunnelZones.get(tunnel.getId.asJava).get shouldBe ip
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

    private def addTunnelToHost(protoHost: Topology.Host)
        : (Topology.Host, Topology.TunnelZone) = {

        val newProtoTunnel = newTunnelZone(protoHost.getId.asJava)
        store.create(newProtoTunnel)
        val oldTunnelId = protoHost.getTunnelZoneIds(0).asJava
        val newTunnelId = newProtoTunnel.getId.asJava
        val updatedHost = newHost(protoHost.getId.asJava,
                                  Set(oldTunnelId, newTunnelId),
                                  Set("192.168.0.1"))
        store.update(updatedHost)
        (updatedHost, newProtoTunnel)
    }

    private def removeHostFromAllTunnels(protoHost: Topology.Host): Topology.Host = {

        val updatedHost = newHost(protoHost.getId.asJava, Set[UUID](),
                                  Set("192.168.0.1"))
        store.update(updatedHost)
        updatedHost
    }

    private def newTunnelZone(hostId: UUID): Topology.TunnelZone = {
        val tunnelId = UUID.randomUUID().asProto
        createTunnelZoneBuilder(tunnelId, "foo",
                                Map(hostId -> IPAddr.fromString("192.168.0.1")))
                                .build()
    }

    private def newHost(hostId: UUID, tunnelIds: Set[UUID],
                        IPs: Set[String]): Topology.Host = {
        createHostBuilder(hostId, "toto", IPs, Map(UUID.randomUUID() -> "eth0"),
                          10, tunnelIds).build()
    }

    private def createZoomObjs: (Topology.Host, Topology.TunnelZone) = {
        val hostId = UUID.randomUUID()
        val protoTunnel = newTunnelZone(hostId)
        val protoHost = newHost(hostId, Set(protoTunnel.getId.asJava),
                                Set("192.168.0.1"))
        store.create(protoHost)
        store.create(protoTunnel)
        (protoHost, protoTunnel)
    }
}
