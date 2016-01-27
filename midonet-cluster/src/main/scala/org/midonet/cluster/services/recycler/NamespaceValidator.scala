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

import org.midonet.cluster.services.recycler.Recycler.ChildContext

/**
  * Verifies whether a namespace can be deleted, by checking whether the
  * creation timestamp of the namespace NSDB znode is previous to the moment
  * that the recycling has started.
  */
object NamespaceValidator extends BackgroundCallback {

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[ChildContext]
        if (event.getResultCode == Code.OK.intValue() &&
            event.getStat.getCtime < context.parent.timestamp) {
            // We only delete those namespaces that have been created
            // before the beginning of the recycling task, to ensure that we
            // do not delete the namespace for a new host.
            context.parent.log debug s"Deleting namespace ${event.getPath} " +
                                     s"with timestamp ${event.getStat.getCtime}"
            context.parent.curator.delete()
                   .deletingChildrenIfNeeded()
                   .withVersion(event.getStat.getVersion)
                   .inBackground(NamespaceRecycler, context,
                                 context.parent.executor)
                   .forPath(event.getPath)
        } else {
            // Else, we ignore the namespace.
            if (event.getResultCode != Code.OK.intValue()) {
                context.parent.failedNamespaces.incrementAndGet()
            }
            context success true
        }
    }

}
