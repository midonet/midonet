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

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import scala.collection.JavaConversions._

import org.midonet.cluster.data.storage.{ObjectSubscription, Storage}
import org.midonet.cluster.models.Commons.IPAddress
import org.midonet.cluster.models.Topology.{TunnelZone => TopologyTunnelZone, Host => TopologyHost}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.FlowController
import org.midonet.midolman.topology.devices.{TunnelZone => SimTunnelZone}
import org.midonet.midolman.topology.{TopologyBuilder, TunnelZoneMapper, VirtualTopology}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator

@RunWith(classOf[JUnitRunner])
class TunnelZoneMapperTest extends MidolmanSpec
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

    feature("A tunnel zone should come with the IPs of its hosts") {
        scenario("One host in the tunnel zone") {
            Given("A tunnel zone with one host")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            val protoTunnel = tuple._2
            val tzMapper = new TunnelZoneMapper(protoTunnel.getId.asJava,
                                                store, vt)

            When("We subscribe to the tunnel zone")
            val tunnelObs = new ObjectSubscription[SimTunnelZone](1)
            tzMapper.observable.subscribe(tunnelObs)

            Then("We obtain a simulation tunnel zone with the host's IPs")
            tunnelObs.await(1, TimeUnit.SECONDS)
            tunnelObs.event match {
                case None => fail("We did not obtain any tunnel zone")
                case Some(simTunnel) =>
                    assertEquals(simTunnel, protoTunnel, Set(protoHost))
            }

            And("The tunnel zone mapper is subscribed to the host")
            tzMapper.subscription(protoHost.getId.asJava) should not be(None)
            And("The tunnel zone mapper is subscribed to the tunnel zone")
            tzMapper.subscription(protoTunnel.getId.asJava) should not be(None)
        }

        scenario("Adding a 2nd host to the tunnel zone") {
            Given("A tunnel zone with two hosts")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            var protoTunnel = tuple._2
            val tzMapper = new TunnelZoneMapper(protoTunnel.getId.asJava,
                                                store, vt)
            val newProtoHost = newHost(UUID.randomUUID(), protoTunnel.getId.asJava,
                                       Set("192.168.0.2"))

            When("We subscribe to the tunnel zone")
            val tunnelObs = new ObjectSubscription[SimTunnelZone](2)
            tzMapper.observable.subscribe(tunnelObs)
            protoTunnel = addHostToTunnel(protoTunnel, newProtoHost)

            Then("We obtain the tunnel zone with the IPs of the two hosts")
            tunnelObs.await(1, TimeUnit.SECONDS)
            tunnelObs.event match {
                case None => fail("We did not obtain any tunnel zone")
                case Some(simTunnel) =>
                    assertEquals(simTunnel, protoTunnel, Set(protoHost,
                                                             newProtoHost))
            }

            And("The tunnel zone mapper is subscribed to the two hosts")
            tzMapper.subscription(protoHost.getId.asJava) should not be(None)
            tzMapper.subscription(newProtoHost.getId.asJava) should not be(None)
        }

        scenario("Removing hosts from the tunnel zone") {
            Given("A tunnel zone with one host")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            var protoTunnel = tuple._2
            val tzMapper = new TunnelZoneMapper(protoTunnel.getId.asJava,
                                                store, vt)

            When("We remove the host from the tunnel zone")
            val tunnelObs = new ObjectSubscription[SimTunnelZone](2)
            tzMapper.observable.subscribe(tunnelObs)
            protoTunnel = removeHostsFromTunnel(protoTunnel)

            Then("The tunnel zone should have an empty set of IPs")
            tunnelObs.await(1, TimeUnit.SECONDS)
            tunnelObs.event match {
                case None => fail("We did not obtain any tunnel zone")
                case Some(simTunnel) =>
                    assertEquals(simTunnel, protoTunnel, Set.empty)
            }

            And("The tunnel zone mapper should not be subscribed to the host")
            tzMapper.subscription(protoHost.getId.asJava) should be(None)
        }

        scenario("2 Hosts and a tunnel zone with one of these hosts") {
            Given("A tunnel zone with one host")
            val tuple = createZoomObjs
            val protoHost = tuple._1
            var protoTunnel = tuple._2
            val tzMapper = new TunnelZoneMapper(protoTunnel.getId.asJava,
                                                store, vt)
            val newProtoHost = newHost(UUID.randomUUID(), protoTunnel.getId.asJava,
                                       Set("192.168.0.2"))
            When("We create a second host that does not belong to the tunnel zone")
            store.create(newProtoHost)

            And("We subscribe to the tunnel zone")
            val tunnelObs = new ObjectSubscription[SimTunnelZone](1)
            tzMapper.observable.subscribe(tunnelObs)

            Then("We obtain the tunnel zone only with the IP of the corresponding host")
            tunnelObs.await(1, TimeUnit.SECONDS)
            tunnelObs.event match {
                case None => fail("We did not obtain any tunnel zone")
                case Some(simTunnel) =>
                    assertEquals(simTunnel, protoTunnel, Set(protoHost))
            }

            And("The tunnel zone mapper is subscribed only to the host it contains")
            tzMapper.subscription(protoHost.getId.asJava) should not be(None)
            tzMapper.subscription(newProtoHost.getId.asJava) should be(None)
        }
    }

    private def assertEquals(simTunnel: SimTunnelZone,
                             protoTunnel: TopologyTunnelZone,
                             protoHosts: Set[TopologyHost]) = {
        simTunnel should not be(null)
        protoTunnel.getId should be(simTunnel.id.asProto)
        protoTunnel.getName should be(simTunnel.name)
        protoTunnel.getHostIdsCount should be(simTunnel.hostIds.size)
        protoTunnel.getHostIdsList.foreach(hostId =>
            simTunnel.hostIds should contain(hostId.asJava)
        )

        val IPs = mutable.Set[IPAddress]()
        protoHosts.foreach(host => IPs ++= host.getAddressesList.toSet)
        IPs should have size(simTunnel.IPs.size)
        IPs.foreach(ip => simTunnel.IPs should contain(IPAddressUtil.toIPAddr(ip)))
    }

    private def newTunnelZone: TopologyTunnelZone = {
        val tunnelId = UUID.randomUUID().asProto
        val hostId = UUID.randomUUID().asProto
        createTunnelZoneBuilder(tunnelId, "foo", Set(hostId)).build()
    }

    private def newHost(hostId: UUID, tunnelId: UUID,
                        IPs: Set[String]): TopologyHost = {
        createHostBuilder(hostId, "toto", IPs, Map(UUID.randomUUID() -> "eth0"),
                          10, Set(tunnelId)).build()
    }

    private def createZoomObjs: (TopologyHost, TopologyTunnelZone) = {
        val protoTunnel = newTunnelZone
        val protoHost = newHost(protoTunnel.getHostIds(0), protoTunnel.getId,
                                Set("192.168.0.1"))
        store.create(protoHost)
        store.create(protoTunnel)
        (protoHost, protoTunnel)
    }

    private def addHostToTunnel(tunnel: TopologyTunnelZone,
                                newHost: TopologyHost): TopologyTunnelZone = {
        val hostIds = mutable.Set[UUID]()
        hostIds.add(newHost.getId.asJava)
        hostIds ++= tunnel.getHostIdsList.map(hostId => hostId.asJava).toSet

        val updatedTunnel = createTunnelZoneBuilder(tunnel.getId, "foo",
                                                    hostIds.toSet).build()
        store.create(newHost)
        store.update(updatedTunnel)
        updatedTunnel
    }

    private def removeHostsFromTunnel(tunnel: TopologyTunnelZone): TopologyTunnelZone = {
        val updatedTunnel = createTunnelZoneBuilder(tunnel.getId, "foo",
                                                    Set.empty).build()
        store.update(updatedTunnel)
        updatedTunnel
    }
}
