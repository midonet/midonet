/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster.data.reactivex

import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable

import rx.subscriptions.Subscriptions
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.reactivex.InstanceObservable.{OnSubscribeInstance, Event}
import org.midonet.cluster.data.storage.Storage
import org.midonet.util.functors.makeAction0

object InstanceObservable {

    trait Event[T]
    case class Update[T](id: ObjId, t: T) extends Event[T]
    case class Error[T](id: ObjId, e: Throwable) extends Event[T]
    case class Complete[T](id: ObjId) extends Event[T]

    final class OnSubscribeInstance[T](clazz: Class[T], id: ObjId)
                                      (implicit store: Storage)
        extends OnSubscribe[Event[T]] {

        private var done = false
        private val sync = new Object
        private val subscribers = new mutable.HashSet[InstanceSubscriber[T]]()

        override def call(child: Subscriber[_ >: Event[T]]): Unit = {
            sync.synchronized {
                if (done) {
                    Observable.empty[Event[T]]().subscribe(child)
                } else {
                    val subscriber = new InstanceSubscriber[T](
                        id, child, s => sync.synchronized {
                            s.unsubscribe()
                            subscribers -= s
                        })
                    subscribers += subscriber
                    store.subscribe(clazz, id, subscriber)
                }
            }
        }

        def complete(): Unit = sync.synchronized {
            done = true
            subscribers.foreach(_.complete())
        }

    }

    final class InstanceSubscriber[T](id: ObjId,
                                      child: Subscriber[_ >: Event[T]],
                                      unsubscribe: (InstanceSubscriber[T]) => Unit)
        extends Subscriber[T]() {

        child.add(this)
        add(child)

        val done = new AtomicBoolean
        child.add(Subscriptions.create(makeAction0 { unsubscribe(this) }))

        override def onCompleted() = if (done.compareAndSet(false, true)) {
            child onNext Complete(id)
            child onCompleted()
        }

        override def onError(e: Throwable) = if (done.compareAndSet(false, true)) {
            child onNext Error(id, e)
            child onError e
        }

        override def onNext(t: T) = if (!done.get) {
            child onNext Update(id, t)
        }

        def complete(): Unit = if (done.compareAndSet(false, true)) {
            child onNext Complete(id)
            child onCompleted()
        }

    }

    /**
     * Creates an [[rx.Observable]] which, when subscribed to, connects to
     * the storage layer, and subscribes to notifications for the specified
     * class instance. This observable wraps object notifications received
     * from the storage layer into [[Event]] objects, which allows the
     * aggregation of different observable streams into a single one, yet
     * it allows the propagation of completed and error notification as
     * regular updates.
     *
     * The [[InstanceObservable]] provides a complete() method
     * which, when called, un-subscribes the observable from the storage
     * observable and emits both a Complete and onCompleted notifications.
     */
    def create[T](clazz: Class[T], id: ObjId)
                 (implicit store: Storage): InstanceObservable[T] = {
        new InstanceObservable[T](new OnSubscribeInstance(clazz, id))
    }
}

final class InstanceObservable[T] protected (onSubscribe: OnSubscribeInstance[T])
        extends Observable[Event[T]](onSubscribe) {

    def complete(): Unit = onSubscribe.complete()

}


