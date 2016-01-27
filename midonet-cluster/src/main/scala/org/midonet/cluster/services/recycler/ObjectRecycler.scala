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

import scala.concurrent.Future
import scala.util.{Failure, Success}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.recycler.Recycler.{RecyclingContext, ChildContext}

/**
  * Recycles the state NSDB znodes corresponding to deleted topology objects,
  * by collecting all objects in the topology tree, and then the state znodes
  * corresponding to those objects for the namespaces of all current hosts. Upon
  * collecting this information, the object validates which orphan states can
  * be deleted, and handles the completion of each individual recycling operation
  * by updating the number of recycled objects in the [[RecyclingContext]].
  */
object ObjectRecycler extends BackgroundCallback {

    def apply(context: RecyclingContext): Future[RecyclingContext] = {
        context.log debug s"Collecting objects ${context.step()}"
        val modelFutures = for (clazz <- context.store.classes) yield {
            val childContext = new ChildContext(context, clazz)
            context.curator.getChildren
                   .inBackground(ObjectCollector, childContext, context.executor)
                   .forPath(context.store.classPath(clazz))
            childContext.future
        }
        val stateFutures = for (host <- context.hosts;
                                clazz <- context.store.classes) yield {
            val childContext = new ChildContext(context, (host, clazz))
            context.curator.getChildren
                   .inBackground(StateCollector, childContext, context.executor)
                   .forPath(context.store.stateClassPath(host, clazz,
                                                         context.version))
            childContext.future
        }
        implicit val ec = context.ec
        Future.sequence(modelFutures ++ stateFutures) onComplete {
            case Success(_) => ObjectValidator(context)
            case Failure(e) => context fail e
        }
        context.future
    }

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[ChildContext]
        if (event.getResultCode == Code.OK.intValue()) {
            context.parent.deletedObjects.incrementAndGet()
        } else {
            context.parent.failedObjects.incrementAndGet()
        }
        context success true
    }

}
