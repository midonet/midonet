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

package org.midonet.cluster.data

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Commons

@RunWith(classOf[JUnitRunner])
class ObjectIdTest extends FlatSpec with Matchers with GivenWhenThen {

    "getIdString" should "handle string identifiers" in {
        Given("A string identifier")
        val id = "01234567-89ab-cdef-0123-456789abcdef"

        Then("The conversion returns the same string")
        getIdString(id) eq id shouldBe true
    }

    "getIdString" should "handle Java UUID identifiers" in {
        Given("A UUID identifier")
        val id = new UUID(0x0123456789abcdefL, 0x0123456789abcdefL)

        Then("The conversion returns the string")
        getIdString(id) shouldBe "01234567-89ab-cdef-0123-456789abcdef"
    }

    "getIdString" should "handle Protocol Buffers UUID identifiers" in {
        Given("A UUID identifier")
        val id = Commons.UUID.newBuilder()
                        .setMsb(0x0123456789abcdefL)
                        .setLsb(0x0123456789abcdefL)
                        .build()

        Then("The conversion returns the string")
        getIdString(id) shouldBe "01234567-89ab-cdef-0123-456789abcdef"
    }

    "getIdString" should "throw an exception for any other type" in {
        Given("A null identifier")
        val id = null

        Then("The conversion thorws an exception")
        intercept[IllegalArgumentException] { getIdString(id) }
    }

}
