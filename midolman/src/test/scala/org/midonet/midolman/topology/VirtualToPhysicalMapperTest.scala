/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.topology

import java.util.UUID
import scala.collection.JavaConversions._

import akka.actor.{Actor, Props}
import akka.actor.Actor.emptyBehavior
import akka.testkit.TestActorRef
import com.google.inject.Guice
import com.google.inject.Injector
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.TunnelZone
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.rcu.Host
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class VirtualToPhysicalMapperTest extends MidolmanSpec {
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

    feature("VirtualToPhysicalMapper resolves host requests.") {
        scenario("Subscribes to a host.") {
            newHost("myself", hostId())

            val host = Host(hostId(), "midonet", Map[UUID, String](),
                            Map[UUID, TunnelZone.HostConfig]())

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
    }

    feature("VirtualToPhysicalMapper resolves tunnel zones") {
        scenario("Subscribe to a tunnel zone.") {
            val zone = greTunnelZone("twilight-zone")
            val host = newHost("myself", hostId(), Set(zone.getId))
            val tunnelZoneHost = new TunnelZone.HostConfig(host.getId)
                                 .setIp(IPv4Addr("1.1.1.1"))
            clusterDataClient().tunnelZonesAddMembership(zone.getId,
                                                         tunnelZoneHost)

            val subscriber = subscribe(TunnelZoneRequest(zone.getId))
            subscriber.getAndClear() should be (List(
                ZoneMembers(zone.getId, TunnelZone.Type.gre, Set(tunnelZoneHost))))

            VirtualToPhysicalMapper ! ZoneChanged(
                    zone.getId, zone.getType, tunnelZoneHost, HostConfigOperation.Deleted)
            subscriber.getAndClear() should be (List(
                ZoneChanged(zone.getId, zone.getType, tunnelZoneHost,
                               HostConfigOperation.Deleted)))

            VirtualToPhysicalMapper ! ZoneChanged(zone.getId, zone.getType,
                                                  tunnelZoneHost,
                                                  HostConfigOperation.Added)

            subscriber.getAndClear() should be (List(
                ZoneChanged(zone.getId, zone.getType, tunnelZoneHost,
                               HostConfigOperation.Added)))

            val other = new TunnelZone.HostConfig(UUID.randomUUID())
            VirtualToPhysicalMapper ! ZoneChanged(zone.getId, zone.getType, other,
                                                     HostConfigOperation.Added)
            subscriber.getAndClear() should be (List(
                ZoneChanged(zone.getId, zone.getType, other,
                               HostConfigOperation.Added)))
        }
    }

    feature("VirtualToPhysicalMapper resolves port sets.") {
        scenario("Subscribe to a port set") {
            val host = newHost("myself", hostId())
            val bridge = newBridge("portSetBridge")
            val localPort = newBridgePort(
                    bridge, new BridgePort().setHostId(host.getId))
            val remoteHost = UUID.randomUUID()
            newBridgePort(bridge, new BridgePort().setHostId(remoteHost))
            fetchTopology(bridge, localPort)
            VirtualToPhysicalMapper ! LocalPortActive(localPort.getId,
                                                      active = true)
            clusterDataClient().portSetsAddHost(bridge.getId, remoteHost)

            val subscriber = subscribe(PortSetRequest(bridge.getId,
                                                      update = false))
            subscriber.getAndClear() should be (List(
                    PortSet(bridge.getId,
                            Set(remoteHost),
                            Set(localPort.getId))))
        }
    }
}
