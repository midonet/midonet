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

import scala.concurrent.Future

import org.midonet.cluster.rpc.State.ProxyResponse.Notify

trait StateTableObserver {

    /**
      * Sends a notification message via this [[StateTableObserver]]. The
      * message may include a snapshot, an update or a completion indication
      * of the stream. The method returns a future, which completes when
      * the observer is ready to accept the next notification. This provides a
      * back-pressure mechanism such the sender can throttle the rate of
      * notifications.
      */
    def next(notify: Notify): Future[AnyRef]

}
