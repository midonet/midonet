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

import org.midonet.cluster.services.recycler.Recycler.{ChildContext, RecyclingContext}

/**
  * Recycles the NSDB znodes for state tables corresponding to deleted topology
  * objects, by collecting all object tables znodes corresponding to objects
  * previously collected on the current [[RecyclingContext]]. Upon collecting
  * this information, the object validates which orphan tables can be deleted,
  * and handles the completion of each individual recycling operation by
  * updating the number of recycled objects in context.
  */
object TableRecycler extends BackgroundCallback {

    def apply(context: RecyclingContext): Future[RecyclingContext] = {
        context.log debug s"Collecting tables ${context.step()}"
        val futures = for (clazz <- context.store.classes) yield {
            val childContext = new ChildContext(context, clazz)
            context.curator.getChildren
                   .inBackground(TableCollector, childContext, context.executor)
                   .forPath(context.store.tablesClassPath(clazz, context.version))
            childContext.future
        }
        implicit val ec = context.ec
        Future.sequence(futures) onComplete {
            case Success(_) => TableValidator(context)
            case Failure(e) => context fail e
        }
        context.future
    }

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[ChildContext]
        if (event.getResultCode == Code.OK.intValue()) {
            context.parent.deletedTables.incrementAndGet()
        } else {
            context.parent.failedTables.incrementAndGet()
        }
        context success true
    }

}
