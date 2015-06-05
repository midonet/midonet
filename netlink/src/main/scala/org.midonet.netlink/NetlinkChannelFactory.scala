/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.netlink

import org.midonet.netlink.NetlinkProtocol.NETLINK_GENERIC
import org.midonet.netlink.NetlinkUtil.NO_NOTIFICATION

class NetlinkChannelFactory {
    def create(blocking: Boolean = false,
               protocol: NetlinkProtocol = NETLINK_GENERIC,
               notificationGroups: Int = NO_NOTIFICATION): NetlinkChannel = {
        try {
            val channel = Netlink.selectorProvider
                .openNetlinkSocketChannel(
                    protocol, notificationGroups)

            channel.connect(new Netlink.Address(0))
            channel.configureBlocking(blocking)
            channel
        } catch { case e: Exception =>
            throw new RuntimeException("Error connecting to Netlink", e)
        }
    }
}

class MockNetlinkChannelFactory extends NetlinkChannelFactory {

    val channel: MockNetlinkChannel = new MockNetlinkChannel(Netlink.selectorProvider,
                                                             NetlinkProtocol.NETLINK_GENERIC)

    override def create(blocking: Boolean = false,
                        protocol: NetlinkProtocol = NETLINK_GENERIC,
                        notificationGroups: Int = NO_NOTIFICATION) = channel
}
