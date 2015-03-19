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

package org.midonet.cluster.services.topology.common

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.models.Topology

@RunWith(classOf[JUnitRunner])
class TopologyMappingsTest extends FeatureSpec with Matchers {

    val types = List(Topology.Type.CHAIN,
                     Topology.Type.DHCP,
                     Topology.Type.HOST,
                     Topology.Type.IP_ADDR_GROUP,
                     Topology.Type.LOAD_BALANCER,
                     Topology.Type.NETWORK,
                     Topology.Type.POOL,
                     Topology.Type.POOL_MEMBER,
                     Topology.Type.PORT,
                     Topology.Type.PORT_GROUP,
                     Topology.Type.ROUTE,
                     Topology.Type.ROUTER,
                     Topology.Type.RULE,
                     Topology.Type.TUNNEL_ZONE,
                     Topology.Type.VIRTUAL_IP,
                     Topology.Type.VTEP,
                     Topology.Type.VTEP_BINDING)

    val classes = List(classOf[Topology.Chain],
                       classOf[Topology.Dhcp],
                       classOf[Topology.Host],
                       classOf[Topology.IPAddrGroup],
                       classOf[Topology.LoadBalancer],
                       classOf[Topology.Network],
                       classOf[Topology.Pool],
                       classOf[Topology.PoolMember],
                       classOf[Topology.Port],
                       classOf[Topology.PortGroup],
                       classOf[Topology.Route],
                       classOf[Topology.Router],
                       classOf[Topology.Rule],
                       classOf[Topology.TunnelZone],
                       classOf[Topology.VIP],
                       classOf[Topology.Vtep],
                       classOf[Topology.VtepBinding])

    feature("map topology classes to type ids")
    {
        scenario("sanity checks") {
            types.length shouldBe Topology.Type.values.length
            classes.length shouldBe Topology.Type.values.length
            TopologyMappings.typeToKlass.size shouldBe Topology.Type.values.length
        }

        scenario("convert from type to class") {
            types.zip(classes)
                .forall(x => TopologyMappings.klassOf(x._1) == Some(x._2)) shouldBe true
        }

        scenario("convert from class to type") {
            classes.zip(types)
                 .forall(x => TopologyMappings.typeOf(x._1) == Some(x._2)) shouldBe true
        }
    }
}


