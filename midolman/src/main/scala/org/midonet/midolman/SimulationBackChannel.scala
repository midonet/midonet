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

package org.midonet.midolman

import java.util.ArrayList

import org.jctools.queues.MpscLinkedQueue8

import org.midonet.util.concurrent.WakerUpper.Parkable

object SimulationBackChannel {
    trait BackChannelMessage
    trait Broadcast { this: BackChannelMessage => }
}

trait SimulationBackChannel {
    def tell(message: SimulationBackChannel.BackChannelMessage): Unit
    def hasMessages: Boolean
    def poll(): SimulationBackChannel.BackChannelMessage
}

final class ShardedSimulationBackChannel
    extends SimulationBackChannel {
    import SimulationBackChannel._

    private val NO_SHARD: BackChannelShard = null
    private val processors = new ArrayList[BackChannelShard]()

    def registerProcessor(): BackChannelShard = {
        val processor = new BackChannelShard()
        processors.add(processor)
        processor
    }

    override def tell(msg: BackChannelMessage): Unit =
        msg match {
            case msg: Broadcast =>
                tellOthers(NO_SHARD, msg)
            case _ =>
                throw new IllegalArgumentException(
                    "Scheduling a non-broadcast messaged on the main back " +
                    "channel is not supported")
        }


    private def tellOthers(shardToSkip: BackChannelShard,
                           msg: BackChannelMessage): Unit = {
        var i = 0
        while (i < processors.size()) {
            val p = processors.get(i)
            if (p ne shardToSkip)
                p.offer(msg)
            i += 1
        }
    }

    override def hasMessages: Boolean = {
        var i = 0
        while (i < processors.size()) {
            if (processors.get(i).hasMessages)
                return true
            i += 1
        }
        false
    }

    override def poll(): BackChannelMessage =
        throw new UnsupportedOperationException(
            "Calling process on the main back channel is not supported")

    final class BackChannelShard extends SimulationBackChannel with Parkable {

        private val q = new MpscLinkedQueue8[BackChannelMessage]()

        private[ShardedSimulationBackChannel] def offer(msg: BackChannelMessage): Unit = {
            while (!q.offer(msg)) {
                park(retries = 0)
            }
        }

        /**
         * Schedules this message on the current backchannel, and, if it is
         * a broadcast message, schedules it on all the remaining shards.
         */
        override def tell(msg: BackChannelMessage): Unit = {
            offer(msg)
            if (msg.isInstanceOf[BackChannelMessage with Broadcast])
                tellOthers(this, msg)
        }

        override def hasMessages: Boolean = !q.isEmpty

        /**
         * Processes the messages in this instance.
         */
        override def poll(): BackChannelMessage =
            q.poll()

        override def shouldWakeUp(): Boolean = hasMessages
    }
}
