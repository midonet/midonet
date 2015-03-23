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

package org.midonet.util.concurrent

import java.util.concurrent.ThreadLocalRandom

import org.junit.runner.RunWith
import org.midonet.util.concurrent.WakerUpper.Parkable
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import scala.compat.Platform

@RunWith(classOf[JUnitRunner])
class WakerUpperTest extends FeatureSpec with Matchers {

    feature ("WakerUpper wakes blocked threads") {

        scenario ("WakerUpper is activated when a thread blocks") {
            val parkStart = Platform.currentTime
            new Parkable {
                override def shouldWakeUp(): Boolean =
                    Platform.currentTime > parkStart + 100
            } park()
        }

        scenario ("WakerUpper supports multiple concurrent threads blocking") {
            val timesToWakeUp = 500
            val threads = (0 until 4) map { _ =>
                new Thread() with Parkable {
                    @volatile var timesWokenUp = 0

                    override def run(): Unit =
                            park(0)

                    override def shouldWakeUp(): Boolean =
                        if (this eq Thread.currentThread()) {
                            Thread.sleep(2)
                            timesWokenUp >= timesToWakeUp
                        } else if (ThreadLocalRandom.current().nextInt(100) < 25) {
                            timesWokenUp += 1
                            true
                        } else {
                            false
                        }
                }
            }

            threads foreach (_.start())
            threads foreach (_.join())
            threads foreach (_.timesWokenUp should be (timesToWakeUp))
        }

        scenario ("Other sources of wake up are handled gracefully") {
            val timesToWakeUp = 500
            val threads = (0 until 3) map { _ =>
                new Thread() with Parkable {
                    @volatile var timesWokenUp = 0

                    override def run(): Unit =
                        while (timesWokenUp < timesToWakeUp) {
                            try {
                                park(0)
                            } catch { case _: InterruptedException =>
                            }
                        }

                    override def shouldWakeUp(): Boolean =
                        if (this eq Thread.currentThread()) {
                            Thread.sleep(2)
                            timesWokenUp >= timesToWakeUp
                        } else if (ThreadLocalRandom.current().nextInt(100) < 25) {
                            timesWokenUp += 1
                            true
                        } else {
                            false
                        }
                    }
                } toArray

            @volatile var stopped = false
            val chaosThread = new Thread() {
                override def run(): Unit =
                    while (!stopped) {
                        threads(ThreadLocalRandom.current().nextInt(0, 3)).interrupt()
                        Thread.sleep(2)
                    }
            }

            threads foreach (_.start())
            chaosThread.start()
            threads foreach (_.join())
            stopped = true
            chaosThread.join()

            threads foreach (_.timesWokenUp should be (timesToWakeUp))
      }
    }
}
