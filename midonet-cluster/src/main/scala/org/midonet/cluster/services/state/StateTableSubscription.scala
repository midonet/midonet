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

package org.midonet.cluster.services.state

/**
  * Represents a unique subscription to a state table observable. This enriches
  * a regular RX subscription by providing access to the unique subscription
  * identifier as well as allowing requests in the context of the subscription.
  * These requests will affect only the observers that receive updates for this
  * subscription.
  */
trait StateTableSubscription {

    /**
      * @return The subscription identifier.
      */
    def id: Long

    /**
      * Terminates the current subscription.
      */
    def unsubscribe(): Unit

    /**
      * Requests a snapshot of the state table corresponding to this
      * subscription. The snapshot will be emitted in the observable stream
      * corresponding to the subscription.
      *
      * @param lastVersion An optional last version. If present, and if
      *                    supported, this will requests the changes since
      *                    the specified version instead of a snapshot.
      */
    def refresh(lastVersion: Option[Long]): Unit

}
