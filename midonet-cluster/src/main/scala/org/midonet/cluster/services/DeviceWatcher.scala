/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services

import rx.functions.Func1

import scala.reflect.ClassTag

import com.google.protobuf.Message
import org.slf4j.{LoggerFactory, Logger}
import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer}

import org.midonet.cluster.data.storage.Storage

/** This trait adds functionality to watch a given type in ZOOM, and set up
 *  individual watchers on each of the entities emitted.  The actions to take
 *  when updates are emitted are configurable.
 *
 *  Call `startWatching` to start watching, `stopWatching` to cancel the
 *  subscriptions.
 */
class DeviceWatcher[T <: Message](store: Storage,
                                  updateHandler: T => Unit,
                                  deleteHandler: Object => Unit,
                                  filterHandler: Func1[_ >: T, java.lang.Boolean])
                                 (implicit private val ct: ClassTag[T]) {

    private val log = LoggerFactory.getLogger("org.midonet.cluster")
    private val deviceSubscriptions = new CompositeSubscription()
    private val deviceType = ct.runtimeClass.getSimpleName

    private class DeviceObserver() extends Observer[T] {

        @volatile private var id: Object = null

        override def onCompleted(): Unit = {
            deleteHandler(id)
        }
        override def onError(t: Throwable): Unit = {
            log.warn(s"Error in $deviceType $id update stream: ", t)
        }
        override def onNext(t: T): Unit = {
            if (id == null) {
                val idField = t.getDescriptorForType.findFieldByName("id")
                id = t.getField(idField)
            } else {
            }
            updateHandler(t)
        }
    }

    private val deviceTypeObserver = new Observer[Observable[T]] {
        override def onCompleted(): Unit = {
            log.debug(s"Completed stream of $deviceType updates")
        }
        override def onError(t: Throwable): Unit = {
            log.warn(s"$deviceType stream emits an error: ", t)
        }
        override def onNext(o: Observable[T]): Unit = {
            deviceSubscriptions.add(o
                    .filter(filterHandler)
                    .subscribe(new DeviceObserver))
        }
    }

    /** Start watching the type and monitor each individual entity emitted
      */
    final def startWatching(): Unit = {
        deviceSubscriptions.add (
            org.midonet.cluster.util.selfHealingTypeObservable[T](store)
                                    .subscribe(deviceTypeObserver)
        )
    }

    /** Stop watching the type and all existing elements.
      */
    final def stopWatching(): Unit = deviceSubscriptions.clear()

}
