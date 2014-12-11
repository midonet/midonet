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
import scala.concurrent.duration.DurationLong

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.TunnelZone.HostConfig
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.data.{TunnelZone => OldTunnel}
import org.midonet.cluster.models.Commons.IPAddress
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{TunnelZone => ProtoTunnelZone}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.topology.VirtualToPhysicalMapper.{TunnelZoneRequest, TunnelZoneUnsubscribe, ZoneChanged, ZoneMembers}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator

@RunWith(classOf[JUnitRunner])
class VTPMRedirectorTest extends TestKit(ActorSystem("VTPMRedirectorTest"))
                         with MidolmanSpec
                         with ImplicitSender {

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

    private def createHostToIp(hostId: UUID, hostIp: IPAddress): HostToIp = {
        val hostToIp = HostToIp.newBuilder()
            .setHostId(hostId)
            .setIp(hostIp)
            .build()

        hostToIp
    }

    private def createTunnelZone(hostId: UUID, hostIp: IPAddress): ProtoTunnelZone = {
        val hostToIp = createHostToIp(hostId, hostIp)
        val newTunnel = ProtoTunnelZone.newBuilder()
            .setId(UUID.randomUUID())
            .setType(ProtoTunnelZone.Type.VTEP)
            .addHosts(hostToIp)
            .build()
        store.create(newTunnel)
        newTunnel
    }

    private def addHostToTunnel(tunnel: ProtoTunnelZone, hostId: UUID,
                                hostIp: IPAddress): ProtoTunnelZone = {

        val newHostToIp = createHostToIp(hostId, hostIp)
        val updatedTunnel = ProtoTunnelZone.newBuilder()
            .setId(tunnel.getId)
            .setType(tunnel.getType)
            .addHosts(tunnel.getHosts(0))
            .addHosts(newHostToIp)
            .build()
        store.update(updatedTunnel)
        updatedTunnel
    }

    private def removeHostFromTunnel(tunnel: ProtoTunnelZone, hostId: UUID) = {
        val hosts = tunnel.getHostsList.filter(!_.getHostId.equals(hostId.asProto))
        val updatedTunnel = ProtoTunnelZone.newBuilder()
            .setId(tunnel.getId)
            .setType(tunnel.getType)
            .addAllHosts(hosts)
            .build()
        store.update(updatedTunnel)
    }

    feature("TunnelZoneRequest of the VTPMRedirector") {
        scenario("Sending a TunnelZoneRequest") {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPAddressUtil.toProto("192.168.0.1")
            val tunnel = createTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We send a tunnel zone request")
            vtpm.self.tell(TunnelZoneRequest(tunnelId), testActor)

            Then("We receive the tunnel zone with its members")
            val hosts = Set(new HostConfig(hostId)
                                .setIp(IPAddressUtil.toIPv4Addr(hostIp)))
            expectMsg(ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts))

            And("When we update the tunnel zone")
            val newHostId = UUID.randomUUID()
            val newHostIp = IPAddressUtil.toProto("192.168.0.2")
            val updatedTunnel = addHostToTunnel(tunnel, newHostId, newHostIp)

            Then("We receive the updated tunnel zone")
            val addedHost = new HostConfig(newHostId)
                                .setIp(IPAddressUtil.toIPv4Addr(newHostIp))
            expectMsg(ZoneChanged(tunnelId, OldTunnel.Type.vtep, addedHost,
                                  HostConfigOperation.Added))

            And("When we remove one host from the tunnel zone")
            removeHostFromTunnel(updatedTunnel, newHostId)

            Then("We receive the updated tunnel zone")
            val removedHost = new HostConfig(newHostId)
                                  .setIp(IPAddressUtil.toIPv4Addr(newHostIp))
            expectMsg(ZoneChanged(tunnelId, OldTunnel.Type.vtep, removedHost,
                                  HostConfigOperation.Deleted))
        }

        scenario("Unsubscribing from the tunnel zone") {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPAddressUtil.toProto("192.168.0.1")
            val tunnel = createTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We send a tunnel zone request")
            vtpm.self.tell(TunnelZoneRequest(tunnelId), testActor)

            Then("We receive the tunnel zone with its members")
            val hosts = Set(new HostConfig(hostId)
                                .setIp(IPAddressUtil.toIPv4Addr(hostIp)))
            expectMsg(ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts))

            And("When we unsubscribe from the tunnel zone")
            try {
                Then("No exceptions should be raised")
                vtpm.self.tell(TunnelZoneUnsubscribe(tunnelId), testActor)
            } catch {
                case e: Exception => fail("Unsubscribing to a tunnel zone should" +
                                          s" not throw exception: $e")
            }
        }
    }

    feature("TryAsk of the VTPMRedirector") {
        scenario("Requesting a tunnel zone with tryAsk")   {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPAddressUtil.toProto("192.168.0.1")
            val tunnel = createTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We request the tunnel zone with tryAsk")
            val nye = intercept[NotYetException] {
                VirtualToPhysicalMapper
                    .tryAsk[ZoneMembers](TunnelZoneRequest(tunnelId))(actorSystem)
            }

            val zoneMembers = Await.result(nye.waitFor, 1.seconds)
                                  .asInstanceOf[ZoneMembers]
            val hosts = Set(new HostConfig(hostId)
                                .setIp(IPAddressUtil.toIPv4Addr(hostIp)))
            zoneMembers shouldBe ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts)

            And("When we update the tunnel zone and ask for it")
            val newHostId = UUID.randomUUID()
            val newHostIp = IPAddressUtil.toProto("192.168.0.2")
            addHostToTunnel(tunnel, newHostId, newHostIp)
            val updatedZoneMembers = VirtualToPhysicalMapper
                .tryAsk[ZoneMembers](TunnelZoneRequest(tunnelId))(actorSystem)

            Then("We receive the updated tunnel zone")
            val addedHost = new HostConfig(newHostId)
                .setIp(IPAddressUtil.toIPv4Addr(newHostIp))
            val updatedHosts = Set(new HostConfig(hostId)
                                       .setIp(IPAddressUtil.toIPv4Addr(hostIp)),
                                   new HostConfig(addedHost.getId)
                                       .setIp(addedHost.getIp))
            updatedZoneMembers shouldBe ZoneMembers(tunnelId, OldTunnel.Type.vtep,
                                             updatedHosts)
        }
    }
}