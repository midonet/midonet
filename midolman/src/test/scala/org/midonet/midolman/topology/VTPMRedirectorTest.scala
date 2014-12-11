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
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.DurationLong

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.TunnelZone.HostConfig
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.{TunnelZone => OldTunnel, ZoomConvert}
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{Host => ProtoHost, TunnelZone => ProtoTunnelZone}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.{IPAddr, IPv4Addr}

@RunWith(classOf[JUnitRunner])
class VTPMRedirectorTest extends TestKit(ActorSystem("VTPMRedirectorTest"))
                         with MidolmanSpec
                         with ImplicitSender
                         with TopologyBuilder {

    private var vtpm: TestableVTPM = _
    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var config: ZookeeperConfig = _

    private class TestableVTPM extends VirtualToPhysicalMapper with MessageAccumulator

    registerActors(VirtualToPhysicalMapper -> (() => new TestableVTPM))

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        super.fillConfig(config)

        // Tests to cover the cases when the new cluster is disabled are
        // present in VirtualToPhysicalMapperTest
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config
    }

    override def beforeTest() {
        vt = injector.getInstance(classOf[VirtualTopology])
        vtpm = VirtualToPhysicalMapper.as[TestableVTPM]
        store = injector.getInstance(classOf[Storage])
        config = injector.getInstance(classOf[ZookeeperConfig])
    }

    private def buildAndStoreTunnelZone(hostId: UUID, hostIp: IPAddr)
        : ProtoTunnelZone = {

        val newTz = createTunnelZoneBuilder(UUID.randomUUID(), "tunnel",
                                                Map(hostId -> hostIp))
            .setType(ProtoTunnelZone.Type.VTEP)
            .build()
        store.create(newTz)
        newTz
    }

    private def buildAndStoreHost: ProtoHost = {
        val protoHost = createHostBuilder(UUID.randomUUID(),
                                          Map(UUID.randomUUID() -> "eth0"),
                                          Set.empty).build()

        store.create(protoHost)
        protoHost
    }

    private def fromHostToIpList(hosts: List[HostToIp]): mutable.Buffer[(UUID, IPAddr)] =
        hosts.map(hostToIp =>
            (hostToIp.getHostId.asJava, IPAddressUtil.toIPAddr(hostToIp.getIp))
        ).toBuffer

    private def addHostToTunnel(tunnel: ProtoTunnelZone, hostId: UUID,
                                hostIp: IPAddr): ProtoTunnelZone = {

        val hosts = fromHostToIpList(tunnel.getHostsList.toList)
        hosts.add((hostId, hostIp))

        val updatedTunnel = createTunnelZoneBuilder(tunnel.getId, "tunnel", hosts.toMap)
            .setType(tunnel.getType)
            .build()
        store.update(updatedTunnel)
        updatedTunnel
    }

    private def addTunnelToHost(host: ProtoHost, tzId: UUID): ProtoHost = {
        val hosts = host.getPortInterfaceMappingList.map(portToInterface =>
            (portToInterface.getPortId.asJava, portToInterface.getInterfaceName)
        ).toMap
        val updatedHost = createHostBuilder(host.getId, hosts, Set(tzId)).build()

        store.update(updatedHost)
        updatedHost
    }

    private def removeHostFromTz(tunnelZone: ProtoTunnelZone, hostId: UUID) = {
        val filteredHosts = tunnelZone.getHostsList
            .filterNot(_.getHostId.equals(hostId.asProto))
        val hosts = fromHostToIpList(filteredHosts.toList)
        val updatedTunnel = createTunnelZoneBuilder(tunnelZone.getId, "tunnel",
                                                    hosts.toMap)
            .setType(tunnelZone.getType)
            .build()

        store.update(updatedTunnel)
    }

    feature("Obtaining tunnel zones from the VTPMRedirector") {
        scenario("Sending a TunnelZoneRequest") {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPAddr.fromString("192.168.0.1")
            val tunnel = buildAndStoreTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We send a tunnel zone request")
            vtpm.self.tell(TunnelZoneRequest(tunnelId), testActor)

            Then("We receive the tunnel zone with its members")
            val hosts = Set(new HostConfig(hostId)
                                .setIp(hostIp.asInstanceOf[IPv4Addr]))
            expectMsg(ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts))

            And("When we update the tunnel zone")
            val newHostId = UUID.randomUUID()
            val newHostIp = IPAddr.fromString("192.168.0.2")
            val updatedTunnel = addHostToTunnel(tunnel, newHostId, newHostIp)

            Then("We receive the updated tunnel zone")
            val addedHost = new HostConfig(newHostId)
                                .setIp(newHostIp.asInstanceOf[IPv4Addr])
            expectMsg(ZoneChanged(tunnelId, OldTunnel.Type.vtep, addedHost,
                                  HostConfigOperation.Added))

            And("When we remove one host from the tunnel zone")
            removeHostFromTz(updatedTunnel, newHostId)

            Then("We receive the updated tunnel zone")
            val removedHost = new HostConfig(newHostId)
                                  .setIp(newHostIp.asInstanceOf[IPv4Addr])
            expectMsg(ZoneChanged(tunnelId, OldTunnel.Type.vtep, removedHost,
                                  HostConfigOperation.Deleted))
        }

        scenario("Unsubscribing from the tunnel zone") {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPAddr.fromString("192.168.0.1")
            val tunnel = buildAndStoreTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We send a tunnel zone request")
            vtpm.self.tell(TunnelZoneRequest(tunnelId), testActor)

            Then("We receive the tunnel zone with its members")
            val hosts = Set(new HostConfig(hostId)
                                .setIp(hostIp.asInstanceOf[IPv4Addr]))
            expectMsg(ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts))

            And("When we unsubscribe from the tunnel zone")
            try {
                Then("No exceptions should be raised")
                vtpm.self.tell(TunnelZoneUnsubscribe(tunnelId), testActor)
            } catch {
                case e: Exception => fail("Unsubscribing from a tunnel zone should" +
                                          s" not throw exception: $e")
            }
        }

        scenario("Requesting a tunnel zone with tryAsk")   {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPAddr.fromString("192.168.0.1")
            val tunnel = buildAndStoreTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We request the tunnel zone with tryAsk")
            val nye = intercept[NotYetException] {
                VirtualToPhysicalMapper
                    .tryAsk[ZoneMembers](TunnelZoneRequest(tunnelId))(actorSystem)
            }

            Then("We receive the tunnel zone")
            val zoneMembers = Await.result(nye.waitFor, 1.seconds)
                .asInstanceOf[ZoneMembers]
            val hosts = Set(new HostConfig(hostId)
                                .setIp(hostIp.asInstanceOf[IPv4Addr]))
            zoneMembers shouldBe ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts)
        }
    }

    feature("Obtaining Hosts from the VTPMRedirector") {
        scenario("Sending a host request") {
            Given("A topology with one host")
            val protoHost = buildAndStoreHost

            When("We send a host request")
            vtpm.self.tell(HostRequest(protoHost.getId), testActor)

            Then("We receive the corresponding host")
            val simHost = ZoomConvert.fromProto(protoHost, classOf[SimHost])
            expectMsg(simHost.toOldHost)

            And("When we add the host to a tunnel zone")
            val protoTz = buildAndStoreTunnelZone(protoHost.getId, IPAddr.fromString("192.168.0.1"))
            val updatedHost = addTunnelToHost(protoHost, protoTz.getId)

            Then("We obtain the updated host")
            val updatedSimHost = ZoomConvert.fromProto(updatedHost, classOf[SimHost])
            updatedSimHost.tunnelZones = Map(protoTz.getId.asJava -> IPAddr.fromString("192.168.0.1"))
            expectMsg(updatedSimHost.toOldHost)

            And("When we remove the host form the tunnel zone")
            store.update(protoHost)
            Then("We receive the host without any tunnel zones")
            expectMsg(simHost.toOldHost)
        }

        scenario("Unsubscribing from the Host") {
            Given("A topology with one host")
            val protoHost = buildAndStoreHost

            When("We send a host request")
            vtpm.self.tell(HostRequest(protoHost.getId), testActor)

            Then("We receive the corresponding host")
            val simHost = ZoomConvert.fromProto(protoHost, classOf[SimHost])
            expectMsg(simHost.toOldHost)

            And("When we unsubscribe from the host")
            try {
                Then("No exceptions should be raised")
                vtpm.self.tell(HostUnsubscribe(simHost.id), testActor)
            } catch {
                case e: Exception => fail("Unsubscribing from a host should" +
                                          s" not throw exception: $e")
            }
        }

        scenario("Requesting a host with TryAsk") {
            Given("A topology with one host")
            val protoHost = buildAndStoreHost

            When("We request the tunnel zone with tryAsk")
            val hostRequest = HostRequest(protoHost.getId, update=false)
            val nye = intercept[NotYetException] {
                VirtualToPhysicalMapper.tryAsk[rcu.Host](hostRequest)(actorSystem)
            }

            Then("We received the requested host")
            val receivedHost = Await.result(nye.waitFor, 1.seconds)
                .asInstanceOf[rcu.Host]
            val expectedHost = ZoomConvert.fromProto(protoHost, classOf[SimHost])
                .toOldHost
            receivedHost shouldBe expectedHost
        }
    }
}