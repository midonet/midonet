/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.flows

import java.nio.ByteBuffer
import java.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, GivenWhenThen, Matchers}

import org.midonet.odp.FlowMatches

@RunWith(classOf[JUnitRunner])
class NativeFlowMatchListTest extends FeatureSpecLike
                                      with Matchers
                                      with BeforeAndAfter
                                      with GivenWhenThen {

    NativeFlowMatchList.loadNativeLibrary()

    val random = new Random
    var nativeList: NativeFlowMatchList = _

    before {
        nativeList = new NativeFlowMatchList()
    }

    after {
        nativeList.delete()
    }

    scenario("Pop on an empty list") {
        intercept[NoSuchElementException] {
            nativeList.popFlowMatch()
        }
    }

    scenario("Operate on a delete native list") {
        nativeList.delete()
        intercept[Exception] {
            nativeList.popFlowMatch()
        }
    }

    scenario("Stack-like behaviour") {
        Given("A couple of random flow matches")
        val fmatch1 = FlowMatches.generateFlowMatch(random)
        val fmatch2 = FlowMatches.generateFlowMatch(random)
        val bytes1 = FlowMatches.toBytes(fmatch1)
        val bytes2 = FlowMatches.toBytes(fmatch2)

        And("Being encoded in a byte buffer")
        val bb = ByteBuffer.allocateDirect(64*1024)
        bb.put(bytes1)
        bb.put(bytes2)
        bb.flip()

        When("Pushed into the native flow list")
        bb.position(0)
        bb.limit(bytes1.length)
        nativeList.pushFlowMatch(bb)
        bb.position(bytes1.length)
        bb.limit(bytes1.length + bytes2.length)
        nativeList.pushFlowMatch(bb)

        And("Clearing the original buffer")
        bb.clear()

        Then("The flow matches should be stored natively")
        nativeList.size() shouldBe 2

        When("Pulled one by one, should match the original ones")
        nativeList.popFlowMatch() shouldBe fmatch2
        nativeList.popFlowMatch() shouldBe fmatch1

        nativeList.size() shouldBe 0
    }

}
