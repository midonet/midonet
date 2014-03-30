/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.topology

import java.util.UUID

import akka.actor.{Actor, Props}
import akka.actor.Actor.emptyBehavior
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.zones.GreTunnelZoneHost
import org.midonet.midolman.{VirtualTopologyHelper, MockMidolmanActors, MidolmanServices, VirtualConfigurationBuilders}
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.packets.IPv4Addr
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.midolman.topology.VirtualToPhysicalMapper.TunnelZoneRequest
import org.midonet.midolman.topology.VirtualToPhysicalMapper.GreZoneChanged
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest

@RunWith(classOf[JUnitRunner])
class VirtualToPhysicalMapperTest extends Suite
                                  with Matchers
                                  with BeforeAndAfter
                                  with MockMidolmanActors
                                  with MidolmanServices
                                  with VirtualConfigurationBuilders
                                  with VirtualTopologyHelper
                                  with OneInstancePerTest {

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
                                       with MessageAccumulator),
        VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper
                                          with MessageAccumulator))

    class Subscriber(request: AnyRef) extends Actor {
        override def preStart(): Unit = {
            super.preStart()
            VirtualToPhysicalMapper ! request
        }

        def receive: Receive = emptyBehavior
    }

    def subscribe(request: AnyRef): MessageAccumulator =
        TestActorRef[Subscriber with MessageAccumulator](Props(
            new Subscriber(request) with MessageAccumulator)).underlyingActor

    def testHostRequest() {
        newHost("myself", hostId())

        val host = Host(hostId(), "midonet", Map[UUID, String](),
                        Map[UUID, TunnelZone.HostConfig[_, _]]())

        val subscriber = subscribe(HostRequest(hostId()))
        subscriber.getAndClear() should be (List(host))

        (1 to 5) map { _ =>
            host
        } andThen {
            VirtualToPhysicalMapper ! _
        } andThen {
            subscriber.messages should contain (_)
        }
    }

    def testTZRequest() {
        val zone = greTunnelZone("twilight-zone")
        val host = newHost("myself", hostId(), Set(zone.getId))
        val tunnelZoneHost = new GreTunnelZoneHost(host.getId)
                             .setIp(IPv4Addr("1.1.1.1").toIntIPv4)
        clusterDataClient().tunnelZonesAddMembership(zone.getId, tunnelZoneHost)

        val subscriber = subscribe(TunnelZoneRequest(zone.getId))
        subscriber.getAndClear() should be (List(
            GreZoneMembers(zone.getId, Set(tunnelZoneHost))))

        VirtualToPhysicalMapper ! GreZoneChanged(zone.getId, tunnelZoneHost,
                                                 HostConfigOperation.Deleted)
        subscriber.getAndClear() should be (List(
            GreZoneChanged(zone.getId, tunnelZoneHost,
                           HostConfigOperation.Deleted)))

        VirtualToPhysicalMapper ! GreZoneChanged(zone.getId, tunnelZoneHost,
                                                 HostConfigOperation.Added)
        subscriber.getAndClear() should be (List(
            GreZoneChanged(zone.getId, tunnelZoneHost,
                           HostConfigOperation.Added)))

        val other = new GreTunnelZoneHost(UUID.randomUUID())
        VirtualToPhysicalMapper ! GreZoneChanged(zone.getId, other,
                                                 HostConfigOperation.Added)
        subscriber.getAndClear() should be (List(
            GreZoneChanged(zone.getId, other,
                           HostConfigOperation.Added)))
    }

    def testPortSetRequest() {
        val host = newHost("myself", hostId())
        val bridge = newBridge("portSetBridge")
        val localPort = newBridgePort(bridge, new BridgePort().setHostId(host.getId))
        val remoteHost = UUID.randomUUID()
        newBridgePort(bridge, new BridgePort().setHostId(remoteHost))
        fetchTopology(bridge, localPort)
        VirtualToPhysicalMapper ! LocalPortActive(localPort.getId, active = true)
        clusterDataClient().portSetsAddHost(bridge.getId, remoteHost)

        val subscriber = subscribe(PortSetRequest(bridge.getId, update = false))
        subscriber.getAndClear() should be (List(
            PortSet(bridge.getId, Set(remoteHost), Set(localPort.getId))))
    }
}
