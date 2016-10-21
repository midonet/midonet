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

import java.nio.ByteBuffer

import org.midonet.netlink._
import org.midonet.packets._

import scala.util.control.NonFatal

object LinkOps {

    case class Veth(dev: Link, peer: Link)

    val VETH_INFO_UNSPEC: Byte = 0
    val VETH_INFO_PEER: Byte = 1

    def createVethPair(devName: String = null,
                       peerName: String = null,
                       up: Boolean = true,
                       devMac: MAC = null,
                       peerMac: MAC = null,
                       devMtu: Int = 65535,
                       peerMtu: Int = 65535): Veth = {
        val (channel, protocol) = prepare()
        try {
            val buf = BytesUtil.instance.allocateDirect(2048)
            var dev = build(devName, up, devMtu, devMac)
            dev.info.kind = Link.NestedAttrValue.LinkInfo.KIND_VETH
            var peer = build(peerName, up = false, peerMtu, peerMac)
            val peerBb = BytesUtil.instance.allocate(1024)
            NetlinkMessage.writeAttr(peerBb, VETH_INFO_PEER, peer)
            peerBb.flip()
            dev.info.data = peerBb

            val writer = new NetlinkBlockingWriter(channel)
            val reader = new NetlinkReader(channel)

            protocol.prepareLinkCreate(buf, dev)
            writer.write(buf)

            buf.clear()
            protocol.prepareLinkGet(buf, devName)
            writer.write(buf)
            dev = readLink(reader, buf)

            if (up) {
                try {
                    peer.ifi.flags |= Link.Flag.IFF_UP
                    buf.clear()
                    protocol.prepareLinkSet(buf, peer)
                    writer.write(buf)
                } catch { case NonFatal(e) =>
                    deleteLink(dev)
                    throw e
                }
            }

            buf.clear()
            protocol.prepareLinkGet(buf, peerName)
            writer.write(buf)
            peer = readLink(reader, buf)

            Veth(dev, peer)
        } finally {
            channel.close()
        }
    }

    def deleteLink(link: Link): Unit = {
        val (channel, protocol) = prepare()
        try {
            val buf = BytesUtil.instance.allocateDirect(2048)
            val writer = new NetlinkBlockingWriter(channel)
            val reader = new NetlinkReader(channel)

            protocol.prepareLinkDel(buf, link)
            writer.write(buf)
            reader.read(buf) // in case there are errors
        } finally {
            channel.close()
        }
    }

    def deleteLink(devName: String): Unit = {
        val link = new Link()
        link.setName(devName)
        deleteLink(link)
    }

    def setAddress(link: Link, ipsubnet: IPSubnet[_ <: IPAddr], mac: MAC = null): Unit = {
        val (channel, protocol) = prepare()
        try {
            val buf = BytesUtil.instance.allocateDirect(2048)
            val writer = new NetlinkBlockingWriter(channel)
            val reader = new NetlinkReader(channel)

            val addr = new Addr()
            addr.ifa.family = Addr.Family.AF_INET
            addr.ifa.index = link.ifi.index
            addr.ifa.prefixLen = ipsubnet.getPrefixLen.toByte
            ipsubnet.getAddress match {
                case ip: IPv4Addr => addr.ipv4.add(ip)
                case ip: IPv6Addr => addr.ipv6.add(ip)
            }
            protocol.prepareAddrNew(buf, addr)
            writer.write(buf)
            buf.clear()
            writer.write(buf)
            reader.read(buf) // in case there are errors

            if (mac ne null) {

            }
        } finally {
            channel.close()
        }
    }

    private def prepare(): (NetlinkChannel, RtnetlinkProtocol) = {
        val channel = new NetlinkChannelFactory().create(
                blocking = false,
                NetlinkProtocol.NETLINK_ROUTE)
        val protocol = new RtnetlinkProtocol(channel.getLocalAddress.getPid)
        (channel, protocol)
    }

    private def build(name: String = null,
                      up: Boolean = false,
                      mtu: Int = 0,
                      mac: MAC = null,
                      index: Int = 0) = {
        val link = new Link()
        link.ifi.family = Addr.Family.AF_UNSPEC
        link.ifi.`type` = Link.Type.ARPHRD_ETHER
        link.setName(name)
        if (up) {
            link.ifi.flags = Link.Flag.IFF_UP
        }
        link.mtu = mtu
        link.mac = mac
        link
    }

    def readLink(reader: NetlinkReader, buf: ByteBuffer): Link = {
        buf.clear()
        reader.read(buf)
        buf.flip()
        buf.position(NetlinkMessage.HEADER_SIZE)
        Link.buildFrom(buf)
    }
}
