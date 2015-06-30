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

import akka.actor.Actor.emptyBehavior
import akka.actor.{Actor, Props}
import akka.testkit.TestActorRef

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.host.{Host => DataHost}
import org.midonet.cluster.data.{TunnelZone, ZoomConvert}
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.topology.devices.{Host => DevicesHost}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class VirtualToPhysicalMapperTest extends MidolmanSpec
                                  with TopologyBuilder {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
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

    private def buildHost(): DevicesHost = {
        // Create the host with the data client
        newHost("myself", hostId)
        val protoHost = createHost(hostId, Set.empty, Set.empty)
        val devicesHost = ZoomConvert.fromProto(protoHost, classOf[DevicesHost])
        devicesHost.alive = isHostAlive(hostId)
        devicesHost
    }

    feature("VirtualToPhysicalMapper resolves host requests.") {
        scenario("Subscribes to a host.") {
            val host = buildHost()
            val subscriber = subscribe(HostRequest(hostId))
            val notifications = subscriber.getAndClear()

            notifications should contain only host

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
            val host = newHost("myself", hostId, Set(zone))
            val tunnelZoneHost = new TunnelZone.HostConfig(host)
                                 .setIp(IPv4Addr("1.1.1.1"))
            clusterDataClient.tunnelZonesAddMembership(zone,
                                                       tunnelZoneHost)

            val subscriber = subscribe(TunnelZoneRequest(zone))
            subscriber.getAndClear() should be (List(
                ZoneMembers(zone, TunnelZone.Type.gre, Set(tunnelZoneHost))))

            VirtualToPhysicalMapper ! ZoneChanged(
                    zone, TunnelZone.Type.gre, tunnelZoneHost, HostConfigOperation.Deleted)
            subscriber.getAndClear() should be (List(
                ZoneChanged(zone, TunnelZone.Type.gre, tunnelZoneHost,
                               HostConfigOperation.Deleted)))

            VirtualToPhysicalMapper ! ZoneChanged(zone, TunnelZone.Type.gre,
                                                  tunnelZoneHost,
                                                  HostConfigOperation.Added)

            subscriber.getAndClear() should be (List(
                ZoneChanged(zone, TunnelZone.Type.gre, tunnelZoneHost,
                               HostConfigOperation.Added)))

            val other = new TunnelZone.HostConfig(UUID.randomUUID())
            VirtualToPhysicalMapper ! ZoneChanged(zone, TunnelZone.Type.gre, other,
                                                     HostConfigOperation.Added)
            subscriber.getAndClear() should be (List(
                ZoneChanged(zone, TunnelZone.Type.gre, other,
                               HostConfigOperation.Added)))
        }
    }
}
