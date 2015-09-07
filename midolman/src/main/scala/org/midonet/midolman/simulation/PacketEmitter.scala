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

package org.midonet.midolman.simulation

import java.lang.{Integer => JInteger}
import java.util.{Queue, UUID}

import akka.actor.ActorRef

import org.midonet.midolman.CheckBackchannels
import org.midonet.packets.Ethernet

object PacketEmitter {
    trait GeneratedPacket

    case class GeneratedLogicalPacket(egressPort: UUID, eth: Ethernet)
        extends GeneratedPacket
    case class GeneratedPhysicalPacket(egressPort: JInteger, eth: Ethernet)
        extends GeneratedPacket
}

class PacketEmitter(queue: Queue[PacketEmitter.GeneratedPacket],
                    alert: ActorRef) {
    import PacketEmitter._

    def pendingPackets = queue.size()

    def schedule(genPacket: GeneratedPacket): Boolean =
        if (queue.offer(genPacket)) {
            true
        } else {
            false
        }

    def process(emit: GeneratedPacket => Unit): Unit = {
        var genPacket: GeneratedPacket = null
        while ({ genPacket = queue.poll(); genPacket } ne null) {
            emit(genPacket)
        }
    }

    def poll(): GeneratedPacket = queue.poll()
}
