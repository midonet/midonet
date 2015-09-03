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

package org.midonet.southbound.vtep

import java.lang.{Long => JLong}
import java.util
import java.util.{Random, UUID}

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class OvsdbUtilTest extends FeatureSpec with Matchers {
    val random = new Random()

    feature("UUID conversion") {
        scenario("UUID translations") {
            val id = UUID.randomUUID()
            val ovs = OvsdbUtil.toOvsdb(id)
            ovs.toString shouldBe id.toString
            OvsdbUtil.fromOvsdb(ovs) shouldBe id
        }
        scenario("UUID set translations") {
            val idSet = Set(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
            )
            val ovsSet = OvsdbUtil.toOvsdb(idSet)
            ovsSet.map(_.toString).toSet shouldBe idSet.map(_.toString)
            OvsdbUtil.fromOvsdb(ovsSet) shouldBe setAsJavaSet(idSet)

            val nullSet: util.Set[OvsdbUUID] = null
            OvsdbUtil.fromOvsdb(nullSet).isEmpty shouldBe true
        }
        scenario("UUID map translations") {
            val idMap = Map(
                (random.nextInt(), UUID.randomUUID()),
                (random.nextInt(), UUID.randomUUID()),
                (random.nextInt(), UUID.randomUUID()),
                (random.nextInt(), UUID.randomUUID())
            )
            val ovsMap = new util.HashMap[JLong, OvsdbUUID]()
            for (e <- idMap) {
                ovsMap.put(e._1.longValue(), OvsdbUtil.toOvsdb(e._2))
            }
            OvsdbUtil.fromOvsdb(ovsMap) shouldBe mapAsJavaMap(idMap)

            val nullMap: util.Map[JLong, OvsdbUUID] = null
            OvsdbUtil.fromOvsdb(nullMap).isEmpty shouldBe true
        }
    }

    feature("IP conversion") {
        scenario("IP set translations") {
            val strSet = new util.HashSet[String]()
            strSet.add("10.0.0.1")
            strSet.add("10.0.0.2")
            strSet.add("10.0.0.3")
            strSet.add("10.0.0.4")
            val ipSet: util.Set[IPv4Addr] = OvsdbUtil
                .fromOvsdbIpSet(strSet)
            ipSet.toSet shouldBe strSet.toSet.map(IPv4Addr.fromString)
            ipSet.toSet[IPv4Addr].map(_.toString) shouldBe strSet.toSet
            OvsdbUtil.toOvsdbIpSet(ipSet.toSet) shouldBe strSet

            val nullSet: util.Set[String] = null
            OvsdbUtil.fromOvsdbIpSet(nullSet).isEmpty shouldBe true

            val nullIps: Set[IPv4Addr] = null
            OvsdbUtil.toOvsdbIpSet(nullIps).isEmpty shouldBe true
        }
    }
}
