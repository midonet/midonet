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
  * Collects all objects and their corresponding state paths from the NSDB, and
  * updates their lists from the specified [[RecyclingContext]]. These lists are
  * compared to determine the obsolete object state paths.
  */
object ObjectCollector extends RecyclerCommons {

    def apply(context: RecyclingContext): Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        context.log debug s"Collecting objects ${context.step()}"
        val objectObservables = for (clazz <- context.store.classes) yield {
            collectObjects(context, clazz) flatMap makeFunc1 {
                processObjects(_, context, clazz)
            }
        }
        val stateObservables = for (host <- context.hosts;
                                    clazz <- context.store.classes) yield {
            collectState(context, host, clazz) flatMap makeFunc1 {
                processState(_, context, host, clazz)
            }
        }

        val observables = objectObservables ++ stateObservables

        Observable.concat(Observable.merge(observables.asJava),
                          Observable.just(context))
    }

    private def collectObjects(context: RecyclingContext, clazz: Class[_])
    : Observable[CuratorEvent] = {
        asObservable(context, throttle = false) {
            context.curator.getChildren
                   .inBackground(_, context, context.executor)
                   .forPath(context.store.classPath(clazz))
        }
    }

    private def processObjects(event: CuratorEvent, context: RecyclingContext,
                               clazz: Class[_]): Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        if (event.getResultCode == Code.OK.intValue()) {
            context.log debug s"Collected ${event.getChildren.size} " +
                              s"objects for class ${clazz.getSimpleName}"
            context.modelObjects
                   .putIfAbsent(clazz, event.getChildren.asScala.toSet)
            Observable.empty()
        } else {
            Observable.error(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

    private def collectState(context: RecyclingContext, host: String,
                             clazz: Class[_]): Observable[CuratorEvent] = {
        asObservable(context, throttle = false) {
            context.curator.getChildren
                .inBackground(_, context, context.executor)
                .forPath(context.store.stateClassPath(host, clazz,
                                                      context.version))
        }
    }

    private def processState(event: CuratorEvent, context: RecyclingContext,
                             host: String, clazz: Class[_])
    : Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        if (event.getResultCode == Code.OK.intValue()) {
            context.log debug s"Collected state for ${event.getChildren.size} " +
                              s"objects at host $host class ${clazz.getSimpleName}"
            context.stateObjects
                   .putIfAbsent((host, clazz), event.getChildren.asScala)
            Observable.empty()

        } else if (event.getResultCode == Code.NONODE.intValue()) {
            // We ignore NONODE errors because the class state keys are
            // created on demand.
            Observable.empty()
        } else {
            Observable.error(
                KeeperException.create(Code.get(event.getResultCode),
                                       event.getPath))
        }
    }

}
