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

package org.midonet.util.concurrent

import java.lang.Thread.State
import scala.concurrent.duration._

import com.lmax.disruptor._
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers, OneInstancePerTest}

import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class BackchannelEventProcessorTest extends FeatureSpec
                                    with Matchers
                                    with OneInstancePerTest
                                    with MidonetEventually {

    feature ("BackchannelEventProcessor receives events from the " +
             "ring buffer and backchannel") {
        val backchannel = new Backchannel {
            var processed = 0
            var hasWork = false
            override def shouldProcess() = hasWork
            override def process(): Unit = processed += 1
        }

        val ringBuffer = RingBuffer.createSingleProducer(new EventFactory[Object] {
            override def newInstance(): Object = new Object
        }, 16)

        val handler = new EventPoller.Handler[Object] {
            var handled = 0

            override def onEvent(event: Object, sequence: Long,
                                 endOfBatch: Boolean): Boolean = {
                handled += 1
                true
            }
        }

        val processor = new BackchannelEventProcessor[Object](ringBuffer, handler, backchannel)
        val t = new Thread() {
            override def run() = processor.run()
        }
        t.setDaemon(true)
        t.start()

        scenario ("Processor reacts to new events") {
            eventually(new Timeout(1 minute)) {
                backchannel.processed should be (0)
                t.getState should be (State.WAITING)
            }
            backchannel.shouldProcess should be (false)
            handler.handled should be (0)

            ringBuffer.publish(0)
            ringBuffer.isPublished(0) should be (true)
            eventually {
                handler.handled should be (1)
                backchannel.processed should be (2) /* 1 after wake-up and 1 after the event being handled */
            }
        }

        scenario ("Thread reacts to work from backchannel") {
            eventually(new Timeout(1 minute)) {
                backchannel.processed should be (0)
                t.getState should be (State.WAITING)
            }
            backchannel.shouldProcess should be (false)

            backchannel.hasWork = true
            eventually {
                handler.handled should be (0)
                backchannel.processed should be >= 1024
            }
        }

        scenario ("Thread reacts to processor shutdown") {
            eventually(new Timeout(1 minute)) {
                backchannel.processed should be (0)
                t.getState should be (State.WAITING)
            }
            backchannel.shouldProcess should be (false)

            processor.halt()
            t.join()
        }

        scenario ("Backchannel is queried at batch boundary") {
            eventually(new Timeout(1 minute)) {
                backchannel.processed should be (0)
                t.getState should be (State.WAITING)
            }
            backchannel.shouldProcess should be (false)

            ringBuffer.publish(0, 1)
            ringBuffer.isPublished(0) should be (true)
            ringBuffer.isPublished(1) should be (true)
            eventually {
                handler.handled should be (2)
                backchannel.processed should be (2)
            }
        }
    }
}

