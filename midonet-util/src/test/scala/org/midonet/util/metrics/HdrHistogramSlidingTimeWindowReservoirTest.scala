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

package org.midonet.util.metrics

import java.util.concurrent.TimeUnit
import com.codahale.metrics.Clock

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec}

@RunWith(classOf[JUnitRunner])
class HdrHistogramSlidingTimeWindowReservoirTest extends FeatureSpec with Matchers {

    class TestClock extends Clock {
        var nanoTime = 0L

        override def getTick: Long = nanoTime
    }

    feature("Sliding window histogram") {
        scenario("10 minute, 10 second steps") {
            val clock = new TestClock()
            val reservoir = new HdrHistogramSlidingTimeWindowReservoir(
                10, TimeUnit.MINUTES, 10, TimeUnit.SECONDS, clock)

            clock.nanoTime = 1234530233L
            reservoir.update(10)
            reservoir.update(20)

            var snap = reservoir.getSnapshot()
            snap.getMax shouldBe 20
            snap.getMin shouldBe 10

            snap = reservoir.getSnapshot()
            snap.getMax shouldBe 20
            snap.getMin shouldBe 10

            clock.nanoTime += TimeUnit.NANOSECONDS.convert(5, TimeUnit.MINUTES)
            reservoir.update(30)

            snap = reservoir.getSnapshot()
            snap.getMax shouldBe 30
            snap.getMin shouldBe 10

            // since we convert step to a power of 2, we may have to wait an
            // extra step for old values to be rolled out, since the power of
            // 2 step likely doesn't divide evenly into the window
            clock.nanoTime += TimeUnit.NANOSECONDS.convert(5, TimeUnit.MINUTES) +
                TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS)
            snap = reservoir.getSnapshot()
            snap.getMax shouldBe 30
            snap.getMin shouldBe 30
            snap.getMean shouldBe 30.0
            snap.getStdDev shouldBe 0
            snap.getValue(50) shouldBe 30.0
            reservoir.size shouldBe 1
        }

        scenario("Single big bucket") {
            val clock = new TestClock()

            val reservoir = new HdrHistogramSlidingTimeWindowReservoir(
                1, TimeUnit.MINUTES, 1, TimeUnit.MINUTES, clock)
            val snapshot = reservoir.getSnapshot()
            clock.nanoTime = 1
            reservoir.update(10)
            reservoir.update(20)

            var snap = reservoir.getSnapshot()
            snap.getMax shouldBe 20
            snap.getMin shouldBe 10

            clock.nanoTime += TimeUnit.NANOSECONDS.convert(59, TimeUnit.SECONDS)
            snap = reservoir.getSnapshot()
            snap.getMax shouldBe 20
            snap.getMin shouldBe 10

            clock.nanoTime += TimeUnit.NANOSECONDS.convert(2, TimeUnit.MINUTES)
            snap = reservoir.getSnapshot()
            snap.size shouldBe 0
            snap.getMax shouldBe 0
            snap.getMin shouldBe 0
        }
    }
}
