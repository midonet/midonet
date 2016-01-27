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

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.zookeeper.KeeperException.Code

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.recycler.Recycler.{ChildContext, RecyclingContext}

/**
  * Recycles the orphan namespaces that have been previously collected in the
  * specified [[RecyclingContext]]. The class validates whether a namespace is
  * recyclable, and handles the completion of each deletion operation by
  * incrementing the number of recycled namespaces in the context.
  */
object NamespaceRecycler extends BackgroundCallback {

    private val ClusterNamespaceId = Seq(MidonetBackend.ClusterNamespaceId.toString)

    def apply(context: RecyclingContext): Future[RecyclingContext] = {
        context.log debug s"Deleting orphan namespaces ${context.step()}"
        // Never delete the cluster namespace.
        val namespaces = context.namespaces -- context.hosts -- ClusterNamespaceId
        val futures = for (namespace <- namespaces) yield {
            val childContext = new ChildContext(context)
            context.log debug s"Verifying and deleting namespace $namespace"
            context.curator.getData
                   .inBackground(NamespaceValidator, childContext, context.executor)
                   .forPath(context.store.stateNamespacePath(namespace, context.version))
            childContext.future
        }

        if (futures.isEmpty) {
            context.log debug "No namespaces to verify and delete"
        }

        context.completeWith(Future.sequence(futures)(Set.canBuildFrom, context.ec))
        context.future
    }

    override def processResult(client: CuratorFramework, event: CuratorEvent)
    : Unit = {
        val context = event.getContext.asInstanceOf[ChildContext]
        if (event.getResultCode == Code.OK.intValue()) {
            context.parent.deletedNamespaces.incrementAndGet()
        } else {
            context.parent.failedNamespaces.incrementAndGet()
        }
        context success true
    }

}
