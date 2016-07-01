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

package org.midonet.midolman.state

import java.util.UUID

import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.state.NatState.NatBinding
import org.midonet.packets.IPv4Addr
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.logging.Logger

object NoOpNatLeaser extends NatLeaser {

    override val allocator: NatBlockAllocator = null
    override val log: Logger = null
    override val clock: NanoClock = null

    private val binding = NatBinding(IPv4Addr.fromString("0.0.0.0"), 0)

    override def allocateNatBinding(deviceId: UUID,
                                    destinationIp: IPv4Addr,
                                    destinationPort: Int,
                                    natTargets: Array[NatTarget])
    : NatBinding = binding

    override def freeNatBinding(deviceId: UUID,
                                destinationIp: IPv4Addr,
                                destinationPort: Int,
                                binding: NatBinding): Unit = {}

    override def obliterateUnusedBlocks(): Unit = {}
}
