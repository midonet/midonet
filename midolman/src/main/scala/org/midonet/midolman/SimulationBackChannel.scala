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

import org.jctools.queues.MpscArrayQueue

import org.midonet.midolman.services.MidolmanActorsService
import org.midonet.util.concurrent.WakerUpper.Parkable

trait BackChannelMessage

trait SimulationBackChannel {
    def tell(message: BackChannelMessage): Unit
    def hasMessages: Boolean
    def process(handler: BackChannelHandler): Unit
}

trait BackChannelHandler {
    def handle(message: BackChannelMessage): Unit
}

object ShardedSimulationBackChannel {
    // TODO: having to pass the actorsService here, ugly as it
    //       may be, is an artifact of our bootstrap process
    def apply(as: MidolmanActorsService): ShardedSimulationBackChannel = {
        new ShardedSimulationBackChannel(
            () => PacketsEntryPoint.getRef()(as.system) ! CheckBackchannels
        )
    }
}

final class ShardedSimulationBackChannel(triggerChannelCheck: () => Unit)
    extends SimulationBackChannel {

    private val NO_SHARD: BackChannelShard = null
    private val processors = new ArrayList[BackChannelShard]()

    def registerProcessor(): BackChannelShard = {
        val processor = new BackChannelShard()
        processors.add(processor)
        processor
    }

    override def tell(msg: BackChannelMessage): Unit =
        tellOthers(NO_SHARD, msg)

    private def tellOthers(shardToSkip: BackChannelShard,
                           msg: BackChannelMessage): Unit = {
        var i = 0
        while (i < processors.size()) {
            val p = processors.get(i)
            if (p ne shardToSkip)
                p.offer(msg)
            i += 1
        }
        triggerChannelCheck()
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

     override def process(handler: BackChannelHandler): Unit =
        throw new Exception("Calling process on the main back channel is not supported")

    final class BackChannelShard extends SimulationBackChannel with Parkable {
        private val MAX_PENDING = 1024

        private val q = new MpscArrayQueue[BackChannelMessage](MAX_PENDING)

        private[ShardedSimulationBackChannel] def offer(msg: BackChannelMessage): Unit = {
            while (!q.offer(msg)) {
                park(retries = 0)
            }
        }

        /*
         * Broadcasts this tag to all invalidation processors.
         */
        override def tell(msg: BackChannelMessage): Unit = {
            offer(msg)
            tellOthers(this, msg)
        }

        override def hasMessages: Boolean = !q.isEmpty

        /**
         * Processes the messages in this instance.
         */
        override def process(handler: BackChannelHandler): Unit = {
            var msg: BackChannelMessage = null
            while ({ msg = q.poll(); msg } ne null) {
                handler.handle(msg)
            }
        }

        override def shouldWakeUp(): Boolean = hasMessages
    }
}
