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
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.IPAddress
import org.midonet.cluster.models.Topology.{TunnelZone => TopologyTunnelZone, Host => TopologyHost, PortToInterface}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.topology.{HostMapper, TunnelZoneMapper, VirtualTopology, TopologyBuilder}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.IPAddr
import org.midonet.util.concurrent.ReactiveActor.OnCompleted
import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.reactivex.AwaitableObserver.OnNext

@RunWith(classOf[JUnitRunner])
class HostMapperTest extends MidolmanSpec
                     with TopologyBuilder
                     with Matchers {

    private val oneSecond = new FiniteDuration(1, TimeUnit.SECONDS)
    private var vt: VirtualTopology = _
    private implicit var store: Storage = _

    registerActors(FlowController -> (() => new FlowController
                                                with MessageAccumulator))

    protected override def beforeTest() = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
    }

    feature("A host should come with its tunnel zones membership") {
        scenario("The host is in one tunnel zone") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, store, vt)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](1)
            hostMapper.observable.subscribe(hostObs)

            Then("We obtain a simulation host with the host's tunnel membership")
            hostObs.await(oneSecond, 0)
            val simHost = hostObs.list.get(0).get.asInstanceOf[OnNext[SimHost]].value
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
            val hostMapper = new HostMapper(protoHost.getId.asJava, store, vt)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("Add a 2nd tunnel zone the host is a member of")
            tuple = addTunnelToHost(protoHost)
            val updatedProtoHost = tuple._1
            val newProtoTunnel = tuple._2

            Then("We obtain a simulation host with the host's tunnel membership")
            hostObs.await(oneSecond, 0)
            val simHost = hostObs.list.get(1).get.asInstanceOf[OnNext[SimHost]].value
            assertEquals(simHost, updatedProtoHost, Set(protoTunnel, newProtoTunnel))

            And("The host mapper is subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) should not be(None)

            And("The host mapper is subscribed to the two tunnel zones")
            hostMapper.subscription(protoTunnel.getId.asJava) should not be(None)
            hostMapper.subscription(newProtoTunnel.getId.asJava) should not be(None)
        }

        scenario("The host is one tunnel zone and then none") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, store, vt)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("We remove the host from the tunnel zone")
            val updatedProtoHost = removeHostFromAllTunnels(protoHost)

            Then("We obtain a simulation host that does not belong to any tunnels")
            hostObs.await(oneSecond, 0)
            val simHost = hostObs.list.get(1).get.asInstanceOf[OnNext[SimHost]].value
            assertEquals(simHost, updatedProtoHost, Set.empty)

            And("The host mapper is subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) should not be(None)
            And("The host mapper is not subscribed to the removed tunnel zone")
            hostMapper.subscription(protoTunnel.getId.asJava) should be(None)
        }
    }

    feature("The tunnel zone mapper handles deletion of hosts appropriately") {
        scenario("Deleting the host") {
            Given("A host member of one tunnel zone")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val hostMapper = new HostMapper(protoHost.getId.asJava, store, vt)

            When("We subscribe to the host")
            val hostObs = new AwaitableObserver[SimHost](2)
            hostMapper.observable.subscribe(hostObs)

            And("We delete the host")
            store.delete(classOf[TopologyHost], protoHost.getId.asJava)

            Then("We obtain a simulation host with an onComplete notification")
            hostObs.await(oneSecond, 0)
            val notification = hostObs.list.get(1).get
            assert(notification.getClass == classOf[AwaitableObserver.OnCompleted])

            And("The host mapper is not subscribed to the host")
            hostMapper.subscription(protoHost.getId.asJava) should be(None)
            And("The host mapper is not subscribed to the tunnel zone")
            hostMapper.subscription(protoTunnel.getId.asJava) should be(None)
        }
    }

    private def assertEquals(simHost: SimHost, protoHost: TopologyHost,
                             tunnels: Set[TopologyTunnelZone]) = {
        protoHost.getId.asJava should be(simHost.id)
        protoHost.getName should be(simHost.name)
        protoHost.getAddressesCount should be(simHost.addresses.size)
        protoHost.getAddressesList.foreach(ip =>
            simHost.addresses should contain(IPAddressUtil.toIPAddr(ip))
        )
        protoHost.getPortInterfaceMappingCount should be(simHost.portInterfaceMapping.size)
        protoHost.getPortInterfaceMappingList.foreach(portInterface => {
            val portId = portInterface.getPortId
            val interface = portInterface.getInterfaceName
            simHost.portInterfaceMapping.get(portId) should be(interface)
        })
        protoHost.getFloodingProxyWeight should be(simHost.floodingProxyWeight)
        protoHost.getTunnelZoneIdsCount should be(simHost.tunnelZoneIds.size)
        protoHost.getTunnelZoneIdsList.foreach(tunnelId =>
            simHost.tunnelZoneIds should contain(tunnelId.asJava)
        )
        var membershipSize = 0
        for (tunnel <- tunnels) {
            tunnel.getHostsList.foreach(hostToIp => {
                val hostId = hostToIp.getHostId
                if (hostId.asJava.equals(simHost.id)) {
                    val ip = IPAddressUtil.toIPAddr(hostToIp.getIp)
                    simHost.tunnelZones.get(tunnel.getId.asJava).get should be(ip)
                    membershipSize += 1
                }
            })
        }
        simHost.tunnelZones.size should be(membershipSize)
    }

    private def addTunnelToHost(protoHost: TopologyHost):
        (TopologyHost, TopologyTunnelZone) = {

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

    private def removeHostFromAllTunnels(protoHost: TopologyHost):
        TopologyHost = {

        val updatedHost = newHost(protoHost.getId.asJava, Set[UUID](),
                                  Set("192.168.0.1"))
        store.update(updatedHost)
        updatedHost
    }

    private def newTunnelZone(hostId: UUID): TopologyTunnelZone = {
        val tunnelId = UUID.randomUUID().asProto
        createTunnelZoneBuilder(tunnelId, "foo",
                                Map(hostId -> IPAddr.fromString("192.168.0.1")))
                                .build()
    }

    private def newHost(hostId: UUID, tunnelIds: Set[UUID],
                        IPs: Set[String]): TopologyHost = {
        createHostBuilder(hostId, "toto", IPs, Map(UUID.randomUUID() -> "eth0"),
                          10, tunnelIds).build()
    }

    private def createZoomObjs: (TopologyHost, TopologyTunnelZone) = {
        val hostId = UUID.randomUUID()
        val protoTunnel = newTunnelZone(hostId)
        val protoHost = newHost(hostId, Set(protoTunnel.getId.asJava),
                                Set("192.168.0.1"))
        store.create(protoHost)
        store.create(protoTunnel)
        (protoHost, protoTunnel)
    }
}
