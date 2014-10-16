/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.sdn.flows

import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.BePropertyMatchResult
import org.scalatest.matchers.BePropertyMatcher

@RunWith(classOf[JUnitRunner])
class WildcardFlowTest extends Suite with Matchers {

    def testCombineExpirationTimes() {
        val wf1 = WildcardFlow.apply(new WildcardMatch(), List(), 0, 0, 0)
        val wf2 = WildcardFlow.apply(new WildcardMatch(), List(), 0, 0, 0)

        val wf3 = WildcardFlow.apply(new WildcardMatch(), List(), 10, 0, 0)
        val wf4 = WildcardFlow.apply(new WildcardMatch(), List(), 0, 10, 0)

        val wf5 = WildcardFlow.apply(new WildcardMatch(), List(), 2, 0, 0)
        val wf6 = WildcardFlow.apply(new WildcardMatch(), List(), 0, 2, 0)

        val wf7 = WildcardFlow.apply(new WildcardMatch(), List(), 1, 1, 0)

        var wfc1 = wf1.combine(wf2)
        wfc1.getHardExpirationMillis should be (0)
        wfc1.getIdleExpirationMillis should be (0)

        wfc1 = wf1.combine(wf3)
        wfc1.getHardExpirationMillis should be (10)
        wfc1.getIdleExpirationMillis should be (0)

        wfc1 = wf1.combine(wf4)
        wfc1.getHardExpirationMillis should be (0)
        wfc1.getIdleExpirationMillis should be (10)

        wfc1 = wf3.combine(wf4)
        wfc1.getHardExpirationMillis should be (10)
        wfc1.getIdleExpirationMillis should be (10)

        wfc1 = wf3.combine(wf5)
        wfc1.getHardExpirationMillis should be (2)
        wfc1.getIdleExpirationMillis should be (0)

        wfc1 = wf3.combine(wf6)
        wfc1.getHardExpirationMillis should be (10)
        wfc1.getIdleExpirationMillis should be (2)

        wfc1 = wf3.combine(wf7)
        wfc1.getHardExpirationMillis should be (1)
        wfc1.getIdleExpirationMillis should be (1)

        wfc1 = wf3.combine(wf6).combine(wf7)
        wfc1.getHardExpirationMillis should be (1)
        wfc1.getIdleExpirationMillis should be (1)

    }

}
