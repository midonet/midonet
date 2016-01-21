/*
 * Copyright 2016 Midokura SARL
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
import scala.util.{Failure, Success}

import org.slf4j.LoggerFactory
import rx.Observer
import rx.subscriptions.CompositeSubscription

import org.midonet.cluster.data.storage._
import org.midonet.util.reactivex._


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

/** The Snatcher will watch the observable that emits state keys for a
  *  given entity, and compete with other snatchers to snatch it if it's
  *  not taken, or if a previous owner releases it.
  *
  * You should avoid using two instances of the Snatcher per entity per node
  * But nothing will prevent you from doing it.
  *
  * All operations run on the ZK event thread, except for the initial
  * subscription, so thread-safety is not enforced within the class.
  *
  * @param targetId the ID of the entity that we'll try to snatch
  * @param nodeId our node ID, used to take ownership of the target
  * @param stateStore the state storage
  * @param stateKey the state key used to write ownership.  Note that this
  *                 key must be declared in the StateStorage as FirstWriteWins
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
            isOwner = false
            onLost()
        }
    }
    override def onError(t: Throwable): Unit = {
        log.error(s"Failure watching entity $targetId of type $typeName", t)
        onCompleted()
    }
    override def onNext(t: StateKey): Unit = t match {
        case SingleValueKey(_, None, _) =>
            log.debug(s"$typeName $targetId isn't taken, grab it!!")
                // The value doesn't even matter, since the ownership is tied to
                // the zk session
                stateStore.addValue(ct.runtimeClass, targetId, stateKey,
                                    nodeId.toString).andThen {
                    case Success(_) =>
                    case Failure(e: NotStateOwnerException) => // race lost
                        log.debug(s"Lost race grabbing key: $t", e)
                    case Failure(e) =>
                        log.warn(s"Failed to write state for key: $t", e)
                }
        case SingleValueKey(_ , Some(v), session) if stateStore.ownerId() == session =>
            log.debug(s"I own $typeName $targetId")
            iAmOwner()
        case SingleValueKey(_, Some(v), session) => // Somebody else is the owner
            log.debug(s"$typeName $targetId already taken by node $v")
            val wasOwner = isOwner
            isOwner = false
            if (wasOwner) {
                onLost()
            }
        case v =>
            log.warn(s"Unexpected value on state key $stateKey: $v")
    }

    private def iAmOwner(): Unit = {
        val wasOwner = isOwner
        isOwner = true
        if (!wasOwner) {
            onSnatched()
        }
    }

    /** Tell the Snatcher to stop trying to own the entity.  If it is the
      * current owner, it'll give up ownership.
      */
    def giveUp(): Unit = {
        sub.unsubscribe()
        if (isOwner) {
            log.info(s"Release $typeName $targetId")
            isOwner = false
            stateStore.removeValue(ct.runtimeClass, targetId, stateKey, null)
                      .andThen {
                          case Success(_) =>
                              log.debug(s"Released $typeName $targetId")
                          case Failure(_) =>
                              log.warn(s"Failed to release $typeName " +
                                       s"$targetId, it should expire by " +
                                       "itself anyway")
                      }

            onLost()
        }
    }
}
