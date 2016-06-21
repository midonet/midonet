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
import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.simulation.{PortGroup => SimPortGroup}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Subnet, MAC, IPv4Addr}
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class PortGroupTest extends MidolmanSpec
                    with MidonetEventually {
    val timeout = 1 second

    var port1: UUID = _
    var port2: UUID = _

    var portGroup: UUID = _

    def randomPort(router: UUID) = {
        val subnet = new IPv4Subnet(IPv4Addr.random, 24)
        newRouterPort(router, MAC.random(), subnet.toUnicastString,
            subnet.toNetworkAddress.toString, subnet.getPrefixLen)
    }

    override def beforeTest() {
        val router = newRouter("router0")
        port1 = randomPort(router)
        port2 = randomPort(router)
        portGroup = newPortGroup("port-group-test", false)
    }

    private def interceptPortGroup(): SimPortGroup = {
        VirtualTopology.tryGet(classOf[SimPortGroup], portGroup)
    }

    feature("midolman tracks port groups in the cluster correctly") {
        scenario("VTA gets a port group and receives updates from its manager") {
            Await.result(VirtualTopology.get(classOf[SimPortGroup], portGroup),
                         timeout)

            val pg = interceptPortGroup()
            pg.name should equal ("port-group-test")
            pg should not be 'stateful

            setPortGroupStateful(portGroup, true)
            interceptPortGroup() should be ('stateful)

            newPortGroupMember(pg.id, port1)
            interceptPortGroup().members should contain theSameElementsAs List(port1)

            newPortGroupMember(pg.id, port2)
            interceptPortGroup().members should contain theSameElementsAs List(port1, port2)

            deletePortGroupMember(pg.id, port1)
            interceptPortGroup().members should contain theSameElementsAs List(port2)
        }
    }
}
