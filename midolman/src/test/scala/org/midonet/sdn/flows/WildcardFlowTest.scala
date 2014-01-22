package org.midonet.sdn.flows

import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.BePropertyMatcher
import org.scalatest.matchers.BePropertyMatchResult
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.Suite

@RunWith(classOf[JUnitRunner])
class WildcardFlowTest extends Suite with ShouldMatchers {

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
