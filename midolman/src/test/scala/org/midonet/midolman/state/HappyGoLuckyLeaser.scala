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

package org.midonet.midolman.state

import java.util.UUID

import org.slf4j.helpers.NOPLogger

import org.midonet.midolman.rules.NatTarget
import org.midonet.packets.IPv4Addr
import org.midonet.packets.NatState.NatBinding
import org.midonet.util.concurrent.MockClock
import org.midonet.util.logging.Logger

object HappyGoLuckyLeaser extends NatLeaser {

    override val log = Logger(NOPLogger.NOP_LOGGER)
    override val allocator: NatBlockAllocator = new MockNatBlockAllocator
    override val clock = new MockClock

    override def allocateNatBinding(deviceId: UUID,
                                    destinationIp: IPv4Addr,
                                    destinationPort: Int,
                                    natTargets: Array[NatTarget]): NatBinding =
        NatBinding(natTargets(0).nwStart, natTargets(0).tpStart)
}
