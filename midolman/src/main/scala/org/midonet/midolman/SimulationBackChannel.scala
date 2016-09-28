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

import scala.util.control.NonFatal

import org.jctools.queues.MpscLinkedQueue8

import org.midonet.util.concurrent.WakerUpper.Parkable

object SimulationBackChannel {

    type BackChannelCallback = (BackChannelMessage) => Unit

    trait BackChannelMessage
    trait Broadcast { this: BackChannelMessage => }

    trait BackChannelShard extends SimulationBackChannel {
        def offer(msg: BackChannelMessage): Unit
    }

}

trait SimulationBackChannel {
    def tell(message: SimulationBackChannel.BackChannelMessage): Unit
    def hasMessages: Boolean
    def poll(): SimulationBackChannel.BackChannelMessage
}

final class ShardedSimulationBackChannel extends SimulationBackChannel {
    import SimulationBackChannel._

    private val noShard: BackChannelShard = null
    private val processors = new ArrayList[BackChannelShard]()

    /**
      * Registers a queue-based back-channel shard.
      */
    def registerProcessor(): BackChannelShard = {
        processors.synchronized {
            val processor = new QueueBackChannelShard()
            processors.add(processor)
            processor
        }
    }

    /**
      * Registers a callback-based back-channel shard.
      */
    def registerProcessor(callback: BackChannelCallback): BackChannelShard = {
        processors.synchronized {
            val processor = new CallbackBackChannelShard(callback)
            processors.add(processor)
            processor
        }
    }

    override def tell(msg: BackChannelMessage): Unit =
        msg match {
            case msg: Broadcast =>
                tellOthers(noShard, msg)
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
            "Calling poll on the main back channel is not supported")

    /**
      * A back-channel shard that uses a multi-producer single-consumer queue.
      * Back-channel consumers must regularly poll the shard in order to fetch
      * the last back-channel messages.
      */
    final class QueueBackChannelShard extends BackChannelShard with Parkable {

        private val q = new MpscLinkedQueue8[BackChannelMessage]()

        override def offer(msg: BackChannelMessage): Unit = {
            while (!q.offer(msg)) {
                park(retries = 0)
            }
        }

        /**
         * Schedules this message on the current back-channel, and, if it is
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

    /**
      * A back-channel shard that uses a callback function to send back-channel
      * messages to consumers.
      */
    final class CallbackBackChannelShard(callback: (BackChannelMessage) => Unit)
        extends BackChannelShard {

        override def offer(msg: BackChannelMessage): Unit = {
            try callback(msg)
            catch { case NonFatal(e) => /* Catch all exceptions */ }
        }

        /**
          * Schedules this message on the current back-channel, and, if it is
          * a broadcast message, schedules it on all the remaining shards.
          */
        override def tell(msg: BackChannelMessage): Unit = {
            offer(msg)
            if (msg.isInstanceOf[BackChannelMessage with Broadcast])
                tellOthers(this, msg)
        }

        override def hasMessages: Boolean = false

        /**
          * Processes the messages in this instance.
          */
        override def poll(): BackChannelMessage = {
            throw new UnsupportedOperationException(
                "Calling poll on the callback back channel is not supported")
        }
    }
}
