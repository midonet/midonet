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

package org.midonet.cluster.util

import java.util.UUID

import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.slf4j.LoggerFactory
import rx.Observer
import rx.subscriptions.CompositeSubscription

import org.midonet.cluster.data.storage._


object Snatcher {
    def apply[D](targetId: UUID, nodeId: UUID, stateStore: StateStorage,
                 stateKey: String,
                 onSnatched: () => Unit,
                 onLost: () => Unit)(implicit ct: ClassTag[D]): Snatcher[D] = {
        val snatcher = new Snatcher[D](targetId, nodeId, stateStore, stateKey,
                                       onSnatched, onLost)
        snatcher.sub.add(
            stateStore.keyObservable(ct.runtimeClass, targetId, stateKey)
                      .subscribe(snatcher)
        )
        snatcher
    }
}

/** The Snatcher will watch the observable that emits state keys for a given
  * entity, and compete with other snatchers in other instances to snatch
  * it if it's not taken, or if a previous owner releases it.
  *
  * You should avoid using two instances of the Snatcher per entity per node
  * But nothing will prevent you from doing it.
  *
  * All operations run on the ZK event thread, except for the initial
  * subscription, so thread-safety is not enforced within the class.
  *
  * @param targetId the ID of the entity that we'll try to snatch
  * @param nodeId our node ID, used to take ownershipo of VTEPs
  * @param stateStore the state storage
  * @param stateKey the state key used to write ownership.  Note that this
  *                 key must be declared in the StateStorage as First Write Wins
  */
sealed class Snatcher[D](val targetId: UUID,
                         val nodeId: UUID, stateStore: StateStorage,
                         stateKey: String,
                         onSnatched: () => Unit,
                         onLost: () => Unit)(implicit ct: ClassTag[D])
    extends Observer[StateKey] {

    private val log = LoggerFactory.getLogger(s"org.midonet.cluster.snatcher-$nodeId")
    private val typeName = ct.runtimeClass.getSimpleName

    private val sub = new CompositeSubscription()
    private var isOwner = false

    override def onCompleted(): Unit = {
        if (isOwner) {
            isOwner = false;
            sub.unsubscribe()
            onLost()
        }
    }
    override def onError(t: Throwable): Unit = {
        log.error(s"Failure watching entity $targetId of type " +
                  s"${ct.runtimeClass}", t)
        onCompleted()
    }
    override def onNext(t: StateKey): Unit = t match {
        case SingleValueKey(k, Some(value), _) if nodeId.toString != value =>
            log.debug(s"$typeName $targetId already taken by node $value")
            val wasOwner = isOwner
            isOwner = false
            if (wasOwner) {
                onLost()
            }
        case SingleValueKey(k, Some(_), _) =>
            // Update about ourselves, ignore
        case SingleValueKey(k, None, _) =>
            log.debug(s"$typeName $targetId isn't taken, grab it!!")
            try {
                stateStore.addValue(ct.runtimeClass, targetId, stateKey,
                                    nodeId.toString)
                    .toBlocking.first()
                isOwner = true
                onSnatched()
            } catch {
                case NonFatal(ex) =>
                    log.warn("Failed to write state for key: $t", ex)
            }
        case v =>
            log.warn(s"Unexpected value on state key $stateKey: $v")
    }

    /** Tell the Snatcher to stop trying to own the entity.  If it is the
      * current owner, it'll give up ownership.
      */
    def giveUp(): Unit = {
        log.info(s"Release $typeName $targetId")
        sub.unsubscribe()
        if (isOwner) {
            isOwner = false
            stateStore.removeValue(ct.runtimeClass, targetId, stateKey, null)
                      .toBlocking.first()
            onLost()
        }
    }
}
