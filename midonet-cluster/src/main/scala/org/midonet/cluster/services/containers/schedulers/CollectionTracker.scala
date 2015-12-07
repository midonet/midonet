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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import scala.collection.mutable
import scala.reflect.ClassTag

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.util.functors._

/** Processes notifications for a collection of [[ObjectTracker]]s, by
  * aggregating the notifications from all trackers in the collection and
  * emitting their last values in a single set.
  *
  * An instance of this class provides a `watch` method to specify the
  * identifiers of the objects from the collection, and exposes an observable
  * which when subscribed to emits the set of values received from each
  * individual tracker in the collection.
  *
  * A non-abstract derived class must provide an implementation for the
  * `newMember` method, which returns a new object tracker for a given
  * identifier.
  *
  * If a member observable emits an error, that member is removed from the
  * collection and its error is filtered. If a member observable completes,
  * that member is removed from the collection, and the output observable
  * emits a new update with the member removed
  *
  * The observable exposed by this class does not filter the input observable
  * errors, and will complete when the input observable completes.
  *
  * NOTE: The collection tracker is not thread-safe and it does not operate on
  * any particular scheduler. Rather, the calls to the `watch`, `add` and
  * `remove` methods and all notifications received from children trackers must
  * be scheduled on the same thread.
  */
abstract class CollectionTracker[M <: ObjectTracker[ME], ME >: Null]
                                (context: Context)(implicit tag: ClassTag[M])
    extends ObjectTracker[Map[UUID, ME]] {

    private val membersSubject = PublishSubject.create[Observable[ME]]
    protected val members = new mutable.HashMap[UUID, M]

    // Emits notifications for the current collection.
    override val observable: Observable[Map[UUID, ME]] = Observable
        .merge(membersSubject)
        .filter(makeFunc1(_ => isReady))
        .map[Map[UUID, ME]](makeFunc1(_ => { ref = buildEvent(); ref }))
        .distinctUntilChanged()
        .takeUntil(mark)

    /** Creates the tracker for a new member with the specified identifier.
      */
    protected def newMember(memberId: UUID): M

    /** Indicates whether the collection tracker has received the data for all
      * the members of this collection.
      */
    override def isReady: Boolean = {
        members.values.forall(_.isReady)
    }

    /** Adds a new member identifier to watch to the current collection. If
      * the identifier corresponds to an existing member, the method has no
      * effect.
      */
    def add(id: UUID): Unit = {
        watch(members.keySet.toSet + id)
    }

    /** Removes a member identifier from the current collection. If the
      * identifier corresponds to a non-existent member, the method has no
      * effect.
      */
    def remove(id: UUID): Unit = {
        watch(members.keySet.toSet - id)
    }

    /** Watches the specified set of members. Current members that are not part
      * of set are removed from the membership list, and their trackers are
      * completed. New members are added to the membership list, and for each
      * one a new tracker is created and merged into the output observable.
      */
    def watch(ids: Set[UUID]): Unit = {

        context.log.debug(s"${tag.runtimeClass.getSimpleName} collection " +
                          s"updated with members $ids")

        // Create observables for the new members of this collection.
        val addedMembers = new mutable.MutableList[(UUID, M)]
        var membersChanged = false
        for (memberId <- ids if !members.contains(memberId)) {
            val memberTracker = newMember(memberId)
            members += memberId -> memberTracker
            addedMembers += ((memberId, memberTracker))
            membersChanged = true
        }

        // Complete the observable for the current members no longer members
        // of this object collection.
        for ((memberId, memberState) <- members.toList
             if !ids.contains(memberId)) {
            memberState.complete()
            members -= memberId
            membersChanged = true
        }

        // Emit a notification when there is no change in the membership.
        if (!membersChanged) {
            membersSubject onNext Observable.just(null)
        }

        // Publish the observables for the added members.
        for ((memberId, memberState) <- addedMembers) {
            val errorHandler = makeFunc1[Throwable, Observable[ME]] {
                memberError(memberId, _)
            }
            membersSubject onNext memberState.observable
                .doOnCompleted(makeAction0 { members -= memberId })
                .onErrorResumeNext(errorHandler)
                .concatWith(Observable.just(null))
        }
    }

    /** Handles the errors emitted by the observable of the specified
      * member. The method returns an observable emitting a `null`
      * notification to remove the member from the collection membership.
      */
    private def memberError(memberId: UUID, e: Throwable): Observable[ME] = {

        context.log.warn(s"${tag.runtimeClass.getSimpleName} collection " +
                         s"error for $memberId: removed from tracker", e)

        members -= memberId
        Observable.just(null)
    }

    /** Builds the event for this collection tracker membership.
      */
    private def buildEvent(): Map[UUID, ME] = {
        members.mapValues(_.last).toMap
    }

}

