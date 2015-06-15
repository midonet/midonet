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

import scala.collection.JavaConversions._
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.Observable

import org.midonet.cluster.data.TunnelZone.HostConfig
import org.midonet.cluster.data.storage.StorageWithOwnership
import org.midonet.cluster.data.{TunnelZone => OldTunnel, ZoomConvert}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{Host => ProtoHost, TunnelZone => ProtoTunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.devices.{Host => SimHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class VTPMRedirectorTest extends TestKit(ActorSystem("VTPMRedirectorTest"))
                         with MidolmanSpec
                         with ImplicitSender
                         with TopologyBuilder {

    private var vtpm: TestableVTPM = _
    private var store: StorageWithOwnership = _
    private var vt: VirtualTopology = _

    private class TestableVTPM extends VirtualToPhysicalMapper with MessageAccumulator

    registerActors(VirtualToPhysicalMapper -> (() => new TestableVTPM))

    override protected def fillConfig(config: Config) = {
        // Tests to cover the cases when the new cluster is disabled are
        // present in VirtualToPhysicalMapperTest
        super.fillConfig(config).withValue("zookeeper.use_new_stack",
                                           ConfigValueFactory.fromAnyRef(true))
    }

    override def beforeTest() {
        vt = injector.getInstance(classOf[VirtualTopology])
        vtpm = VirtualToPhysicalMapper.as[TestableVTPM]
        store = injector.getInstance(classOf[MidonetBackend]).ownershipStore
    }

    private def buildAndStoreTunnelZone(hostId: UUID, hostIp: IPAddr)
        : ProtoTunnelZone = {

        val newTz = createTunnelZone(UUID.randomUUID(), ProtoTunnelZone.Type.VTEP,
                                     Some("tunnel"), Map(hostId -> hostIp))
        store.create(newTz)
        newTz
    }

    private def buildAndStoreHost: ProtoHost = {
        val protoHost = createHost(UUID.randomUUID())
        store.create(protoHost, protoHost.getId.asJava.toString)
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
        val updatedTunnel = createTunnelZone(tunnel.getId, tunnel.getType,
                                             Some("tunnel"), hosts.toMap)
        store.update(updatedTunnel)
        updatedTunnel
    }

    private def addTunnelToHost(host: ProtoHost, tzId: UUID): ProtoHost = {
        val portIds = host.getPortIdsList.toSet.map(
            (id: Commons.UUID) => id.asJava)
        val updatedHost = createHost(host.getId, portIds, Set(tzId))
        store.update(updatedHost, updatedHost.getId.asJava.toString,
                     validator = null)
        updatedHost
    }

    private def removeHostFromTz(tunnelZone: ProtoTunnelZone, hostId: UUID) = {
        val filteredHosts = tunnelZone.getHostsList
            .filterNot(_.getHostId == hostId.asProto)
        val hosts = fromHostToIpList(filteredHosts.toList)
        val updatedTunnel = createTunnelZone(tunnelZone.getId, tunnelZone.getType,
                                             Some("tunnel"), hosts.toMap)
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

        scenario("Deleting the tunnel zone after sending a tz request") {
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

            When("We create an observer to the VT observable")
            val observer = new TestAwaitableObserver[SimHost]
            vt.observables(tunnelId.asJava)
                .asInstanceOf[Observable[SimHost]]
                .subscribe(observer)

            And("When we update the tunnel zone")
            store.delete(classOf[ProtoTunnelZone], tunnelId)

            And("We wait for the deletion to be notified")
            observer.awaitCompletion(5 seconds)

            Then("tryAsk should result in a NotYetException as the VTPM DeviceCaches"
                 + "should have been cleared")
            intercept[NotYetException] {
                val tzRequest = TunnelZoneRequest(tunnelId)
                VirtualToPhysicalMapper.tryAsk[ZoneMembers](tzRequest)(actorSystem)
            }
        }

        scenario("Unsubscribing from the tunnel zone") {
            Given("A topology with one tunnel zone")
            val hostId = UUID.randomUUID()
            val hostIp = IPv4Addr.fromString("192.168.0.1")
            val tunnel = buildAndStoreTunnelZone(hostId, hostIp)
            val tunnelId = tunnel.getId

            When("We send a tunnel zone request")
            val tzRequest = TunnelZoneRequest(tunnelId)
            vtpm.self.tell(tzRequest, testActor)

            Then("We receive the tunnel zone with its members")
            val hosts = new mutable.HashSet[HostConfig]()
            hosts.add(new HostConfig(hostId).setIp(hostIp))
            expectMsg(ZoneMembers(tunnelId, OldTunnel.Type.vtep, hosts.toSet))

            And("When we unsubscribe from the tunnel zone")
            try {
                Then("No exceptions should be raised")
                vtpm.self.tell(TunnelZoneUnsubscribe(tunnelId), testActor)
            } catch {
                case e: Exception => fail("Unsubscribing from a tunnel zone should" +
                                          s" not throw exception: $e")
            }

            Then("tryAsk should result in a NotYetException as the VTPM DeviceCaches"
                 + "should have been cleared")
            intercept[NotYetException] {
                VirtualToPhysicalMapper.tryAsk[ZoneMembers](tzRequest)(actorSystem)
            }

            And("When we update the chain")
            val newHostId = UUID.randomUUID()
            val newHostIp = IPv4Addr.fromString("192.168.0.2")
            val updatedTz = addHostToTunnel(tunnel, newHostId, newHostIp)

            Then("The subscriber does not receive updates")
            hosts.add(new HostConfig(newHostId).setIp(newHostIp))
            intercept[AssertionError] {
                expectMsg(ZoneMembers(tunnelId, OldTunnel.Type.vtep,
                                      hosts.toSet))
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

            And("When we request the tunnel zone again, no exceptions are raised")
            VirtualToPhysicalMapper
                .tryAsk[ZoneMembers](TunnelZoneRequest(tunnelId))(actorSystem)
        }

        scenario("Requesting a tunnel zone that does not exist") {
            Given("An empty topology")

            When("When we request the tunnel zone with tryAsk")
            val rndId = UUID.randomUUID()
            val nye = intercept[NotYetException] {
                VirtualToPhysicalMapper
                    .tryAsk[ZoneMembers](TunnelZoneRequest(rndId))(actorSystem)
            }

            Then("A timeout exception is raised")
            intercept[TimeoutException] {
                Await.result(nye.waitFor, 1.seconds)
            }
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
            simHost.alive = true
            expectMsg(simHost)

            And("When we add the host to a tunnel zone")
            val protoTz = buildAndStoreTunnelZone(protoHost.getId,
                                                  IPAddr.fromString("192.168.0.1"))
            val updatedHost = addTunnelToHost(protoHost, protoTz.getId)

            Then("We obtain the updated host")
            val updatedSimHost = ZoomConvert.fromProto(updatedHost,
                                                       classOf[SimHost])
            updatedSimHost.tunnelZones =
                Map(protoTz.getId.asJava -> IPAddr.fromString("192.168.0.1"))
            updatedSimHost.alive = true
            expectMsg(updatedSimHost)

            And("When we remove the host form the tunnel zone")
            store.update(protoHost, protoHost.getId.asJava.toString,
                         validator = null)

            Then("We receive the host without any tunnel zones")
            expectMsg(simHost)
        }

        scenario("Deleting the host after sending a host request") {
            Given("A topology with one host")
            val protoHost = buildAndStoreHost

            When("We send a host request")
            vtpm.self.tell(HostRequest(protoHost.getId), testActor)

            Then("We receive the corresponding host")
            val simHost = ZoomConvert.fromProto(protoHost, classOf[SimHost])
            simHost.alive = true
            expectMsg(simHost)

            When("We create an observer to the VT observable")
            val observer = new TestAwaitableObserver[SimHost]
            vt.observables(protoHost.getId.asJava)
                .asInstanceOf[Observable[SimHost]]
                .subscribe(observer)

            And("When we delete the host")
            store.delete(classOf[ProtoHost], protoHost.getId,
                         protoHost.getId.asJava.toString)

            And("We wait for the deletion to be notified")
            observer.awaitCompletion(5 seconds)

            Then("The observer should receive a completed notification")
            observer.getOnCompletedEvents should not be empty

            And("tryAsk results in a NotYetException as the VTPM DeviceCaches"
                 + " have been cleared")
            intercept[NotYetException] {
                val hostRequest = HostRequest(protoHost.getId, update=false)
                VirtualToPhysicalMapper.tryAsk[SimHost](hostRequest)(actorSystem)
            }
        }

        scenario("Unsubscribing from the Host") {
            Given("A topology with one host")
            val protoHost = buildAndStoreHost

            When("We send a host request")
            val hostRequest = HostRequest(protoHost.getId)
            vtpm.self.tell(hostRequest, testActor)

            Then("We receive the corresponding host")
            val simHost = ZoomConvert.fromProto(protoHost, classOf[SimHost])
            simHost.alive = true
            expectMsg(simHost)

            And("When we unsubscribe from the host")
            try {
                Then("No exceptions should be raised")
                vtpm.self.tell(HostUnsubscribe(simHost.id), testActor)
            } catch {
                case e: Exception => fail("Unsubscribing from a host should" +
                                          s" not throw exception: $e")
            }

            Then("tryAsk should result in a NotYetException as the VTPM DeviceCaches"
                 + "should have been cleared")
            intercept[NotYetException] {
                VirtualToPhysicalMapper.tryAsk[SimHost](hostRequest)(actorSystem)
            }

            And("When we add a tunnel zone to the host")
            val tunnelZone = buildAndStoreTunnelZone(protoHost.getId,
                                    IPAddr.fromString("192.168.0.2"))
            val updatedHost = addTunnelToHost(protoHost, tunnelZone.getId)

            Then("The subscriber does not receive the update")
            val updatedSimHost = ZoomConvert.fromProto(updatedHost,
                                                       classOf[SimHost])
            intercept[AssertionError] {
               expectMsg(updatedSimHost)
            }
        }

        scenario("Requesting a host with TryAsk") {
            Given("A topology with one host")
            val protoHost = buildAndStoreHost

            When("We request the tunnel zone with tryAsk")
            val hostRequest = HostRequest(protoHost.getId, update=false)
            val nye = intercept[NotYetException] {
                VirtualToPhysicalMapper.tryAsk[SimHost](hostRequest)(actorSystem)
            }

            Then("We received the requested host")
            val receivedHost = Await.result(nye.waitFor, 1.seconds)
                .asInstanceOf[SimHost]
            val expectedHost = ZoomConvert.fromProto(protoHost, classOf[SimHost])
            expectedHost.alive = true
            receivedHost shouldBe expectedHost

            And("When we request the host again no exceptions are raised")
            VirtualToPhysicalMapper.tryAsk[SimHost](hostRequest)(actorSystem)
        }

        scenario("Requesting a host that does not exist") {
            Given("An empty topology")

            When("When we request the host with tryAsk")
            val hostRequest = HostRequest(UUID.randomUUID(), update=false)
            val nye = intercept[NotYetException] {
                VirtualToPhysicalMapper.tryAsk[SimHost](hostRequest)(actorSystem)
            }

            Then("A NotFoundException is raised")
            intercept[TimeoutException] {
                Await.result(nye.waitFor, 1.seconds)
            }
        }
    }
}
