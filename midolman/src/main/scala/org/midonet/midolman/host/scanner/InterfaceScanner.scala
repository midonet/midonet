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

package org.midonet.midolman.host.scanner

import rx.{Observer, Scheduler, Subscription}

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.netlink.rtnetlink.AbstractRtnetlinkConnection

/**
 * Interface data scanning API. It's job is scan and find out the
 * current list of interface data from the local system and notify
 * observers whenever there are changes.
 */
trait InterfaceScanner extends AbstractRtnetlinkConnection {
    /**
     * Let an Observer subscribe notifications from InterfaceScanner.
     *
     * @param obs an Observer to subscribe a published set of all L2 Ethernet
     *            interfaces on the host notified when a interface is
     *            added/remove to/from the host.
     * @return Subscription object through which users can unsubscribe events
     *         from InterfaceScanner.
     */
    def subscribe(obs: Observer[Set[InterfaceDescription]],
                  scheduler: Option[Scheduler] = None): Subscription
    /**
     * Start scanning and notifying the interfaces on the host.
     */
    def start(): Unit

    /**
     * Stop scanning and notifying the interfaces on the host.
     */
    def stop(): Unit
}
