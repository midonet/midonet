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
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.Code

import rx.Observable

import org.midonet.util.functors.makeFunc1

/**
  * Collects the current state namespaces from the NSDB, and updates the list
  * of namespaces from the specified [[RecyclingContext]]. This list is
  * compared to the current list of hosts, to determine the obsolete namespaces.
  */
object NamespaceCollector extends RecyclerCommons {

    def apply(context: RecyclingContext): Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        context.log debug "Collecting current state namespaces " +
                          s"${context.step()}"
        asObservable(context, throttle = false) {
            context.curator.getChildren
                   .inBackground(_, context, context.executor)
                   .forPath(context.store.statePath(context.version))
        } flatMap makeFunc1 {
            process
        }
    }

    private def process(event: CuratorEvent): Observable[RecyclingContext] = {
        val context = event.getContext.asInstanceOf[RecyclingContext]
        if (context.canceled) {
            return Observable.empty()
        }
        if (event.getResultCode == Code.OK.intValue()) {
            context.namespaces = event.getChildren.asScala.toSet
            context.log debug s"Collected ${context.namespaces.size} namespaces"
            Observable.just(context)
        } else {
            Observable.error(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

}
