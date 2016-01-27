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

import org.midonet.cluster.models.Topology.Host
import org.midonet.util.functors.makeFunc1

/**
  * Collects the current hosts from the NSDB, and updates the list of hosts
  * in the specified [[RecyclingContext]]. The hosts set is used to determine
  * the obsolete namespaces that should be deleted.
  */
object HostCollector extends RecyclerCommons {

    def apply(context: RecyclingContext): Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        context.log debug s"Collecting current hosts ${context.step()}"
        asObservable(context, throttle = false) {
            context.curator.getChildren
                   .inBackground(_, context, context.executor)
                   .forPath(context.store.classPath(classOf[Host]))
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
            context.hosts = event.getChildren.asScala.toSet
            context.log debug s"Collected ${context.hosts.size} hosts"
            Observable.just(context)
        } else {
            Observable.error(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

}
