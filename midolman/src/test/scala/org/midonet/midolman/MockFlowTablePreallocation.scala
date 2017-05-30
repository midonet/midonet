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

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.FlowExpirationIndexer.ExpirationQueue
import org.midonet.midolman.flows.ManagedFlowImpl
import org.midonet.midolman.monitoring.MeterRegistry
import org.midonet.util.collection.ArrayObjectPool

class MockFlowTablePreallocation(config: MidolmanConfig)
        extends FlowTablePreallocationImpl(config) {
    override def allocateAndTenure() {}

    override def takeIndexToFlow(): Array[ManagedFlowImpl] =
        new Array[ManagedFlowImpl](maxFlows)
    override def takeManagedFlowPool(): ArrayObjectPool[ManagedFlowImpl] =
        new ArrayObjectPool[ManagedFlowImpl](
            maxFlows, new ManagedFlowImpl(_))
    override def takeMeterRegistry(): MeterRegistry =
        MeterRegistry.newOnHeap(maxFlows)
    override def takeErrorExpirationQueue(): ExpirationQueue =
        new ExpirationQueue(maxFlows/3)
    override def takeFlowExpirationQueue(): ExpirationQueue =
        new ExpirationQueue(maxFlows)
    override def takeStatefulFlowExpirationQueue(): ExpirationQueue =
        new ExpirationQueue(maxFlows)
    override def takeTunnelFlowExpirationQueue(): ExpirationQueue =
        new ExpirationQueue(maxFlows/3)
}
