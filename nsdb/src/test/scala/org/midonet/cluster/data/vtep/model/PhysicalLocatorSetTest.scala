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

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class PhysicalLocatorSetTest extends FeatureSpec with Matchers {
    val uuid = UUID.randomUUID()
    val loc = UUID.randomUUID().toString

    feature("constructors") {
        scenario("default constructor") {
            val s = PhysicalLocatorSet(uuid, loc)
            s.uuid shouldBe uuid
            s.locatorIds should contain only loc
        }
        scenario("non-uuid constructor") {
            val s = PhysicalLocatorSet(loc)
            s.uuid shouldNot be (null)
            s.locatorIds should contain only loc
        }
    }
    feature("physical locator set tolerates null values") {
        scenario("null uuid") {
            val s = PhysicalLocatorSet(null, loc)
            s.uuid shouldNot be (null)
            s.locatorIds should contain only loc
        }
        scenario("null locator set") {
            val s = new PhysicalLocatorSet(uuid, null)
            s.uuid shouldBe uuid
            s.locatorIds.isEmpty shouldBe true
        }
    }
    feature("operations") {
        scenario("equality does depend on uuid") {
            val s1 = PhysicalLocatorSet(uuid, loc)
            val s2 = PhysicalLocatorSet(uuid, loc)
            val s3 = PhysicalLocatorSet(null, loc)
            s1.equals(s2) shouldBe true
            s1.equals(s3) shouldBe false
        }
        scenario("hashcode depends on uuid") {
            val s1 = PhysicalLocatorSet(null, loc)
            val s2 = PhysicalLocatorSet(uuid, loc)
            s1.hashCode shouldNot be (s2.hashCode)
            s2.hashCode shouldBe uuid.hashCode()
        }
    }
}
