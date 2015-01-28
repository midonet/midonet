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

package org.midonet.cluster.storage

import org.junit.runner.RunWith
import org.midonet.cluster.models.C3PO.C3POState
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.CuratorTestFramework
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ZoomProviderTest extends FeatureSpec
                               with Matchers
                               with CuratorTestFramework {
    feature("pre-registered classes") {
        scenario("check all topology proto classes") {
            val zoomProvider = new ZoomProvider(curator)
            val storage = zoomProvider.get
            val classes = Set(
                classOf[Chain],
                classOf[Host],
                classOf[IpAddrGroup],
                classOf[Network],
                classOf[Port],
                classOf[PortGroup],
                classOf[Route],
                classOf[Router],
                classOf[Rule],
                classOf[TunnelZone],
                classOf[Vtep],
                classOf[VtepBinding]
            )

            // Check that type enumeration matches the class set size
            classes.size shouldBe Type.values.size

            // Check that all topology classes are registered
            classes.forall(storage.isRegistered) shouldBe true
        }

        scenario("check neutron classes") {
            val zoomProvider = new ZoomProvider(curator)
            val storage = zoomProvider.get
            val classes = Set(
                classOf[NeutronHealthMonitor],
                classOf[NeutronLoadBalancerPool],
                classOf[NeutronLoadBalancerPoolHealthMonitor],
                classOf[NeutronLoadBalancerPoolMember],
                classOf[NeutronNetwork],
                classOf[NeutronPort],
                classOf[NeutronRouter],
                classOf[NeutronSubnet],
                classOf[VIP]
            )

            // Check that all topology classes are registered
            classes.forall(storage.isRegistered) shouldBe true
        }

        scenario("c3po classes") {
            val zoomProvider = new ZoomProvider(curator)
            val storage = zoomProvider.get
            val classes = Set(
                classOf[C3POState]
            )

            // Check that all topology classes are registered
            classes.forall(storage.isRegistered) shouldBe true
        }
    }
}
