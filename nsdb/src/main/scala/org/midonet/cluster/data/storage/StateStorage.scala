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

import scala.collection.concurrent.TrieMap

import rx.Observable

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage.KeyType.KeyType

/**
 * The [[KeyType]] enumeration defines how values can be written to a state
 * key.
 *  - [[KeyType.SingleFirstWriteWins]]
 *  Only one key value is allowed at a time. A client can add a new value only
 *  if the key has no value or if the current value belongs to the same client
 *  session. The new value can be different from the old value.
 *  - [[KeyType.SingleLastWriteWins]]
 *  Only one key value is allowed at a time. A client can always add a new value
 *  and it will overwrite any existing value, even if it belongs to a different
 *  client session.
 *  - [[KeyType.Multiple]]
 *  Multiple key values from different clients are allowed at a time. Keys
 *  supporting multiple values are always last write wins, where a value can
 *  be overwritten by a different client.
 */
object KeyType extends Enumeration {
    class KeyType(val isSingle: Boolean, val firstWins: Boolean) extends Val
    final val SingleFirstWriteWins = new KeyType(true, true)
    final val SingleLastWriteWins = new KeyType(true, false)
    final val Multiple = new KeyType(false, false)
}

/**
 * An entry for a state key, which includes the key name and the set of key
 * values.
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
 * representing the current owner of the state value. The owner must uniquely
 * identify a client storage session.
 */
case class StateResult(ownerId: Long)

/**
 * Stores the registered keys for a given class.
 */
private[storage] final class StateInfo {
    val keys = new TrieMap[String, KeyType]
}

object StateStorage {

    /** Owner identifier returned as result when there is no owner. */
    final val NoOwnerId = 0L

    /** Encoding used for string conversion to byte array. */
    final val StringEncoding = "UTF-8"

}

/**
 * A trait that complements the [[Storage]] trait with support for ephemeral
 * state data. They are added as key value pairs where "state" keys are
 * registered prior to building the storage.
 *
 * A class implementing this trait should allow the registration of zero or more
 * state keys per object class. In turn, this should give the capability to
 * add or remove state values for every key and object created in storage, using
 * that object's identifier.
 *
 * Depending on the [[KeyType]] a key may support a single or multiple
 * values. It is desirable, but not a requirement that state values are
 * independent of the object's data, so that modifications to the object
 * do not affect that object's state. However, the state for the object should
 * not be available after the object has been deleted.
 *
 * For example, the state storage is used by the agent to add alive state to
 * existing hosts, which are stored separately from the host object. Since a
 * host can only be set alive by a single agent at a time, host alive uses
 * [[KeyType.SingleFirstWriteWins]], such that the first writing agent has
 * exclusive access to the state value.
 *
 * Likewise, the agent uses it to add active state to local exterior ports.
 * Since ports can be set as active on more than one host a time, port active
 * uses [[KeyType.Multiple]], such that the state storage allows multiple values
 * for the same key.
 */
trait StateStorage {

    protected[this] val stateInfo = new TrieMap[Class[_], StateInfo]

    /** Registers a new key for a class using the specified write policy. */
    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    def registerKey(clazz: Class[_], key: String, writePolicy: KeyType)
    : Unit

    /** Adds a value to a key for the object with the specified class and
      * identifier. The method is asynchronous, returning an observable that
      * when subscribed to will execute the add and will emit one notification
      * with the result of the operation.
      * @throws ServiceUnavailableException The storage is not built.
      * @throws IllegalArgumentException The key or class have not been
      * registered. */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def addValue(clazz: Class[_], id: ObjId, key: String, value: String)
    : Observable[StateResult]

    /** Removes a value from a key for the object with the specified class and
      * identifier. For single value keys, the `value` is ignored, and any
      * current value is deleted. The method is asynchronous, returning an
      * observable that when subscribed to will execute the remove and will emit
      * one notification with the result of the operation. */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def removeValue(clazz: Class[_], id: ObjId, key: String, value: String)
    : Observable[StateResult]

    /** Gets the set of values corresponding to a state key. The method is
      * asynchronous, returning an observable that when subscribed to will
      * execute the get and will emit one notification with the request
      * result. */
    @throws[ServiceUnavailableException]
    def getKey(clazz: Class[_], id: ObjId, key: String): Observable[StateKey]

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
    : KeyType = {
        stateInfo.getOrElse(clazz, throw new IllegalArgumentException(
            s"Class ${clazz.getSimpleName} is not registered")).keys
                 .getOrElse(key, throw new IllegalArgumentException(
            s"Key $key is not registered for class ${clazz.getSimpleName}"))
    }

}
