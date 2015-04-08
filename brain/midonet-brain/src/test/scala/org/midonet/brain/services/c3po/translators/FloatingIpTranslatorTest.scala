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

package org.midonet.brain.services.c3po.translators

import scala.concurrent.Promise

import com.google.protobuf.Message

import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.brain.services.c3po.C3POStorageManager.Operation
import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronRouter}
import org.midonet.cluster.models.Topology.{Port, Route, Router}
import org.midonet.cluster.util.IPSubnetUtil.univSubnet4
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}

class FloatingIpTranslatorTestBase extends FlatSpec with BeforeAndAfter
                                                    with Matchers
                                                    with OpMatchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: FloatingIpTranslator = _

    protected val fipId = randomUuidProto
    protected val fipRouterId = randomUuidProto
    protected val fipPortId = randomUuidProto
    protected val fipIpAddr = IPAddressUtil.toProto("10.10.10.1")
    protected val fipIpSubnet = IPSubnetUtil.fromAddr(fipIpAddr)
    protected val fipFixedIpAddr = IPAddressUtil.toProto("192.168.1.1")
    protected val fipRouteId = RouteManager.fipGatewayRouteId(fipId)

    protected val unboundFipProto = s"""
        id { $fipId }
        floating_ip_address { $fipIpAddr }
        """
    protected val unboundFip = nFloatingIpFromTxt(unboundFipProto)

    protected val prvRouterGatewayPortId = randomUuidProto
    protected val fipRouterInternalPortId = randomUuidProto
    protected val fipRouterGatewayPortId = randomUuidProto

    protected val fipRouter = mRouterFromTxt(s"""
        id { $fipRouterId }
        port_ids { $fipRouterInternalPortId }
        port_ids { $fipRouterGatewayPortId }
        """)

    protected val fipNeutronRouter = nRouterFromTxt(s"""
        id { $fipRouterId }
        gw_port_id { $prvRouterGatewayPortId }
        """)

    protected val fipProto = s"""
        $unboundFipProto
        router_id { $fipRouterId }
        port_id { $fipPortId }
        fixed_ip_address { $fipFixedIpAddr }
        """
    protected val fip = nFloatingIpFromTxt(fipProto)

    protected val gwRoute = mRouteFromTxt(s"""
        id { $fipRouteId }
        src_subnet { $univSubnet4 }
        dst_subnet { $fipIpSubnet }
        next_hop: PORT
        next_hop_port_id { $prvRouterGatewayPortId }
        weight: ${RouteManager.DEFAULT_WEIGHT}
        """)

    protected def bindFip(id: UUID, fip: FloatingIp) {
        when(storage.get(classOf[FloatingIp], id))
            .thenReturn(Promise.successful(fip).future)
    }
}

/**
 * Tests a Neutron Floating IP Create translation.
 */
@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorCreateTest extends FloatingIpTranslatorTestBase {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new FloatingIpTranslator(storage)

        when(storage.get(classOf[Router], fipRouterId))
            .thenReturn(Promise.successful(fipRouter).future)
        when(storage.get(classOf[NeutronRouter], fipRouterId))
            .thenReturn(Promise.successful(fipNeutronRouter).future)
    }

    "Unassociated floating IP" should "not create anything" in {
        val midoOps = translator.translate(neutron.Create(unboundFip))

        midoOps shouldBe empty
    }

    "Associated floating IP" should "create a route to GW" in {
        val midoOps: List[Operation] = translator.translate(neutron.Create(fip))

        midoOps should contain (midonet.Create(gwRoute))
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorUpdateTest extends FloatingIpTranslatorTestBase {
    import org.midonet.brain.services.c3po.translators.RouterTranslator._
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new FloatingIpTranslator(storage)

        when(storage.get(classOf[Router], fipRouterId))
            .thenReturn(Promise.successful(fipRouter).future)
        when(storage.get(classOf[NeutronRouter], fipRouterId))
            .thenReturn(Promise.successful(fipNeutronRouter).future)
    }

    "Associating a floating IP to a port" should "create a gateway route" in {
        bindFip(fipId, unboundFip)
        val midoOps = translator.translate(neutron.Update(fip))

        midoOps should contain (midonet.Create(gwRoute))
    }

    "Removing a floating IP from a port" should "delete the gateway route" in {
        bindFip(fipId, fip)
        val midoOps = translator.translate(neutron.Update(unboundFip))

        midoOps should contain (midonet.Delete(classOf[Route],
                                               fipRouteId))
    }

    "UpdateOp that doesn's newly associate / remove the floating IP" should
    "keep the gateway route as is" in {
        bindFip(fipId, fip)
        val midoOps = translator.translate(neutron.Update(fip))

        midoOps shouldBe empty
    }
}

@RunWith(classOf[JUnitRunner])
class FloatingIpTranslatorDeleteTest extends FloatingIpTranslatorTestBase {
    import org.midonet.brain.services.c3po.translators.RouterTranslator._

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new FloatingIpTranslator(storage)
    }

    "Deleting an unassociated floating IP" should "not create anything" in {
        bindFip(fipId, unboundFip)
        val midoOps = translator.translate(neutron.Delete(classOf[FloatingIp],
                                                          fipId))

        midoOps shouldBe empty
    }

    "Deleting an associated floating IP" should "delete a GW route" in {
        bindFip(fipId, fip)
        val midoOps = translator.translate(neutron.Delete(classOf[FloatingIp],
                                                          fipId))

        midoOps should contain (midonet.Delete(classOf[Route],
                                               fipRouteId))
    }
}