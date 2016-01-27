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

import org.apache.curator.framework.api.CuratorEvent
import org.apache.zookeeper.KeeperException.Code

import rx.Observable

import org.midonet.cluster.services.MidonetBackend
import org.midonet.util.functors.makeFunc1

/**
  * Recycles the orphan namespaces that have been previously collected in the
  * specified [[RecyclingContext]]. The class validates whether a namespace is
  * recyclable, and handles the completion of each deletion operation by
  * incrementing the number of recycled namespaces in the context.
  */
object NamespaceRecycler extends RecyclerCommons {

    private val ClusterNamespaceId = Seq(MidonetBackend.ClusterNamespaceId.toString)

    def apply(context: RecyclingContext): Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        context.log debug s"Deleting orphan namespaces ${context.step()}"
        // Never delete the cluster namespace.
        val namespaces = context.namespaces -- context.hosts -- ClusterNamespaceId
        val observables = for (namespace <- namespaces) yield {
            validate(context, namespace) flatMap makeFunc1 {
                verify(_, context, namespace)
            } flatMap makeFunc1 {
                delete(_, context, namespace)
            } flatMap makeFunc1 {
                process(_, context)
            }
        }

        if (observables.isEmpty) {
            context.log debug "No namespaces to verify and delete"
        }

        Observable.concat(Observable.merge(observables.asJava),
                          Observable.just(context))
    }

    private def validate(context: RecyclingContext, namespace: String)
    : Observable[CuratorEvent] = {
        context.log debug s"Verifying namespace $namespace"
        asObservable(context, throttle = false) {
            context.curator.getData
                   .inBackground(_, context, context.executor)
                   .forPath(context.store.stateNamespacePath(namespace,
                                                             context.version))
        }
    }

    private def verify(event: CuratorEvent, context: RecyclingContext,
                       namespace: String): Observable[CuratorEvent] = {
        if (context.canceled) {
            return Observable.empty()
        }
        if (event.getResultCode == Code.OK.intValue() &&
            event.getStat.getCtime < context.timestamp) {
            // We only delete those namespaces that have been created
            // before the beginning of the recycling task, to ensure that we
            // do not delete the namespace for a new host.
            context.log debug s"Namespace $namespace verified"
            Observable.just(event)
        } else {
            // Else, we ignore the namespace.
            context.log debug s"Namespace $namespace failed verification " +
                              s"(result code: ${event.getResultCode})"
            if (event.getResultCode != Code.OK.intValue()) {
                context.failedNamespaces.incrementAndGet()
            }
            Observable.empty()
        }
    }

    private def delete(event: CuratorEvent, context: RecyclingContext,
                       namespace: String): Observable[CuratorEvent] = {
        context.log debug s"Deleting namespace ${event.getPath} " +
                          s"with timestamp ${event.getStat.getCtime}"
        asObservable(context, throttle = true) {
            if (context.canceled) {
                return Observable.empty()
            }
            context.curator.delete()
                   .deletingChildrenIfNeeded()
                   .withVersion(event.getStat.getVersion)
                   .inBackground(_, context, context.executor)
                   .forPath(event.getPath)
        }
    }

    private def process(event: CuratorEvent, context: RecyclingContext)
    : Observable[RecyclingContext] = {
        if (event.getResultCode == Code.OK.intValue()) {
            context.deletedNamespaces.incrementAndGet()
        } else {
            context.failedNamespaces.incrementAndGet()
        }
        Observable.empty()
    }

}
