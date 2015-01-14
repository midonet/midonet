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

package org.midonet.cluster.data.vtep.model

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class VtepEndPointTest extends FeatureSpec with Matchers {
    feature("Vtep Endpoint") {
        scenario("equals") {
            val ep1 = VtepEndPoint(IPv4Addr.fromString("10.1.1.1"), 42)
            val ep10 = VtepEndPoint(IPv4Addr.fromString("10.1.1.1"), 42)
            val ep2 = VtepEndPoint(IPv4Addr.fromString("10.1.1.2"), 42)
            val ep11 = VtepEndPoint(IPv4Addr.fromString("10.1.1.1"), 43)
            val epnull = VtepEndPoint(null, 42)
            (ep1 == ep2) shouldBe false
            (ep1 == ep11) shouldBe false
            (ep1 == epnull) shouldBe false
            (ep1 == ep10) shouldBe true
        }

        scenario("toString") {
            val ep1 = VtepEndPoint(IPv4Addr.fromString("10.1.1.1"), 42)
            val epnull = VtepEndPoint(null, 42)
            ep1.toString shouldBe "10.1.1.1:42"
            epnull.toString shouldBe "null:42"
        }

        scenario("hashCode") {
            val ep1 = VtepEndPoint(IPv4Addr.fromString("10.1.1.1"), 42)
            val ep2 = VtepEndPoint(IPv4Addr.fromString("10.1.1.1"), 43)
            val epnull = VtepEndPoint(null, 42)
            ep2.hashCode should be > ep1.hashCode
            epnull.hashCode shouldBe 42
        }
    }
}
