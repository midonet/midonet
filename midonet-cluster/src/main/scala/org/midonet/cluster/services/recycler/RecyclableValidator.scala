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

package org.midonet.cluster.services.recycler

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.recycler.Recycler.RecyclingContext

/**
  * Verifies that the current NSDB is recyclable by checking that the last time
  * the root ZOOM znode was last modified at a moment in the past before the
  * current time minus the current recycling interval. If the NSDB is recyclable
  * the object will write to the root znode to update its last modified
  * timestamp, with the completion being handled by the [[RecyclableValidator]].
  */
object RecyclableValidator extends BackgroundCallback {

    private val Data = Array[Byte](1)

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[RecyclingContext]
        if (event.getResultCode == Code.OK.intValue()) {
            val time = context.clock.time
            if (time - event.getStat.getMtime > context.interval.toMillis) {
                context.log debug s"Marking NSDB for recycling at $time " +
                                  s"${context.step()}"
                context.curator.setData()
                       .withVersion(event.getStat.getVersion)
                       .inBackground(RecyclerValidator, context,
                                     context.executor)
                       .forPath(context.store.basePath, Data)
            } else {
                context.log debug "Skipping NSDB recycling: already recycled " +
                                  s"at ${event.getStat.getMtime} current " +
                                  s"time is $time"
                context.skip()
            }
        } else {
            context fail event
        }
    }

}
