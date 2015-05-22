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

package org.midonet.netlink.rtnetlink

import org.midonet.netlink.NetlinkUtil._
import org.midonet.netlink._
import org.midonet.util.concurrent.NanoClock

class MockRtnetlinkConnection(channel: NetlinkChannel,
                              maxPendingRequests: Int,
                              maxRequestSize: Int,
                              clock: NanoClock)
        extends RtnetlinkConnection(channel, maxPendingRequests,
            maxRequestSize, clock) {
    override val readBuf = BytesUtil.instance.allocate(NETLINK_READ_BUF_SIZE)

    override val requestBroker = new NetlinkRequestBroker(writer, reader,
        maxPendingRequests, maxRequestSize, readBuf, clock)
}
