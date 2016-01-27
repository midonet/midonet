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

import org.midonet.util.functors.makeFunc1

/**
  * Recycles the state NSDB znodes corresponding to deleted topology objects,
  * by collecting all objects and the corresponding state znodes for the
  * namespaces of the current hosts. Upon collecting this information, the
  * object validates which orphan states can be deleted, and handles the
  * completion of each individual recycling operation by updating the number of
  * recycled objects in the [[RecyclingContext]].
  */
object ObjectRecycler extends RecyclerCommons {

    def apply(context: RecyclingContext): Observable[RecyclingContext] = {
        if (context.canceled) {
            return Observable.empty()
        }
        context.log debug s"Deleting objects state for ${context.hosts.size} " +
                          s"namespaces ${context.step()}"
        // Recycle the state objects that are not present in the model objects.
        val observables = for ((host, clazz) <- context.stateObjects.keys().asScala;
                               id <- context.stateObjects.get((host, clazz))
                               if !context.modelObjects.get(clazz).contains(id)) yield {
            validate(context, host, clazz, id) flatMap makeFunc1 {
                verify(_, context)
            } flatMap makeFunc1 {
                delete(_, context)
            } flatMap makeFunc1 {
                process(_, context)
            }
        }

        if (observables.isEmpty) {
            context.log debug s"No objects to verify and delete"
        }

        Observable.concat(Observable.merge(observables.toIterable.asJava),
                          Observable.just(context))
    }

    private def validate(context: RecyclingContext, host: String,
                         clazz: Class[_], id: String)
    : Observable[CuratorEvent] = {

        context.log debug "Verifying and deleting object state " +
                          s"${clazz.getSimpleName}/$id at host $host"
        val path = context.store.stateObjectPath(host, clazz, id, context.version)

        asObservable(context, throttle = false) {
            context.curator.getData
                .inBackground(_, context, context.executor)
                .forPath(path)
        }
    }

    private def verify(event: CuratorEvent, context: RecyclingContext)
    : Observable[CuratorEvent] = {
        if (context.canceled) {
            return Observable.empty()
        }
        // We only delete those objects that have been created before
        // the beginning of the recycling task, to ensure that we do
        // not delete the state for a new object.
        if (event.getResultCode == Code.OK.intValue() &&
            event.getStat.getCtime < context.timestamp) {
            Observable.just(event)
        } else {
            if (event.getResultCode != Code.OK.intValue()) {
                context.failedObjects.incrementAndGet()
            }
            Observable.empty()
        }
    }

    private def delete(event: CuratorEvent, context: RecyclingContext)
    : Observable[CuratorEvent] = {
        context.log debug s"Deleting object ${event.getPath} with " +
                          s"timestamp ${event.getStat.getCtime}"
        asObservable(context, throttle = true) {
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
            context.deletedObjects.incrementAndGet()
        } else {
            context.failedObjects.incrementAndGet()
        }
        Observable.empty()
    }

}
