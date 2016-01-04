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

package org.midonet.containers

import rx.Observable
import rx.subjects.PublishSubject

/** Abstract object state class with boiler-plate members.
  */
abstract class ObjectTracker[T >: Null] {

    protected var ref: T = null

    protected val mark = PublishSubject.create[T]

    /** An observable that emits notifications for this object state.
      */
    def observable: Observable[T]

    /** Indicates whether the object state has received the data from
      * storage for all objects.
      */
    def isReady: Boolean

    /** Returns the last notification emitted by the observable of this
      * state.
      */
    final def last: T = ref

    /** Completes the observable of the port group state.
      */
    final def complete(): Unit = mark.onCompleted()

}
