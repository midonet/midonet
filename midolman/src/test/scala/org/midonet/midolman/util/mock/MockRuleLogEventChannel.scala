/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.util.mock

import java.util.UUID

import org.midonet.logging.rule.Result
import org.midonet.midolman.logging.rule.RuleLogEventChannel
import org.midonet.midolman.rules.Rule
import org.midonet.midolman.simulation.Chain
import org.midonet.packets.IPAddr

class MockRuleLogEventChannel extends RuleLogEventChannel {

    override def handoff(loggerId: UUID, nwProto: Byte,
                         chain: Chain,
                         rule: Rule,
                         srcIp: IPAddr,
                         dstIp: IPAddr, srcPort: Int,
                         dstPort: Int,
                         result: Result): Long = 1L

    override def flush(): Unit = ()

    override def doStop(): Unit = notifyStopped()

    override def doStart(): Unit = notifyStarted()
}
