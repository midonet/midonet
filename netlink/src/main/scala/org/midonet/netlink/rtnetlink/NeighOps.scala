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

import org.midonet.netlink._
import org.midonet.packets.{IPAddr, MAC}

object NeighOps {
    def addNeighEntry(link: Link, ip: IPAddr, mac: MAC): Unit = {
        val buf = BytesUtil.instance.allocateDirect(256)
        val channel = new NetlinkChannelFactory().create(
            blocking = false,
            NetlinkProtocol.NETLINK_ROUTE)
        val protocol = new RtnetlinkProtocol(channel.getLocalAddress.getPid)
        val writer = new NetlinkBlockingWriter(channel)
        val reader = new NetlinkReader(channel)

        val neigh = new Neigh()
        neigh.ip = ip
        neigh.mac = mac
        neigh.ndm.`type` = Neigh.Type.NDA_LLADDR
        neigh.ndm.state = Neigh.State.NUD_PERMANENT
        neigh.ndm.family = Addr.Family.AF_INET
        neigh.ndm.ifindex = link.ifi.index
        buf.clear()
        protocol.prepareNeighAdd(buf, neigh)
        writer.write(buf)
        reader.read(buf) // in case there are errors
    }
}
