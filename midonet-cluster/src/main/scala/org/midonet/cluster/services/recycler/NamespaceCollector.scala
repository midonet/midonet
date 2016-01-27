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

import scala.collection.JavaConverters._
import scala.concurrent.Future

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.recycler.Recycler.RecyclingContext

/**
  * Collects the current state namespaces from the NSDB, and updates the list
  * of namespaces from the specified [[RecyclingContext]]. This list is
  * compared to the current list of hosts, to determine the obsolete namespaces.
  */
object NamespaceCollector extends BackgroundCallback {

    def apply(context: RecyclingContext): Future[RecyclingContext] = {
        context.log debug "Collecting current state namespaces " +
                          s"${context.step()}"
        context.curator.getChildren
               .inBackground(NamespaceCollector, context,
                             context.executor)
               .forPath(context.store.statePath(context.version))
        context.future
    }

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[RecyclingContext]
        if (event.getResultCode == Code.OK.intValue()) {
            context.namespaces = event.getChildren.asScala.toSet
            context.log debug s"Collected ${context.namespaces.size} namespaces"
            context.success()
        } else {
            context fail event
        }
    }

}
