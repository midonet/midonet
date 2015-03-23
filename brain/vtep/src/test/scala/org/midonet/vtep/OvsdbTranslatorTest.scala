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
            OvsdbTranslator.fromOvsdb(ovs) shouldBe id
        }
        scenario("uuid set translations") {
            val idSet = new util.HashSet[UUID]()
            idSet.add(UUID.randomUUID())
            idSet.add(UUID.randomUUID())
            idSet.add(UUID.randomUUID())
            idSet.add(UUID.randomUUID())
            val ovsSet = OvsdbTranslator.toOvsdb(idSet)
            OvsdbTranslator.fromOvsdb(ovsSet) shouldBe idSet

            val nullSet: util.Set[UUID] = null
            OvsdbTranslator.fromOvsdb(nullSet).isEmpty shouldBe true
        }
        scenario("uuid map translations") {
            val idMap = new util.HashMap[Integer, UUID]()
            idMap.put(random.nextInt(), UUID.randomUUID())
            idMap.put(random.nextInt(), UUID.randomUUID())
            idMap.put(random.nextInt(), UUID.randomUUID())
            idMap.put(random.nextInt(), UUID.randomUUID())

            val ovsMap = new util.HashMap[Long, OvsdbUUID]()
            for (e <- idMap) {
                ovsMap.put(e._1.longValue(), OvsdbTranslator.toOvsdb(e._2))
            }
            OvsdbTranslator.fromOvsdb(ovsMap) shouldBe idMap

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

            val nullSet: util.Set[String] = null
            OvsdbTranslator.fromOvsdbIpSet(nullSet).isEmpty shouldBe true
        }
    }
}
