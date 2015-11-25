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

import org.midonet.netlink.{NetlinkProtocol, NetlinkMessageWrapper, NLFlag}
import org.midonet.packets.{MAC, IPv4Addr}

final class RtnetlinkProtocol(pid: Int) {
    def messageFor(buf: ByteBuffer,
                   rtnetlinkType: Short): NetlinkMessageWrapper =
        NetlinkMessageWrapper(buf)
            .withType(rtnetlinkType)

    def enum(buf: ByteBuffer, rtnetlinkType: Short): Unit =
        messageFor(buf, rtnetlinkType)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
            .finalize(pid)

    def prepareLinkList(buf: ByteBuffer): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETLINK)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
        Link.describeListRequest(buf)
        message.finalize(pid)
    }

    def prepareLinkGet(buf: ByteBuffer, ifIndex: Int): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETLINK)
            .withFlags(NLFlag.REQUEST)
        Link.describeGetRequest(buf, ifIndex)
        message.finalize(pid)
    }

    def prepareLinkCreate(buf: ByteBuffer, link: Link): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.NEWLINK)
            .withFlags((NLFlag.REQUEST | NLFlag.New.CREATE |
                        NLFlag.New.EXCL).toShort)
        Link.describeSetRequest(buf, link)
        message.finalize(pid)
    }

    def prepareLinkDel(buf: ByteBuffer, link: Link): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.DELLINK)
            .withFlags(NLFlag.REQUEST)
        Link.describeDelRequest(buf, link)
        message.finalize(pid)
    }

    def prepareLinkSetAddr(buf: ByteBuffer, link: Link, mac: MAC): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.SETLINK)
            .withFlags((NLFlag.REQUEST | NLFlag.ACK).toShort)
        Link.describeSetAddrRequest(buf, link, mac)
        message.finalize(pid)
    }

    def prepareLinkSet(buf: ByteBuffer, link: Link): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.SETLINK)
            .withFlags(NLFlag.REQUEST)
        Link.describeSetRequest(buf, link)
        message.finalize(pid)
    }

    def prepareAddrList(buf: ByteBuffer): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETADDR)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
        Addr.describeListRequest(buf)
        message.finalize(pid)
    }

    def prepareAddrGet(buf: ByteBuffer, linkIndex: Int, family: Byte): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETADDR)
            .withFlags(NLFlag.REQUEST)
        Addr.describeGetRequest(buf, linkIndex, family)
        message.finalize(pid)
    }

    def prepareAddrNew(buf: ByteBuffer, addr: Addr): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.NEWADDR)
            .withFlags((NLFlag.REQUEST | NLFlag.New.EXCL |
                        NLFlag.New.CREATE).toShort)
        Addr.describeNewRequest(buf, addr)
        message.finalize(pid)
    }

    def prepareRouteList(buf: ByteBuffer): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETROUTE)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
        Route.describeListRequest(buf)
        message.finalize(pid)
    }

    def prepareRouteGet(buf: ByteBuffer, dst: IPv4Addr): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETROUTE)
            .withFlags(NLFlag.REQUEST)
        Route.describeGetRequest(buf, dst)
        message.finalize(pid)
    }

    def prepareRouteNew(buf: ByteBuffer, dst: IPv4Addr, prefix: Int,
                        gw: IPv4Addr, link: Link): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.NEWROUTE).withFlags(
                (NLFlag.REQUEST | NLFlag.ECHO | NLFlag.New.CREATE).toShort)
        Route.describeNewRequest(buf, dst, prefix, gw, link)
        message.finalize(pid)
    }

    def prepareNeighList(buf: ByteBuffer): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETNEIGH)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
        Neigh.describeListRequest(buf)
        message.finalize(pid)
    }
}
