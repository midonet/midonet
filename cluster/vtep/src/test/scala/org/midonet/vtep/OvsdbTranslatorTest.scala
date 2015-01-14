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

package org.midonet.vtep

import java.util
import java.util.{Random, UUID}

import scala.collection.JavaConversions._

import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class OvsdbTranslatorTest extends FeatureSpec with Matchers {
    val random = new Random()
    feature("uuid conversion") {
        scenario("uuid translations") {
            val id = UUID.randomUUID()
            val ovs = OvsdbTranslator.toOvsdb(id)
            ovs.toString shouldBe id.toString
            OvsdbTranslator.fromOvsdb(ovs) shouldBe id
        }
        scenario("uuid set translations") {
            val idSet = Set(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
            )
            val ovsSet = OvsdbTranslator.toOvsdb(idSet)
            ovsSet.map(_.toString).toSet shouldBe idSet.map(_.toString)
            OvsdbTranslator.fromOvsdb(ovsSet) shouldBe setAsJavaSet(idSet)

            val nullSet: util.Set[UUID] = null
            OvsdbTranslator.fromOvsdb(nullSet).isEmpty shouldBe true
        }
        scenario("uuid map translations") {
            val idMap = Map(
                (random.nextInt().asInstanceOf[Integer], UUID.randomUUID()),
                (random.nextInt().asInstanceOf[Integer], UUID.randomUUID()),
                (random.nextInt().asInstanceOf[Integer], UUID.randomUUID()),
                (random.nextInt().asInstanceOf[Integer], UUID.randomUUID())
            )
            val converted = OvsdbTranslator.toOvsdb(idMap)
            val ovsMap = new util.HashMap[Long, OvsdbUUID]()
            for (e <- idMap) {
                ovsMap.put(e._1.longValue(), OvsdbTranslator.toOvsdb(e._2))
            }
            converted shouldBe ovsMap
            OvsdbTranslator.fromOvsdb(ovsMap) shouldBe mapAsJavaMap(idMap)
            OvsdbTranslator.fromOvsdb(converted) shouldBe mapAsJavaMap(idMap)

            val nullMap: util.Map[Long, OvsdbUUID] = null
            OvsdbTranslator.fromOvsdb(nullMap).isEmpty shouldBe true
        }
    }

    feature("ip conversion") {
        scenario("ip set translations") {
            val strSet = new util.HashSet[String]()
            strSet.add("10.0.0.1")
            strSet.add("10.0.0.2")
            strSet.add("10.0.0.3")
            strSet.add("10.0.0.4")
            val ipSet: util.Set[IPv4Addr] = OvsdbTranslator
                .fromOvsdbIpSet(strSet)
            ipSet.toSet shouldBe strSet.toSet.map(IPv4Addr.fromString)
            ipSet.toSet[IPv4Addr].map(_.toString) shouldBe strSet.toSet
            OvsdbTranslator.toOvsdbIpSet(ipSet.toSet) shouldBe strSet

            val nullSet: util.Set[String] = null
            OvsdbTranslator.fromOvsdbIpSet(nullSet).isEmpty shouldBe true

            val nullIps: Set[IPv4Addr] = null
            OvsdbTranslator.toOvsdbIpSet(nullIps).isEmpty shouldBe true
        }
    }
}
