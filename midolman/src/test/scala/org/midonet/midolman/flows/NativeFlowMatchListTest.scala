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

import scala.collection.JavaConversions._
import org.junit.runner.RunWith
import org.midonet.netlink.{BytesUtil, NetlinkMessage}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpecLike, GivenWhenThen, Matchers}
import org.midonet.odp.{FlowMatch, FlowMatches}

@RunWith(classOf[JUnitRunner])
class NativeFlowMatchListTest extends FeatureSpecLike
                                      with Matchers
                                      with BeforeAndAfter
                                      with GivenWhenThen {

    val random = new Random
    var nativeList: NativeFlowMatchList = _

    private def serializeIntoBuffer(fm: FlowMatch): ByteBuffer = {
        val bb = BytesUtil.instance.allocateDirect(1024)

        for (key <- fm.getKeys) {
            val sizePos = bb.position
            bb.putShort(0)
            bb.putShort(key.attrId())
            val start = bb.position()
            val numBytes = key.serializeInto(bb)
            if (numBytes % 4 != 0) bb.put(new Array[Byte](numBytes % 4))
            bb.putShort(sizePos, (bb.position() - start + NetlinkMessage.ATTR_HEADER_LEN).toShort)
        }

        bb.flip()
        bb
    }

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
        val fmatch3 = FlowMatches.generateFlowMatch(random)
        val fmatch4 = FlowMatches.generateFlowMatch(random)
        val fmatch5 = FlowMatches.generateFlowMatch(random)

        And("Being encoded in a byte buffer")
        val bb1 = serializeIntoBuffer(fmatch1)
        val bb2 = serializeIntoBuffer(fmatch2)
        val bb3 = serializeIntoBuffer(fmatch3)
        val bb4 = serializeIntoBuffer(fmatch4)
        val bb5 = serializeIntoBuffer(fmatch5)

        When("Pushed into the native flow list")
        nativeList.pushFlowMatch(bb1)
        nativeList.pushFlowMatch(bb2)
        nativeList.pushFlowMatch(bb3)
        nativeList.pushFlowMatch(bb4)
        nativeList.pushFlowMatch(bb5)

        Then("The flow matches should be stored natively")
        nativeList.size() shouldBe 5

        When("Pulled one by one, should match the original ones")
        nativeList.popFlowMatch() shouldBe fmatch5
        nativeList.popFlowMatch() shouldBe fmatch4
        nativeList.popFlowMatch() shouldBe fmatch3
        nativeList.popFlowMatch() shouldBe fmatch2
        nativeList.popFlowMatch() shouldBe fmatch1

        nativeList.size() shouldBe 0
    }

}