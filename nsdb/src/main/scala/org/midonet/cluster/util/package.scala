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

package org.midonet.cluster

import java.util.UUID

import scala.reflect.ClassTag

import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.util.functors.makeFunc1

package object util {

    /** An Observable that will recover itself if an error is emitted */
    def selfHealingObservable[T](store: Storage)
                                (implicit ctag: ClassTag[T]):
    Observable[Observable[T]] =
        store.observable(ctag.runtimeClass.asInstanceOf[Class[T]])
             .onErrorResumeNext(makeFunc1[Throwable,
                                          Observable[Observable[T]]] {
                case t: NotFoundException => Observable.empty[Observable[T]]()
                case _ => selfHealingObservable[T](store)
             })

    /** An Observable that will recover itself if an error is emitted */
    def selfHealingObservable[T](store: Storage, id: UUID)
                                (implicit ctag: ClassTag[T]):
    Observable[T] =
        store.observable(ctag.runtimeClass.asInstanceOf[Class[T]], id)
             .onErrorResumeNext(makeFunc1[Throwable,
                                          Observable[T]] {
                case t: NotFoundException => Observable.empty()
                case _: T => selfHealingObservable[T](store, id)
             })
}
