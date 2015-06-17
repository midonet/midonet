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

import com.typesafe.scalalogging.Logger

import org.midonet.sdn.flows.FlowTagger.FlowTag

trait FlowTagIndexer extends FlowIndexer {
    private val tagToFlows = new HashMap[FlowTag, Set[ManagedFlow]]()
    val log: Logger

    abstract override def registerFlow(flow: ManagedFlow): Unit = {
        super.registerFlow(flow)
        val numTags = flow.tags.size()
        var i = 0
        while (i < numTags) {
            getOrAdd(flow.tags.get(i)).add(flow)
            i += 1
        }
    }

    abstract override def removeFlow(flow: ManagedFlow): Unit = {
        super.removeFlow(flow)
        val numTags = flow.tags.size()
        var i = 0
        while (i < numTags) {
            val flows = tagToFlows.get(flow.tags.get(i))
            if (flows ne null)
                flows.remove(flow)
            i += 1
        }
    }

    def invalidateFlowsFor(tag: FlowTag): Unit = {
        val flows = tagToFlows.remove(tag)
        log.debug(s"Invalidating ${if (flows ne null) flows.size() else 0} flows for tag $tag")
        if (flows ne null) {
            val it = flows.iterator()
            while (it.hasNext) {
                removeFlow(it.next())
            }
        }
    }

    private def getOrAdd(tag: FlowTag): Set[ManagedFlow] = {
        var set = tagToFlows.get(tag)
        if (set eq null) {
            set = Collections.newSetFromMap(new IdentityHashMap())
            tagToFlows.put(tag, set)
        }
        set
    }
}
