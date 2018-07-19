/*
 * Copyright 2018 Midokura SARL
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

package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.ErrorCode
import org.midonet.insights.Insights
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.FlowMatch

@RunWith(classOf[JUnitRunner])
class FlowControllerDeleterTest extends MidolmanSpec {

    var deleter: FlowControllerDeleter = _

    override def beforeTest(): Unit = {
        val preallocation = new MockFlowTablePreallocation(config)
        deleter = new FlowControllerDeleterImpl(
            flowProcessor,
            0,
            preallocation.takeMeterRegistry(),
            Insights.NONE)
    }

    def test(initSeq: List[Boolean]): Unit = {
        var seq = initSeq
        flowProcessor.flowDeleteSubscribe((_, obs) => {
            if (seq.isEmpty) {
                // success
                obs.onCompleted()
                true
            } else {
                val h = seq.head
                seq = seq.tail
                if (h) {
                    // tryEject success but request fails
                    obs.onError(new NetlinkException(ErrorCode.EBUSY, 200))
                    true
                } else {
                    // tryEject failure
                    false
                }
            }
        })
        deleter.removeFlowFromDatapath(new FlowMatch, 100)
        while (deleter.shouldProcess) {
            deleter.processCompletedFlowOperations()
        }
        if (!seq.isEmpty) {
            fail("Unexpected exit")
        }
    }

    feature("Retries failed requests") {
        scenario("no failure") {
            test(List())
        }

        scenario("tryEject failure") {
            test(List(false, false, false, false, false))
        }

        scenario("netlink failure") {
            test(List(true, true, true, true, true))
        }

        // MNA-1301
        // scenario("mixed failure") {
        //     test(List(false, true, false, true, false, true))
        // }
    }
}
