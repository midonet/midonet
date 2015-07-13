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
class HmacTest extends FeatureSpec with Matchers {
    val examples = Set(
        /*
         * Examples taken from:
         * https://en.wikipedia.org/wiki/Hash-based_message_authentication_code
         */
        ("", "",
         "b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad"),
        ("key", "The quick brown fox jumps over the lazy dog",
         "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
    )

    feature("Hmac") {
        scenario("hmac sha256") {
            examples foreach { case (key, data, hmac) =>
                (Hmac.hmac(key.getBytes, data.getBytes)
                    flatMap (x => f"${x}%02x")).mkString shouldBe hmac
            }
        }
    }
}
