/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import com.typesafe.scalalogging.Logger
import org.slf4j.helpers.NOPLogger

import org.scalatest.{OneInstancePerTest, BeforeAndAfter, GivenWhenThen,
                      FeatureSpec, Matchers}

import org.midonet.midolman.DeduplicationActor.ActionsCache
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.{FlowKeys, FlowAction}

class ActionsCacheTest extends FeatureSpec
                       with Matchers
                       with GivenWhenThen
                       with BeforeAndAfter
                       with OneInstancePerTest {

    val logger = Logger(NOPLogger.NOP_LOGGER)

    feature("ActionsCache implements a cache that can be expired") {
        scenario("expiration ring buffer circles around") {
            Given("an empty actions cache")
            val ac = new ActionsCache(4, logger)

            When("adding and expiring FlowMatches")
            (1 to 4 * 2) foreach { _ =>
                val fm = new FlowMatch
                val idx = ac.getSlot()
                ac.actions.put(fm, new java.util.LinkedList[FlowAction]())
                ac.pending(idx) = fm
                ac.clearProcessedFlowMatches()
            }

            Then("the cache should be empty")
            ac.actions should be (empty)
        }

        scenario("all expired FlowMatches are cleaned") {
            Given("a full actions cache")
            val ac = new ActionsCache(4, logger)
            1 to 4 foreach { i =>
                val fm = new FlowMatch().addKey(FlowKeys.inPort(i))
                val idx = ac.getSlot()
                ac.actions.put(fm, new java.util.LinkedList[FlowAction]())
                ac.pending(idx) = fm
            }

            When("cleaning expired FlowMatches")
            ac.clearProcessedFlowMatches()

            Then("the cache should be empty")
            ac.actions should be (empty)
        }


        scenario("thread spins waiting for cache entries to expire") {
            Given("a full actions cache")
            val ac = new ActionsCache(2, logger)
            val fm1 = new FlowMatch
            val idx1 = ac.getSlot()
            ac.actions.put(fm1, new java.util.LinkedList[FlowAction]())

            val fm2 = new FlowMatch().addKey(FlowKeys.vlan(1))
            val idx2 = ac.getSlot()
            ac.actions.put(fm2, new java.util.LinkedList[FlowAction]())

            When("calling getSlot")
            Thread.currentThread().interrupt()
            try {
                ac.getSlot()
            } catch {
                case e: InterruptedException =>
            }

            Then("the thread spins until entries are expired")
            Thread.currentThread().isInterrupted should be (false)
            ac.pending(idx1) = fm1
            ac.getSlot()
            ac.actions should have size 1
        }
    }
}
