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

import scala.collection.mutable
import scala.concurrent.Future

import rx.Observable

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage.WritePolicy.WritePolicy

/**
 * The [[WritePolicy]] enumeration defines how values can be written to a state
 * key.
 *  - [[WritePolicy.SingleFirstWriteWins]]
 *  Only one key value is allowed at a time. A client can add a new value only
 *  if the key has no value or if the current value belongs to the same client
 *  session. The new value can be different from the old value.
 *  - [[WritePolicy.SingleLastWriteWins]]
 *  Only one key value is allowed at a time. A client can always add a new value
 *  and it will overwrite any existing value, event if it belongs to a different
 *  client session.
 *  - [[WritePolicy.Multiple]]
 *  Multiple key values from different clients are allowed at a time. Keys
 *  supporting multiple values are always last write wins, where a value can
 *  be overwritten by a different client.
 */
object WritePolicy extends Enumeration {
    class WritePolicy(val isSingle: Boolean, val firstWins: Boolean) extends Val
    final val SingleFirstWriteWins = new WritePolicy(true, true)
    final val SingleLastWriteWins = new WritePolicy(true, false)
    final val Multiple = new WritePolicy(false, false)
}

/**
 * An entry for a state key, which includes the key name and the set of key
 * key values.
 */
trait StateKey {
    def key: String
    def isEmpty: Boolean
    def nonEmpty: Boolean
}

case class SingleValueKey(key: String, value: Option[String], ownerId: Long)
    extends StateKey {
    override def isEmpty = value.isEmpty
    override def nonEmpty = value.nonEmpty
}

case class MultiValueKey(key: String, value: Set[String]) extends StateKey {
    override def isEmpty = value.isEmpty
    override def nonEmpty = value.nonEmpty
}

/**
 * The result of an asynchronous write operation. Contains an identifier
 * representing the current owner of the state value. When the backend store
 * is ZooKeeper, the owner identifier is the client session identifier.
 */
case class StateResult(ownerId: Long)

/**
 * Stores the registered keys for a given class.
 */
private[storage] final class StateInfo {
    val keys = new mutable.HashMap[String, WritePolicy]
}

object StorageWithState {

    /** Owner identifier returned as result when there is no owner, such as
      * idempotent deletion. */
    final val NoOwnerId = 0L

    /** Encoding used for string conversion to byte array. */
    final val StringEncoding = "UTF-8"

}

/**
 * A trait that extends the [[Storage]] trait with support for state ephemeral
 * data. This is added as values to keys, which are registered before building
 * the storage. State keys are created for each object added to the storage
 * using the methods from the [[Storage]] trait.
 */
trait StorageWithState {

    protected[this] val stateInfo = new mutable.HashMap[Class[_], StateInfo]

    /** Registers a new key for a class using the specified write policy. */
    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    def registerKey(clazz: Class[_], key: String, writePolicy: WritePolicy)
    : Unit

    /** Adds a value to a key for the object with the specified class and
      * identifier. The method is asynchronous, returning a future that
      * contains the result of the operation.
      * @throws ServiceUnavailableException The storage is not built.
      * @throws IllegalArgumentException The key or class have not been
      * registered. */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def addValue(clazz: Class[_], id: ObjId, key: String, value: String)
    : Future[StateResult]

    /** Removes a value from a key for the object with the specified class and
      * identifier. For single value keys, the `value` is ignored, and any
      * current value is deleted. The method is asynchronous, returning a future
      * that contains the result of the operation. */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def removeValue(clazz: Class[_], id: ObjId, key: String, value: String)
    : Future[StateResult]

    /** Gets the set of values corresponding to a state key. The method is
      * asynchronous, returning a future that contains the request result. */
    @throws[ServiceUnavailableException]
    def getKey(clazz: Class[_], id: ObjId, key: String): Future[StateKey]

    /** Returns an observable for a state key. Upon subscription, the observable
      * will emit a notification with current set of values corresponding to key
      * and thereafter an additional notification whenever the set of values has
      * changed. The observable does not emit notifications for successful
      * write operations, which do not modify the value set. */
    @throws[ServiceUnavailableException]
    def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey]

    /** Gets the write policy for the given class and key. */
    @throws[IllegalArgumentException]
    protected[this] def getWritePolicy(clazz: Class[_], key: String)
    : WritePolicy = {
        stateInfo.getOrElse(clazz, throw new IllegalArgumentException(
            s"Class ${clazz.getSimpleName} is not registered"))
            .keys.getOrElse(key, throw new IllegalArgumentException(
            s"Key $key is not registered for class ${clazz.getSimpleName}"))
    }

}
