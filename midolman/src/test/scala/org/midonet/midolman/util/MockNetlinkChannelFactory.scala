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

package org.midonet.midolman.util

import org.midonet.netlink.NetlinkProtocol.NETLINK_GENERIC

import org.midonet.netlink.{NetlinkUtil, MockNetlinkChannel, NetlinkChannelFactory, NetlinkProtocol}

class MockNetlinkChannelFactory extends NetlinkChannelFactory {
    val selectorProvider = new MockSelectorProvider
    val channel = new MockNetlinkChannel(
        selectorProvider,
        NetlinkProtocol.NETLINK_GENERIC)
    channel.configureBlocking(false)

    override def create(blocking: Boolean = false,
                        protocol: NetlinkProtocol = NETLINK_GENERIC,
                        notificationGroups: Int = NetlinkUtil.NO_NOTIFICATION) = {
        channel
    }
}
