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

package org.midonet.midolman.topology

import java.util.UUID

import javax.annotation.concurrent.NotThreadSafe

import org.midonet.midolman.logging.MidolmanLogging

import scala.collection.mutable
import scala.reflect.ClassTag

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.util.functors.makeAction1

/**
 * Stores the state for an object, and exposes an [[Observable]] that emits
 * updates for this object. The observable completes either when the object
 * is deleted, or when calling the complete() method, which is used to
 * signal that an object no longer belongs to the containing device.
 */
class TopologyObjectState[D >: Null <: Device](val id: UUID)(implicit tag: ClassTag[D]) {
    private var currentObj: D = null
    private val mark = PublishSubject.create[D]()

    val observable = VirtualTopology.observable[D](id)
        .doOnNext(makeAction1(currentObj = _:D))
        .takeUntil(mark)

    /** Completes the observable corresponding to this obj state. */
    def complete(): Unit = mark.onCompleted()
    /** Get the chain for this obj state. */
    def dereference: D = currentObj
    /** Indicates whether the obj state has received the obj data. */
    def isReady: Boolean = currentObj ne null
}

class ObjectReferenceTracker[D >: Null <: Device](val vt: VirtualTopology)
        (implicit tag: ClassTag[D]) extends MidolmanLogging {

    type State = TopologyObjectState[D]

    @throws[DeviceMapperException]
    @inline private def assertThread(): Unit = vt.assertThread()

    private val refsSubject = PublishSubject.create[Observable[D]]
    private val refs = new mutable.HashMap[UUID, State]

    @NotThreadSafe
    final def requestRefs(ids: Set[UUID]): Unit = {
        log.debug(s"Updating refs: $ids")

        // Remove the refs that are no longer used.
        for ((id, state) <- refs if !ids.contains(id)) {
            state.complete()
            refs -= id
        }
        // Create the state and emit observables for the new refs.
        val addedRefs = new mutable.MutableList[State]

        for (id <- ids if !refs.contains(id)) {
            val state = new State(id)
            refs += id -> state
            addedRefs += state
        }

        // Publish observable for added chains.cest
        for (state <- addedRefs) {
            refsSubject onNext state.observable
        }
    }

    /**
     * This method has the same purpose as requestRefs(ids: Set[UUID]),
     * except that this method takes a variable list of refs identifiers. Nulls
     * are allowed.
     */
    @NotThreadSafe
    final def requestRefs(ids: UUID*): Unit = {
        assertThread()
        requestRefs(ids.filter(_ ne null).toSet)
    }

    /**
     * Completes the refs observable and the observable for all chains that
     * were previously emitted and not completed. This method must only be
     * called for the VT thread.
     */
    @NotThreadSafe
    final def completeRefs(): Unit = {
        assertThread()
        for (state <- refs.values) {
            state.complete()
        }
        refsSubject.onCompleted()
    }

    /**
     * Indicates whether all chains for the device were received.
     */
    @NotThreadSafe
    final def areRefsReady: Boolean = {
        assertThread()
        val ready = refs.forall(_._2.isReady)
        log.debug("Refs ready: {}", Boolean.box(ready))
        ready
    }

    /**
     * Returns the last requested chains as an immutable map of chain
     * identifiers to chain simulation objects. If the chain for a certain
     * identifier was not yet received from the virtual topology, its
     * corresponding value in the map is null.
     */
    @NotThreadSafe
    final def currentRefs: Map[UUID, D] = refs.map(e  => (e._1, e._2.dereference)).toMap

    /**
     * An observable that emits notifications for the chains.
     */
    final val refsObservable: Observable[D] = Observable.merge(refsSubject)
}
