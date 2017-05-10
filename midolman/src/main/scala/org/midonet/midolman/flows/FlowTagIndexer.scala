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

package org.midonet.midolman.flows

import java.util._

import org.midonet.midolman.logging.MidolmanLogging

import org.midonet.sdn.flows.FlowTagger.FlowTag

class FlowTagIndexer extends MidolmanLogging {
    private val tagToFlows = new HashMap[FlowTag, Set[ManagedFlowImpl]]()

    def indexFlowTags(flow: ManagedFlowImpl): Unit = {
        val numTags = flow.tags.size()
        var i = 0
        while (i < numTags) {
            getOrAdd(flow.tags.get(i)).add(flow)
            i += 1
        }
    }

    def removeFlowTags(flow: ManagedFlowImpl): Unit = {
        val numTags = flow.tags.size()
        var i = 0
        while (i < numTags) {
            val tag = flow.tags.get(i)
            val flows = tagToFlows.get(tag)
            if (flows ne null) {
                flows.remove(flow)
                if (flows.size() == 0)
                    tagToFlows.remove(tag)
            }
            i += 1
        }
    }

    def invalidateFlowsFor(tag: FlowTag): Iterator[ManagedFlowImpl] = {
        val flows = tagToFlows.remove(tag)
        log.debug(s"Invalidating ${if (flows ne null) flows.size() else 0} flows for tag $tag")
        if (flows ne null) {
            val iter = flows.iterator()
            while (iter.hasNext()) {
                removeFlowTags(iter.next())
            }
            flows.iterator()
        } else {
            Collections.emptyIterator()
        }
    }

    def flowsFor(tag: FlowTag): Set[ManagedFlowImpl] =
        tagToFlows.get(tag)

    private def getOrAdd(tag: FlowTag): Set[ManagedFlowImpl] = {
        var set = tagToFlows.get(tag)
        if (set eq null) {
            set = Collections.newSetFromMap(new IdentityHashMap())
            tagToFlows.put(tag, set)
        }
        set
    }
}
