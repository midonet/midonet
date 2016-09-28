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
package org.midonet.midolman

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable

import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import org.midonet.midolman.SimulationBackChannel.{BackChannelMessage, Broadcast}

@RunWith(classOf[JUnitRunner])
class SimulationBackChannelTest extends FeatureSpec with Matchers with BeforeAndAfter {

    private val checkTriggers = new AtomicInteger(0)
    private var backChannel: ShardedSimulationBackChannel = null
    private var p1, p2, p3, p4: SimulationBackChannel = _
    private var c1: mutable.ArrayBuffer[BackChannelMessage] = _

    private var _processedMsgs: List[BackChannelMessage] = Nil

    def processedMsgs = {
        val ret = _processedMsgs
        _processedMsgs = Nil
        ret
    }

    def process(backChannel: SimulationBackChannel): Unit =
        while (backChannel.hasMessages)
            _processedMsgs ::= backChannel.poll()

    case class Message(text: String) extends BackChannelMessage

    before {
        checkTriggers.set(0)
        backChannel = new ShardedSimulationBackChannel()
        c1 = new mutable.ArrayBuffer[BackChannelMessage]()
        p1 = backChannel.registerProcessor()
        p2 = backChannel.registerProcessor()
        p3 = backChannel.registerProcessor()
        p4 = backChannel.registerProcessor(c1 += _)
    }

    feature("delivers and handles messages") {
        scenario("a message sent to one queue shard remains in that shard") {
            p1.tell(Message("foo"))
            process(p1)
            processedMsgs should be (List(Message("foo")))
            process(p2)
            processedMsgs should be (Nil)
            process(p3)
            processedMsgs should be (Nil)
            c1 shouldBe empty
        }

        scenario("a message sent to one callback shard remains in that shard") {
            p4.tell(Message("foo"))
            process(p1)
            processedMsgs should be (Nil)
            process(p2)
            processedMsgs should be (Nil)
            process(p3)
            processedMsgs should be (Nil)
            c1 should contain only Message("foo")
        }

        scenario("a broadcast message sent to one queue shard ends up in all shards") {
            p1.tell(new Message("foo") with Broadcast)
            process(p1)
            processedMsgs should be (List(Message("foo")))
            process(p2)
            processedMsgs should be (List(Message("foo")))
            process(p3)
            processedMsgs should be (List(Message("foo")))
            c1 should contain only Message("foo")
        }

        scenario("a broadcast message sent to one callback shard ends up in all shards") {
            p4.tell(new Message("foo") with Broadcast)
            process(p1)
            processedMsgs should be (List(Message("foo")))
            process(p2)
            processedMsgs should be (List(Message("foo")))
            process(p3)
            processedMsgs should be (List(Message("foo")))
            c1 should contain only Message("foo")
        }

        scenario("cannot process messaged on the main channel") {
            intercept[UnsupportedOperationException] {
                backChannel.poll()
            }
        }

        scenario("a non-broadcast message sent to the parent channel is not support") {
            intercept[IllegalArgumentException] {
                backChannel.tell(Message("foo"))
            }
        }

        scenario("cannot poll on the callback shard") {
            intercept[UnsupportedOperationException] {
                p4.poll()
            }
        }

        scenario("a broadcast message sent to the parent channel ends up in all shards") {
            backChannel.tell(new Message("foo") with Broadcast)
            process(p1)
            processedMsgs should be (List(Message("foo")))
            process(p2)
            processedMsgs should be (List(Message("foo")))
            process(p3)
            processedMsgs should be (List(Message("foo")))
            c1 should contain only Message("foo")
        }

        scenario("shards report correctly whether they contain messages") {
            backChannel.tell(new Message("foo") with Broadcast)
            backChannel.hasMessages should be (true)
            p1.hasMessages should be (true)
            process(p1)
            p1.hasMessages should be (false)

            backChannel.hasMessages should be (true)
            p2.hasMessages should be (true)
            process(p2)
            p2.hasMessages should be (false)

            backChannel.hasMessages should be (true)
            p3.hasMessages should be (true)
            process(p3)
            p3.hasMessages should be (false)

            p4.hasMessages shouldBe false

            backChannel.hasMessages should be (false)
        }
    }
}
