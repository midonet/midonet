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
 *  - [[KeyType.SingleLastWriteWinsFastFailOver]]
 *  Same thing as above except that state ephemeral data is quickly removed when
 *  the entity that created them is deemed to be down. For instance,
 *  the VPNaaS service adds a state key using this key type such that fast
 *  failover of the VPN service can be performed.
 *  - [[KeyType.Multiple]]
 *  Multiple key values from different clients are allowed at a time. Keys
 *  supporting multiple values are always last write wins, where a value can
 *  be overwritten by a different client.
 */
object KeyType extends Enumeration {
    class KeyType(val isSingle: Boolean, val firstWins: Boolean,
                  val failFast: Boolean = false) extends Val
    final val SingleFirstWriteWins = new KeyType(true, true)
    final val SingleLastWriteWins = new KeyType(true, false)
    final val SingleLastWriteWinsFastFailOver = new KeyType(true, false, true)
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
 * values. The state of an object is independent from its data and is isolated
 * for each host in a private ZooKeeper tree called namespace. Because a new
 * host can create state after an object has been created, the state paths are
 * created on demand when a hosts adds a value to a state key. However, it is
 * not possible to add a state value for an object that doesn't exist.
 *
 * For example, the state storage is used by the agent to add alive state to
 * existing hosts, which are stored separately from the host object. Since a
 * host can only be set alive by a single agent at a time, host alive uses
 * [[KeyType.SingleFirstWriteWins]], such that the first writing agent has
 * exclusive access to the state value.
 *
 * Likewise, the agent uses it to add active state to local exterior ports.
 * Since ports can be set as active on more than one host at a time, port active
 * uses [[KeyType.Multiple]], such that the state storage allows multiple values
 * for the same key.
 */
trait StateStorage {

    protected[this] val stateInfo = new TrieMap[Class[_], StateInfo]

    protected def namespace: String

    /** Registers a new key for a class using the specified key type. */
    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    def registerKey(clazz: Class[_], key: String, keyType: KeyType)
    : Unit

    /** Adds a value to a key for the object with the specified class and
      * identifier to the state for the current namespace. The method is
      * asynchronous, returning an observable that when subscribed to will
      * execute the add and will emit one notification with the result of the
      * operation.
      * @throws ServiceUnavailableException The storage is not built.
      * @throws IllegalArgumentException The key or class have not been
      * registered. */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def addValue(clazz: Class[_], id: ObjId, key: String, value: String)
    : Observable[StateResult]

    /** Removes a value from a key for the object with the specified class and
      * identifier from the state of the current namespace. For single value
      * keys, the `value` is ignored, and any current value is deleted. The
      * failFast parameter determines whether we should use a storage session
      * which ensures fast failure detection of the entity that inserts the
      * state data.
      * The method is asynchronous, returning an observable that when subscribed
      * to will execute the remove and will emit one notification with the result
      * of the operation. */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def removeValue(clazz: Class[_], id: ObjId, key: String, value: String,
                    failFast: Boolean = false)
    : Observable[StateResult]

    /** Gets the set of values corresponding to a state key from the state of
      * the current namespace. The method is asynchronous, returning an
      * observable that when subscribed to will execute the get and will emit
      * one notification with the request result. */
    @throws[ServiceUnavailableException]
    def getKey(clazz: Class[_], id: ObjId, key: String): Observable[StateKey]

    /** The same as the previous `getKey`, except that this method returns
      * the state key value for the specified namespace. */
    @throws[ServiceUnavailableException]
    def getKey(namespace: String, clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey]

    /** Returns an observable for a state key of the current namespace. Upon
      * subscription, the observable will emit a notification with current set
      * of values corresponding to key and thereafter an additional notification
      * whenever the set of values has changed. The observable does not emit
      * notifications for successful write operations, which do not modify the
      * value set.
      * - If the namespace state does not exist, the observable completes
      *   immediately.
      * - If the object class or object instance do not exist, the observable
      *   completes immediately.
      * - If a value for the state key has not been set, the observable returns
      *   a value option equal to [[None]].
      */
    @throws[ServiceUnavailableException]
    def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey]

    /** The same as the previous `keyObservable` method, except that this method
      * returns an observable for the state of the specified namespace. */
    @throws[IllegalArgumentException]
    @throws[ServiceUnavailableException]
    def keyObservable(namespace: String, clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey]

    /** The same as the previous `keyObservable` method, except that this method
      * returns an observable for the state of the last namespace identifier
      * emitted by the input `namespaces` observable.
      *
      * The output observable will not emit a notification until the input
      * observable emits at least one namespace identifier. If the specified
      * namespace state does not exist, the observable emits [[None]] as key
      * value. Therefore, the input observable can emit a non-existing namespace
      * identifier such as `null` to stop receiving updates from the last
      * emitted namespace state.
      */
    @throws[ServiceUnavailableException]
    def keyObservable(namespaces: Observable[String], clazz: Class[_], id: ObjId,
                      key: String): Observable[StateKey]

    /** Returns a number uniquely identifying the current owner.  Note that
      * this value has nothing to do with the node ID.
      * The failFast parameter determines whether a session to storage
      * ensuring fast failover should be used or not.
      */
    def ownerId(failFast: Boolean = false): Long

    /** Gets the key type for the given class and key. */
    @throws[IllegalArgumentException]
    protected[this] def getKeyType(clazz: Class[_], key: String): KeyType = {
        stateInfo.getOrElse(clazz, throw new IllegalArgumentException(
            s"Class ${clazz.getSimpleName} is not registered")).keys
                 .getOrElse(key, throw new IllegalArgumentException(
            s"Key $key is not registered for class ${clazz.getSimpleName}"))
    }

}
