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

package org.midonet.odp

import org.midonet.netlink.RtnetlinkConnection
import org.midonet.netlink.rtnetlink.{Addr, Route, Neigh, Link}
import org.midonet.packets.{MAC, IPv4Addr}

/**
 *
 */
class RtnetlinkConnectionOps(val rtCon: RtnetlinkConnection) {

    import OvsConnectionOps._

    def enumLink() = toFuture[java.util.Set[Link]] { rtCon linkGet _ }

    def setLinkAddr(link: Link, mac: MAC) = toFuture[java.lang.Boolean] { rtCon linkSetAddr(link, mac, _) }

    def setLinkUp(link: Link) = toFuture[java.lang.Boolean] { rtCon linkSetUp(link, _) }

    def setLinkDown(link: Link) = toFuture[java.lang.Boolean] { rtCon linkSetDown(link, _) }

    def enumAddr() = toFuture[java.util.Set[Addr]] { rtCon addrGet  _ }

    def addAddr(ipv4: IPv4Addr, prefixlen: Int, link: Link) =
        toFuture[Addr] { rtCon.addrNew(Addr.buildWithIPv4(ipv4, prefixlen, link.ifi.ifi_index), _)}

    def delAddr(ipv4: IPv4Addr, prefixlen: Int, link: Link) =
        toFuture[Addr] { rtCon.addrDel(Addr.buildWithIPv4(ipv4, prefixlen, link.ifi.ifi_index), _)}

    def enumRoute() = toFuture[java.util.Set[Route]] { rtCon routeGet  _ }

    def addRoute(dst: IPv4Addr, prefix: Int, gw: IPv4Addr, link: Link) =
        toFuture[Route] { rtCon.routeNew(dst, prefix, gw, link, _)}

    def getRoute(dst: IPv4Addr) = toFuture[java.util.Set[Route]] { rtCon routeGet (dst, _) }

    def enumNeigh() = toFuture[java.util.Set[Neigh]] { rtCon neighGet _ }

}
