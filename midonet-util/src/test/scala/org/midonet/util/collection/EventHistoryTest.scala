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

package org.midonet.util.collection

import scala.collection.JavaConversions._

import org.junit.runner.RunWith

import org.scalatest.{GivenWhenThen, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.util.collection.EventHistory._

@RunWith(classOf[JUnitRunner])
class EventHistoryTest extends FeatureSpec with GivenWhenThen with Matchers {
    scenario("The event history keeps a sliding window of events") {

        Given("A list of events of the history size plus delta.")
        val eventHistory = new EventHistory[Int](1024)
        val historySize = 1024
        val historyDelta = 1
        val eventCount = historySize + historyDelta

        When("Invalidate all tags.")
        for (tag <- 0 until eventCount) {
            eventHistory.put(tag)
        }

        val youngest = eventHistory.latest
        val oldest = youngest - historySize + 1

        Then ("Event not valid (EventSearchWindowMissed) for lastSeen in" +
              " [0, oldest - 1)")
        for (lastSeen <- -1L until oldest - 1) {
            // 0 < tagCount
            eventHistory.existsSince(lastSeen, Set(0)) should be (EventSearchWindowMissed)
            eventHistory.existsSince(lastSeen, Set(eventCount - 1)) should be (EventSearchWindowMissed)
        }

        // Test 1
        And ("Events valid (EventNotSeen) for lastSeen >= oldest - 1 and" +
                " events [0, historyDelta).")
        // Test 2
        And ("Events not valid (EventSeen) for lastSeen >= oldest - 1 and" +
                " events [lastSeen - oldest + historySize + 1, historySize)")
        // Test 3
        And ("Events valid (EventNotSeen) for lastSeen >= oldest - 1 and" +
                " events [historyDelta, lastSeen - oldest + historySize]")
        for (lastSeen <- oldest - 1 until eventCount) {
            // Test 1: condition 0 < historyDelta
            eventHistory.existsSince(lastSeen, Set(0)) should be (EventNotSeen)
            eventHistory.existsSince(lastSeen, Set(historyDelta - 1)) should be (EventNotSeen)

            // Test 2
            if ((lastSeen - oldest + historyDelta + 1).toInt < historySize) {
                eventHistory.existsSince(lastSeen,
                    Set((lastSeen - oldest + historyDelta + 1).toInt)) should be (EventSeen)
                eventHistory.existsSince(lastSeen, Set(historySize - 1)) should be (EventSeen)
            }

            // Test 3
            if (historyDelta <= (lastSeen - oldest + historyDelta).toInt) {
                eventHistory.existsSince(lastSeen, Set(historyDelta)) should be (EventNotSeen)
                eventHistory.existsSince(lastSeen,
                    Set((lastSeen - oldest + historyDelta).toInt)) should be (EventNotSeen)
            }
        }
    }
}
