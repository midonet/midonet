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

package org.midonet.midolman.openstack.metadata

import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AddressManagerTest extends FeatureSpec
                         with Matchers {

    val examples = Set(
        (4,     "169.254.0.1"),
        (5,     "169.254.0.2"),
        (43519, "169.254.169.252"),
        (43520, "169.254.169.253"),
        (43521, "169.254.169.255"),
        (43522, "169.254.170.0"),
        (65533, "169.254.255.251"),
        (65534, "169.254.255.252")
    )

    feature("AddressManager") {
        scenario("get") {
            examples foreach { case (num, str) =>
                AddressManager getRemoteAddress num shouldBe str
            }
        }

        scenario("put") {
            examples foreach { case (num, str) =>
                AddressManager putRemoteAddress str shouldBe num
            }
        }
    }
}
