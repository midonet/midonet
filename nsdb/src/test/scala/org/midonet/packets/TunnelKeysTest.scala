/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.packets

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpecLike, Matchers}

import TunnelKeys._

@RunWith(classOf[JUnitRunner])
class TunnelKeysTest extends FeatureSpecLike
        with Matchers {
    val MaxKey = 0xffffff

    feature("type keys") {
        scenario("key source has too many bits") {
            val srcKey = 0xdeadbeef

            val key = LegacyPortType.apply(srcKey)
            key & ~0xffffff shouldBe 0
        }

        scenario("key can only have on type") {
            def matchingTypes(key: Int): Int = {
                var count = 0
                if (LegacyPortType.isOfType(key)) { count += 1 }
                if (LocalPortGeneratedType.isOfType(key)) { count += 1 }
                if (Fip64Type.isOfType(key)) { count += 1 }
                if (FlowStateType.isOfType(key)) { count += 1 }
                count
            }
            var key = 0xdeadbeef

            key = FlowStateType.apply(key)
            matchingTypes(key) shouldBe 1
            FlowStateType.isOfType(key) shouldBe true

            key = LocalPortGeneratedType.apply(key)
            matchingTypes(key) shouldBe 1
            LocalPortGeneratedType.isOfType(key) shouldBe true

            key = Fip64Type.apply(key)
            matchingTypes(key) shouldBe 1
            Fip64Type.isOfType(key) shouldBe true

            key = LegacyPortType.apply(key)
            matchingTypes(key) shouldBe 1
            LegacyPortType.isOfType(key) shouldBe true
        }

        scenario("flow state key is detected correctly") {
            FlowStateType.isOfType(FlowStatePackets.TUNNEL_KEY) shouldBe true
        }
    }

    feature("trace bit") {
        scenario("trace bit can be added, detected and removed") {
            val key = LocalPortGeneratedType.apply(0xdeadbeef)
            val keyWithTrace = TraceBit.set(key)
            TraceBit.isSet(keyWithTrace) shouldBe true
            TraceBit.clear(keyWithTrace) shouldBe key
        }

        scenario("trace bit doesn't affect type") {
            val key = LocalPortGeneratedType.apply(0xdeadbeef)
            val keyWithTrace = TraceBit.set(key)
            LocalPortGeneratedType.isOfType(keyWithTrace) shouldBe true
        }
    }
}
