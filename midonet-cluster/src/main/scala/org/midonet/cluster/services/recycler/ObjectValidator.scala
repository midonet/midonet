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
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.recycler.Recycler.{ChildContext, RecyclingContext}

/**
  * Validates whether the znode corresponding to an object state is recyclable,
  * by checking whether the creation timestamp of the namespace NSDB znode is
  * previous to the moment that the recycling has started. Upon successful
  * validation, the object deletes the state znode, with the completion being
  * handled by the [[ObjectRecycler]].
  */
object ObjectValidator extends BackgroundCallback {

    def apply(context: RecyclingContext): Future[RecyclingContext] = {
        context.log debug s"Validating object state for ${context.hosts.size} " +
                          s"namespaces ${context.step()}"
        // Recycle the state objects that are not present in the model objects.
        val futures = for ((host, clazz) <- context.stateObjects.keys().asScala;
                           id <- context.stateObjects.get((host, clazz))
                           if !context.modelObjects.get(clazz).contains(id)) yield {
            context.log debug s"Recycling object state ${clazz.getSimpleName}/" +
                              s"$id at host $host"
            val path = context.store.stateObjectPath(host, clazz, id, context.version)
            val childContext = new ChildContext(context, path)

            context.curator.getData
                   .inBackground(ObjectValidator, childContext, context.executor)
                   .forPath(path)
            childContext.future
        }

        if (futures.isEmpty) {
            context.log debug s"No objects to verify and delete ${context.step()}"
        }

        implicit val ec = context.ec
        context.completeWith(Future.sequence(futures))
        context.future
    }

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[ChildContext]
        if (event.getResultCode == Code.OK.intValue() &&
            event.getStat.getCtime < context.parent.timestamp) {
            // We only delete those objects that have been created before
            // the beginning of the recycling task, to ensure that we do
            // not delete the state for a new object.
            context.parent.log debug s"Deleting object ${event.getPath} with " +
                                     s"timestamp ${event.getStat.getCtime}"
            context.parent.curator.delete()
                   .deletingChildrenIfNeeded()
                   .withVersion(event.getStat.getVersion)
                   .inBackground(ObjectRecycler, context, context.parent.executor)
                   .forPath(event.getPath)
        } else {
            if (event.getResultCode != Code.OK.intValue()) {
                context.parent.failedObjects.incrementAndGet()
            }
            context success true
        }
    }

}
