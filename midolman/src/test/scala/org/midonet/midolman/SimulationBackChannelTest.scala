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

import org.scalatest.{BeforeAndAfter, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class SimulationBackChannelTest extends FeatureSpec with Matchers with BeforeAndAfter {

    val checkTriggers = new AtomicInteger(0)
    var backChannel: ShardedSimulationBackChannel = null
    var p1, p2, p3: SimulationBackChannel = _

    var _processedMsgs: List[BackChannelMessage] = Nil

    def processedMsgs = {
        val ret = _processedMsgs
        _processedMsgs = Nil
        ret
    }

    var handler = new BackChannelHandler {
        override def handle(message: BackChannelMessage): Unit = {
            _processedMsgs ::= message
        }
    }

    case class Message(text: String) extends BackChannelMessage

    before {
        checkTriggers.set(0)
        backChannel = new ShardedSimulationBackChannel(() => checkTriggers.incrementAndGet())
        p1 = backChannel.registerProcessor()
        p2 = backChannel.registerProcessor()
        p3 = backChannel.registerProcessor()
    }


    feature("delivers and handles messages") {
        scenario("a message sent to one shard ends up in all shards") {
            p1.tell(Message("foo"))
            p1.process(handler)
            processedMsgs should be (List(Message("foo")))
            p2.process(handler)
            processedMsgs should be (List(Message("foo")))
            p3.process(handler)
            processedMsgs should be (List(Message("foo")))
        }

        scenario("a message sent to the parent channel ends up in all shards") {
            backChannel.tell(Message("foo"))
            p1.process(handler)
            processedMsgs should be (List(Message("foo")))
            p2.process(handler)
            processedMsgs should be (List(Message("foo")))
            p3.process(handler)
            processedMsgs should be (List(Message("foo")))
        }

        scenario("shards report correctly whether they contain messages") {
            backChannel.tell(Message("foo"))
            backChannel.hasMessages should be (true)
            p1.hasMessages should be (true)
            p1.process(handler)
            p1.hasMessages should be (false)

            backChannel.hasMessages should be (true)
            p2.hasMessages should be (true)
            p2.process(handler)
            p2.hasMessages should be (false)

            backChannel.hasMessages should be (true)
            p3.hasMessages should be (true)
            p3.process(handler)
            p3.hasMessages should be (false)

            backChannel.hasMessages should be (false)
        }

    }
    feature("calls the trigger callback") {
        scenario("from the parent") {
            backChannel.tell(Message("bar"))
            checkTriggers.get() should be (1)
        }

        scenario("from a child shard") {
            p1.tell(Message("bar"))
            checkTriggers.get() should be (1)
            p2.tell(Message("bar"))
            checkTriggers.get() should be (2)
            p3.tell(Message("bar"))
            checkTriggers.get() should be (3)
        }
    }

}
