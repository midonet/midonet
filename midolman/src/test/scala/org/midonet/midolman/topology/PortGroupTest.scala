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
import scala.concurrent.duration._

import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.{PortGroup => ClusterPortGroup}
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.midolman.simulation.{PortGroup => SimPortGroup}
import org.midonet.midolman.topology.{VirtualTopologyActor => VTA}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.midolman.topology.VirtualTopologyActor.PortGroupRequest
import org.midonet.packets.{IPv4Subnet, MAC, IPv4Addr}
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class PortGroupTest extends MidolmanSpec
                    with MidonetEventually {
    implicit val askTimeout: Timeout = 1 second

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
        with MessageAccumulator))

    var port1: RouterPort = _
    var port2: RouterPort = _

    var portGroup: ClusterPortGroup = _

    def randomPort(router: UUID) = {
        val subnet = new IPv4Subnet(IPv4Addr.random, 24)
        newRouterPort(router, MAC.random(), subnet.toUnicastString,
            subnet.toNetworkAddress.toString, subnet.getPrefixLen)
    }

    override def beforeTest() {
        newHost("myself", hostId)
        val router = newRouter("router0")
        port1 = randomPort(router)
        port2 = randomPort(router)
        portGroup = newPortGroup("port-group-test", false)
    }

    private def interceptPortGroup(): SimPortGroup = {
        val simGroup = VTA.getAndClear().filter(_.isInstanceOf[SimPortGroup]).head
        simGroup.asInstanceOf[SimPortGroup]
    }

    feature("midolman tracks port groups in the cluster correctly") {
        scenario("VTA gets a port group and receives updates from its manager") {
            VTA ! PortGroupRequest(portGroup.getId, update = false)

            val pg = interceptPortGroup()
            pg.name should equal ("port-group-test")
            pg should not be 'stateful

            updatePortGroup(portGroup.setStateful(true))
            eventually {
                interceptPortGroup() should be ('stateful)
            }

            newPortGroupMember(pg.id, port1.getId)
            eventually {
                interceptPortGroup().members should equal (Set(port1.getId))
            }

            newPortGroupMember(pg.id, port2.getId)
            eventually {
                interceptPortGroup().members should equal (Set(port1.getId, port2.getId))
            }

            deletePortGroupMember(pg.id, port1.getId)
            eventually {
                interceptPortGroup().members should equal (Set(port2.getId))
            }
        }
    }
}
