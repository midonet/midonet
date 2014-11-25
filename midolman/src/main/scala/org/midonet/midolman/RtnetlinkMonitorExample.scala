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

package org.midonet.midolman

import org.midonet.config.ConfigProvider
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.SelectorBasedRtnetlinkConnection
import org.midonet.netlink.Rtnetlink
import org.midonet.odp.RtnetlinkConnectionOps
import org.midonet.netlink.rtnetlink.{Route, Neigh, Addr, Link}
import org.midonet.netlink.RtnetlinkConnection.NotificationHandler
import java.nio.ByteBuffer
import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration.Duration
import org.midonet.packets.{IPv4Addr, MAC}

/**
 *
 */
object RtnetlinkMonitorExample {

    def main(args: Array[String]) {
        import ExecutionContext.Implicits.global
        import scala.collection.JavaConversions._

        val configPath = "/etc/midolman/midolman.conf"
        val configProvider = ConfigProvider.fromIniFile(configPath)
        val config = configProvider.getConfig(classOf[MidolmanConfig])
        val groups =
            Rtnetlink.Group.LINK.bitmask() |
            Rtnetlink.Group.IPV4_IFADDR.bitmask() |
            Rtnetlink.Group.IPV4_ROUTE.bitmask() |
            Rtnetlink.Group.NEIGH.bitmask()
        var interfaces = scala.collection.concurrent.TrieMap[String, Link]()
        val handler = new NotificationHandler {
            override def notify(msgtype: Short, seq: Int, pid: Int, buf: ByteBuffer) {
                msgtype match {
                    case Rtnetlink.Type.NEWLINK |
                         Rtnetlink.Type.DELLINK |
                         Rtnetlink.Type.GETLINK =>
                        val link: Link  = Link.buildFrom(buf)
                        println("notified: " + link)
                        interfaces.update(link.ifname, link)
                    case Rtnetlink.Type.NEWADDR |
                         Rtnetlink.Type.DELADDR |
                         Rtnetlink.Type.GETADDR =>
                        val addr: Addr = Addr.buildFrom(buf)
                        println("notified: " + addr)
                    case Rtnetlink.Type.NEWROUTE |
                         Rtnetlink.Type.DELROUTE |
                         Rtnetlink.Type.GETROUTE =>
                        val route: Route = Route.buildFrom(buf)
                        println("notified: " + route)
                    case Rtnetlink.Type.NEWNEIGH |
                         Rtnetlink.Type.DELNEIGH |
                         Rtnetlink.Type.GETNEIGH =>
                        val neigh: Neigh = Neigh.buildFrom(buf)
                        println("notified: " + neigh)
                    case _ =>
                        printf("notified: [XXX] type=%d", msgtype)
                }
            }
        }

        val selector = new SelectorBasedRtnetlinkConnection("rtnetlink-connection", config, groups, handler)
        selector.start()
        val conn = selector.getConnection
        val ops = new RtnetlinkConnectionOps(conn)

        val dump = ops.enumLink flatMap { s =>
            for (t <- asScalaSet(s)) {
                println("dump: " + t)
                interfaces += (t.ifname -> t)
            }
            ops.enumAddr
        } flatMap { s =>
            for (t <- asScalaSet(s); if t.ifa.ifa_family == Addr.Family.AF_INET)
                println("dump: " + t)
            ops.enumRoute
        } flatMap { s =>
            for (t <- asScalaSet(s); if t.rtm.rtm_family == Addr.Family.AF_INET)
                println("dump: " + t)
            ops.enumNeigh
        } flatMap { s =>
            for (t <- asScalaSet(s); if t.ndm.ndm_family == Addr.Family.AF_INET)
                println("dump: " + t)
            Future.successful()
        }
        Await.result(dump, Duration.Inf)

        val ifname = "veth3"
        val eth = interfaces.get(ifname) match {
            case Some(l) => l
            case None => sys.exit(2)
        }
        val test = ops.setLinkDown(eth) flatMap {
            _ => ops.setLinkAddr(eth, MAC.fromString("aa:bb:cc:00:01:02"))
        } flatMap {
            _ =>
                val link = interfaces.get(ifname)
                assert(link.isDefined)
                assert((link.get.ifi.ifi_flags & Link.NetDeviceFlags.IFF_UP) == 0)
                assert(link.get.mac.equals(MAC.fromString("aa:bb:cc:00:01:02")))
                Future.successful()
        } flatMap {
            _ => ops.setLinkAddr(eth, eth.mac)
        } flatMap {
            _ => ops.setLinkUp(eth)
        } flatMap {
            _ =>
                val link = interfaces.get(ifname)
                assert(link.isDefined)
                assert((link.get.ifi.ifi_flags & Link.NetDeviceFlags.IFF_UP) != 0)
                assert(link.get.mac.equals(eth.mac))
                Future.successful()
        } flatMap {
            _ => ops.addAddr(IPv4Addr.fromString("192.168.240.1"), 24, eth)
        } flatMap {
            addr =>
                assert(addr.ipv4.contains(IPv4Addr.fromString("192.168.240.1")))
                Future.successful()
        } flatMap {
            _ => ops.addRoute(IPv4Addr.fromString("192.168.241.0"), 24, IPv4Addr.fromString("192.168.240.2"), eth)
        } flatMap {
            route =>
                assert(route.rtm.rtm_dst_len == 24)
                assert(route.dst.equals(IPv4Addr.fromString("192.168.241.0")))
                assert(route.gw.equals(IPv4Addr.fromString("192.168.240.2")))
                Future.successful()
        } flatMap {
            _ => ops.addRoute(null, 0, IPv4Addr.fromString("192.168.240.240"), eth)
        } flatMap {
            route =>
                assert(route.rtm.rtm_dst_len == 0)
                assert(route.dst == null)
                assert(route.gw.equals(IPv4Addr.fromString("192.168.240.240")))
                Future.successful()
        } flatMap {
            _ => ops.getRoute(IPv4Addr.fromString("8.8.8.8"))
        } flatMap {
            routes =>
                for (route <- routes.iterator()) {
                    println("route found: " + route)
                    assert(route.rtm.rtm_dst_len == 32)
                    assert(route.dst == IPv4Addr.fromString("8.8.8.8"))
                    assert(route.gw.equals(IPv4Addr.fromString("192.168.240.240")))
                }
                Future.successful()
        } flatMap {
            _ => ops.getRoute(IPv4Addr.fromString("192.168.241.240"))
        } flatMap {
            routes =>
                for (route <- routes.iterator()) {
                    println("route fonud: " + route)
                    assert(route.rtm.rtm_dst_len == 32)
                    assert(route.dst == IPv4Addr.fromString("192.168.241.240"))
                    assert(route.gw.equals(IPv4Addr.fromString("192.168.240.2")))
                }
                Future.successful()
        } flatMap {
            _ => ops.delAddr(IPv4Addr.fromString("192.168.240.1"), 24, eth)
        } flatMap {
            _ => ops.setLinkDown(eth)
        } flatMap {
            _ => Future.successful()
        }
        Await.result(test, Duration.Inf)

        println("Monitoring...")
    }
}
