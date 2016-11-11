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

import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.util.functors.makeFunc1

package object util {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.nsdb")
/*
    /** An Observable that will recover itself if an error is emitted */
    def selfHealingTypeObservable[T](store: Storage, retries: Int = 10)
                                    (implicit ct: ClassTag[T]):
    Observable[Observable[T]] = {
        store.observable(ct.runtimeClass.asInstanceOf[Class[T]])
             .onErrorResumeNext(makeFunc1[Throwable,
                                          Observable[Observable[T]]] {
                     case t: NotFoundException =>
                         Observable.empty[Observable[T]]()
                     case _ if retries <= 0 =>
                         log.warn(s"Update stream of type ${ct.runtimeClass} " +
                                  "cannot be recovered again!")
                         Observable.empty[Observable[T]]()
                     case _ =>
                         log.info(s"Update stream of type ${ct.runtimeClass} " +
                                  "failed, recover")
                         selfHealingTypeObservable[T](store, retries - 1)
             })
    }

    /** An Observable that will recover itself if an error is emitted */
    def selfHealingEntityObservable[T](store: Storage, id: UUID,
                                       retries: Int = 10)
                                      (implicit ct: ClassTag[T]): Observable[T] = {
        store.observable(ct.runtimeClass.asInstanceOf[Class[T]], id)
             .onErrorResumeNext ( makeFunc1[Throwable, Observable[T]] {
                case t: NotFoundException =>
                    Observable.empty[T]()
                case _ if retries <= 0 =>
                    log.warn(s"Update stream for $id of type " +
                             s"${ct.runtimeClass} cannot be recovered again!")
                    Observable.empty[T]()
                case _ =>
                    log.info(s"Update stream for $id of type " +
                             s"${ct.runtimeClass} failed, recover")
                    selfHealingEntityObservable[T](store, id, retries - 1)(ct)
            })
    }*/
}
