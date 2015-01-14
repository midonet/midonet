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

package org.midonet.vtep.mock

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.vtep.OvsdbTools

@RunWith(classOf[JUnitRunner])
class MockOvsdbVtepTest extends FeatureSpec with Matchers {
    feature("observable vtep") {
        scenario("returns a usable handle") {
            val vtep = new InMemoryOvsdbVtep
            val client = vtep.getHandle

            client.isActive shouldBe true
            client.getDatabases.get().toSet shouldBe
                Set(OvsdbTools.DB_HARDWARE_VTEP)
            client.getSchema(OvsdbTools.DB_HARDWARE_VTEP).get().getName shouldBe
                OvsdbTools.DB_HARDWARE_VTEP
        }
    }
}
