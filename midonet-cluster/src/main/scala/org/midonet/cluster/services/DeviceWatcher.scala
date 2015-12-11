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

import scala.reflect.ClassTag

import com.google.protobuf.Message

import com.typesafe.scalalogging.Logger

import rx.subscriptions.CompositeSubscription
import rx.{Observable, Observer, Scheduler}

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.util.selfHealingTypeObservable
import org.midonet.util.functors._
import org.midonet.cluster.util.UUIDUtil._


/** This trait adds functionality to watch a given type in ZOOM, and set up
 *  individual watchers on each of the entities emitted.  The actions to take
 *  when updates are emitted are configurable.
 *
 *  Call `startWatching` to start watching, `stopWatching` to cancel the
 *  subscriptions.
 */

class DeviceWatcher[T <: Message](
    store: Storage, scheduler: Scheduler,
    updateHandler: T => Unit, deleteHandler: T => Unit,
    filterHandler: T => java.lang.Boolean = {t:T => java.lang.Boolean.TRUE},
    log: Logger)
    (implicit private val ct: ClassTag[T]) {

    private val deviceSubscriptions = new CompositeSubscription()
    private val deviceTypeName = ct.runtimeClass.getSimpleName

    private class DeviceObserver extends Observer[T] {

        @volatile private var device: T = _

        override def onCompleted(): Unit = {
            deleteHandler(device)
        }
        override def onError(t: Throwable): Unit = {
            if (device != null)  {
                val idField = device.getDescriptorForType.findFieldByName("id")
                val id = device.getField(idField)
                log.warn(s"Error in $deviceTypeName ${fromProto(
                    id.asInstanceOf[Commons.UUID])} update stream: ", t)
            }
            else {
                log.warn(s"Error in update stream on device (not set yet): ", t)
            }
        }
        override def onNext(t: T): Unit = {
            device = t
            updateHandler(t)
        }
    }

    private val deviceTypeObserver = new Observer[Observable[T]] {
        override def onCompleted(): Unit = {
            log.debug(s"Completed stream of $deviceTypeName updates")
        }
        override def onError(t: Throwable): Unit = {
            log.warn(s"$deviceTypeName stream emits an error: ", t)
        }
        override def onNext(o: Observable[T]): Unit = {
            deviceSubscriptions.add(o
                    .filter(makeFunc1[T, java.lang.Boolean](filterHandler))
                    .subscribe(new DeviceObserver))
        }
    }

    /** Start watching the type and monitor each individual entity emitted
      */
    final def subscribe(): Unit = {
        deviceSubscriptions.add (
            selfHealingTypeObservable[T](store)
                .observeOn(scheduler)
                .subscribe(deviceTypeObserver)
        )
    }

    /** Stop watching the type and all existing elements.
      */
    final def unsubscribe(): Unit = deviceSubscriptions.clear()

}
