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

package org.midonet.cluster.services.vxgw

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.vxgw.FloodingProxyCalculator.calculate
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class FloodingProxyCalculatorTest extends FlatSpec with Matchers
                                  with BeforeAndAfter with GivenWhenThen
                                  with TopologyBuilder {

    "No hosts" should "produce no FP" in {
        calculate(Map.empty[Host, IPv4Addr]) shouldBe None
    }

    "One host with the veto weight" should "produce no FP" in {
        val h = createHost().toBuilder.setFloodingProxyWeight(0).build()
        calculate(Map(h -> IPv4Addr.random).asJava) shouldBe None
    }

    "Happy case" should "work" in {
        val candidates = new java.util.HashMap[Host, IPv4Addr]()
        val hosts = 1 to 100 map { i =>
            createHost().toBuilder.setFloodingProxyWeight(i).build()
        }
        hosts foreach { h => candidates.put(h, IPv4Addr.random) }
        val fp = calculate(candidates).orNull
        fp.tunnelIp shouldNot be (null)

        val fpHost = hosts.find(h => UUIDUtil.fromProto(h.getId) == fp.hostId)
        fpHost.orNull.getFloodingProxyWeight shouldBe >(0)
    }

}
