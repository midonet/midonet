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

package org.midonet.cluster.auth.keystone.v2_0

import java.util.TimeZone

import org.scalatest.{FunSuite, ShouldMatchers}

import org.midonet.cluster.auth.keystone.v2_0.KeystoneService.parseExpirationDate

class KeystoneServiceTest extends FunSuite with ShouldMatchers {

    val utcTimeZone = TimeZone.getTimeZone("UTC")

    test("Fails parsing a malformed date") {
        intercept[KeystoneInvalidFormatException] {
            parseExpirationDate("malformed")
        }
    }

    test("Parses the standard format") {
        val stdFormat = "2015-06-28T08:28:34Z"
        val parsed = parseExpirationDate(stdFormat)
        println(parsed)
    }

    test("Parses the format returned when Keystone uses Fernet tokens") {
        val fernetFormat = "2015-06-28T08:39:41.644518Z"
        val parsed = parseExpirationDate(fernetFormat)
        println(parsed)
    }

}
