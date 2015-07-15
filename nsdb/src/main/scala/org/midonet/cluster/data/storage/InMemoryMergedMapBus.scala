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

package org.midonet.cluster.data.storage

import rx.subjects.{ReplaySubject, SerializedSubject}
import rx.{Observable, Observer}

/**
 * An in-memory implementation of the MergedMapBus trait.
 */
class InMemoryMergedMapBus[K, V >: Null <: AnyRef]
    (id: String, ownerId: String) extends MergedMapBus[K, V] {

    type Opinion = (K, V, String)

    private val subject =
        new SerializedSubject(ReplaySubject.create[Opinion]())

    override def mapId: String = id
    override def owner: String = ownerId
    override def opinionObserver: Observer[(K, V, String)] = subject
    override def opinionObservable: Observable[(K, V, String)] =
        subject.asObservable()
    override def close(): Unit = subject.onCompleted()
}
