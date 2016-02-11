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

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.recycler.Recycler.ChildContext

/**
  * Completes the collection of objects with state tables from the NSDB, and
  * updates the operation's recycling context.
  */
object TableCollector extends BackgroundCallback {

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[ChildContext]
        val clazz = context.tag.asInstanceOf[Class[_]]
        if (event.getResultCode == Code.OK.intValue()) {
            context.parent.log debug s"Collected ${event.getChildren.size} " +
                                     "objects with tables for class " +
                                     s"${clazz.getSimpleName}"
            context.parent.tableObjects
                   .putIfAbsent(clazz, event.getChildren.asScala.toSet)
            context success true
        } else {
            context fail event
        }
    }

}
