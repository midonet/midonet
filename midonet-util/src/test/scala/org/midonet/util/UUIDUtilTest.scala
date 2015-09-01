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

package org.midonet.util

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UUIDUtilTest extends FlatSpec with Matchers {

    import UUIDUtil._

    "Random UUIDs" should "xor back and forth correctly" in {
        1 to 10 foreach { i =>
            val uuid1 = UUID.randomUUID()
            val uuid2 = UUID.randomUUID()
            val uuid3 = xor(uuid1, uuid2)

            xor(uuid3, uuid1) shouldBe uuid2
            xor(uuid2, uuid3) shouldBe uuid1
        }
    }
}
