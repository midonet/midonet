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

package org.midonet.brain.services.c3po.translators

import java.io.StringReader

import scala.concurrent.Promise

import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.IPAddress
import org.midonet.cluster.models.Commons.IPSubnet
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Chain, Network, Port}
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

/* A common base class for testing NeutronPort CRUD translation. */
class PortTranslatorTest extends FlatSpec with BeforeAndAfter with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: PortTranslator = _

    protected val portId = UUIDUtil.randomUuidProto
    protected val networkId = UUIDUtil.randomUuidProto
    protected val tenantId = "neutron tenant"
    protected val adminStateUp = true
    protected val mac = "00:11:22:33:44:55"

    protected val portBase = s"""
        id { $portId }
        network_id { $networkId }
        tenant_id: 'neutron tenant'
        mac_address: '$mac'
        """
    protected val portBaseDown = portBase + """
        admin_state_up: false
        """
    protected val portBaseUp = portBase + """
        admin_state_up: true
        """

    protected val vifPortUp = nPortFromTxt(portBaseUp)

    val midoNetworkBase = s"""
        id { $networkId }
        """
    val midoNetwork = mNetworkFromTxt(midoNetworkBase)

    val midoPortBase = s"""
        id { $portId }
        network_id { $networkId }
        """
    val midoPortUp = mPortFromTxt(midoPortBase + """
        admin_state_up: true
        """)
    val midoPortDown = mPortFromTxt(midoPortBase + """
        admin_state_up: false
        """)

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new PortTranslator(storage)

        when(storage.get(classOf[Network], networkId))
            .thenReturn(Promise.successful(midoNetwork).future)
    }

    "Non-Floating IP Neutron Port DELETE" should "delete the MidoNet Port" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(vifPortUp).future)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps should contain (midonet.Delete(classOf[Port], portId))
    }
}

/**
 * Tests the Neutron VIF Port model translation.
 */
@RunWith(classOf[JUnitRunner])
class VifPortTranslationTest extends PortTranslatorTest {
    "VIF port CREATE" should "create a normal Network Port" in {
        val midoOps = translator.translate(neutron.Create(vifPortUp))
        midoOps should contain (midonet.Create(midoPortUp))
    }

    // TODO test that VIF port CREATE updates security group
    // TODO test that VIF port CREATE creates an external network route if the
    //      port is attached to an external network.
    // TODO test that VIF port CREATE adds a DHCP host entry.
    // TODO test that VIF/DHCP/interface/router GW port CREATE creates a tunnel
    //      key, and makes the tunnel key reference the port.

    "VIF port UPDATE" should "update port admin state" in {
        val vifPortDown = nPortFromTxt(s"$portBaseDown")
        val midoOps = translator.translate(neutron.Update(vifPortDown))
        midoOps should contain (midonet.Update(midoPortDown))
    }
}

@RunWith(classOf[JUnitRunner])
class DhcpPortTranslationTest extends PortTranslatorTest {
    private val dhcpPortUp = nPortFromTxt(portBaseUp + """
        device_owner: DHCP
        """)

    "DHCP port CREATE" should "create a normal Network port" in {
        val midoOps = translator.translate(neutron.Create(dhcpPortUp))
        midoOps should contain only midonet.Create(midoPortUp)
    }

    // TODO test that DHCP port CREATE adds a route to meta data service.
    // TODO test that DHCP port CREATE adds DHCP IP Neutron data.

    "DHCP port UPDATE" should "update port admin state" in {
        val dhcpPortDown = dhcpPortUp.toBuilder().setAdminStateUp(false).build
        val midoOps = translator.translate(neutron.Update(dhcpPortDown))

        midoOps should contain (midonet.Update(midoPortDown))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpPortTranslationTest extends PortTranslatorTest {
    protected val fipPortUp = nPortFromTxt(portBaseUp + """
        device_owner: FLOATINGIP
        """)

    "Floating IP port CREATE" should "not create a Network port" in {
        val midoOps = translator.translate(neutron.Create(fipPortUp))
        midoOps shouldBe empty
    }

    "Floating IP port UPDATE" should "NOT update Port" in {
        val midoOps = translator.translate(neutron.Update(fipPortUp))
        midoOps shouldBe empty
     }

    "Floating IP DELETE" should "NOT delete the MidoNet Port" in {
        when(storage.get(classOf[NeutronPort], portId))
            .thenReturn(Promise.successful(fipPortUp).future)
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronPort], portId))
        midoOps shouldBe empty
    }
}

@RunWith(classOf[JUnitRunner])
class RouterInterfacePortTranslationTest extends PortTranslatorTest {
    private val routerIfPortUp = nPortFromTxt(portBaseUp + """
        device_owner: ROUTER_INTF
        """)

    "Router interface port CREATE" should "create a normal Network port" in {
        val midoOps = translator.translate(neutron.Create(routerIfPortUp))
        midoOps should contain only midonet.Create(midoPortUp)
    }

    "Router interface port UPDATE" should "NOT update Port" in {
        val midoOps = translator.translate(neutron.Update(routerIfPortUp))
        midoOps shouldBe empty
     }
}

@RunWith(classOf[JUnitRunner])
class RouterGatewayPortTranslationTest extends PortTranslatorTest {
    private val routerGatewayPort = nPortFromTxt(portBaseUp + """
        device_owner: ROUTER_GW
        """)

    "Router gateway port CREATE" should "produce Mido provider router port " +
    "CREATE" in {
        // TODO: Test that the midoPort has the provider router ID.
    }

    "Router gateway port Update" should "not update Port " +
    "CREATE" in {
        val midoOps = translator.translate(neutron.Update(routerGatewayPort))
        midoOps shouldBe empty
    }
}