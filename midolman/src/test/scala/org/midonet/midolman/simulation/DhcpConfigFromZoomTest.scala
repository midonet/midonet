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

package org.midonet.midolman.simulation

import java.util.UUID

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class DhcpConfigFromZoomTest extends MidolmanSpec
                             with MidonetEventually
                             with TopologyBuilder {

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var cfg: DhcpConfigFromZoom = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        cfg = new DhcpConfigFromZoom(vt)
    }

    private def createSubnet(address: IPv4Addr): Subnet = {
        new Subnet(address.toString, new Subnet.Data())
    }

    feature("The DHCP config can't fetch a subnet") {
        scenario("from a non existing bridge") {
            intercept[NotYetException] {
                cfg.bridgeDhcpSubnets(UUID.randomUUID())
            }
        }
    }

    feature("The DHCP config fetches data correctly") {

        scenario("A bridge has no subnets") {
            Given("A bridge")
            val id = UUID.randomUUID()
            val b = createBridge(id)
            store.create(b)

            And("The VTA has the bridge in its cache")
            eventually {
                VirtualTopology.tryGet[Bridge](id).id shouldBe id
            }

            When("Requesting subnets for the bridge")
            val subnets = cfg.bridgeDhcpSubnets(id)

            Then("The list of subnets is empty")
            subnets shouldBe empty
        }

        scenario("A bridge has subnets that don't exist") {
            Given("A bridge")
            val id = UUID.randomUUID()
            val b = createBridge(id)
            store.create(b)

            And("The VTA has the bridge in its cache")
            eventually {
                val _b = VirtualTopology.tryGet[Bridge](id)
                _b.id shouldBe id
                _b.subnetIds shouldBe empty
            }

            Then("The request fails as subnets don't exist")
            cfg.bridgeDhcpSubnets(id) shouldBe empty

            And("The host searches fail as no subnets exist")
            cfg.dhcpHost(id, createSubnet(IPv4Addr.random),
                         MAC.random().toString) shouldBe None
        }

        scenario("A bridge has subnets that do exist") {
            Given("A bridge and some DHCP configs")
            val bId = UUID.randomUUID()
            val b = createBridge(bId)
            store.create(b)

            val dhcps = (1 to 3).toList map { i =>
                val dhcp = createDhcp(bId)
                dhcp.toBuilder.addAllHosts(List(
                    createDhcpHost(s"h-$i", MAC.random(), IPv4Addr.random),
                    createDhcpHost(s"h-$i", MAC.random(), IPv4Addr.random)
                ))
                store.create(dhcp)
                dhcp
            }

            And("The VTA has the bridge in its cache")
            eventually {
                val _b = VirtualTopology.tryGet[Bridge](bId)
                _b.id shouldBe bId
                _b.subnetIds should have size dhcps.size
            }

            When("The subnets are requested ")
            val subnets = cfg.bridgeDhcpSubnets(bId)

            Then("The list of subnets is as expected")
            subnets should have size dhcps.size

            dhcps zip subnets foreach {
                case (d, s) => cfg.toSubnet(d) shouldEqual s
                case _ => fail("Unexpected")
            }

            And("The hosts can be found")
            dhcps foreach { dhcp => dhcp.getHostsList foreach { h =>
                val addr = dhcp.getSubnetAddress.asJava.asInstanceOf[IPv4Subnet]
                val _h = cfg.dhcpHost(bId, cfg.toSubnet(dhcp), h.getMac)
                _h shouldBe h
            }}

            And("Non existing hosts are not found")
            cfg.dhcpHost(bId, subnets.head, "aa:bb:cc:dd:ee:ff") shouldBe None
        }
    }
}
