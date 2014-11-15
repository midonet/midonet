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

package org.midonet.midolman.util.mock

import java.util.{Map => JMap}

import org.midonet.midolman.FlowController.FlowRemoveCommand
import org.midonet.midolman.flows.FlowEjector
import org.midonet.odp.{Flow, FlowMatch}

class MockFlowEjector(val flowsTable: JMap[FlowMatch, Flow] = null) extends FlowEjector(32) {
    var flowDelCb: Flow => Unit = _

    override def eject(flowDelete: FlowRemoveCommand): Boolean = {
        if (flowDelCb ne null) {
            flowDelCb(new Flow(flowDelete.flowMatch))
        }
        if (flowsTable ne null) {
            flowsTable.remove(flowDelete.flowMatch)
        }
        true
    }

    def flowDeleteSubscribe(cb: Flow => Unit): Unit =
        flowDelCb = cb
}
