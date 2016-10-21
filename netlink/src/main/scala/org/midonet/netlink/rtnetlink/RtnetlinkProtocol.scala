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

import org.midonet.netlink.{NLFlag, NetlinkMessage, NetlinkMessageWrapper}
import org.midonet.packets.{IPv4Addr, MAC}

final class RtnetlinkProtocol(pid: Int) {
    def messageFor(buf: ByteBuffer,
                   rtnetlinkType: Short): NetlinkMessageWrapper =
        NetlinkMessageWrapper(buf)
            .withType(rtnetlinkType)

    def enum(buf: ByteBuffer, rtnetlinkType: Short): Unit =
        messageFor(buf, rtnetlinkType)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
            .finalize(pid)

    def prepareGetQdisc(buf: ByteBuffer, ifindex: Int): Unit = {
        messageFor(buf, Rtnetlink.Type.GETQDISC)
            .withFlags((NLFlag.REQUEST | NLFlag.Get.DUMP).toShort)
        Tcmsg.addGetQdiscTcmsg(buf, ifindex)
    }

    def tcmsgIngressQdisc(buf: ByteBuffer, ifindex: Int, op: Short,
                          flag: Short): Unit = {
        val message = messageFor(buf, op).withFlags(flag)
        Tcmsg.addIngressQdiscTcmsg(buf, ifindex)
        NetlinkMessage.writeStringAttr(buf, TcmsgType.TCA_KIND, "ingress")
        NetlinkMessage.writeAttrEmpty(buf, TcmsgType.TCA_OPTIONS)
        message.finalize(pid)
    }

    def prepareDeleteIngressQdisc(buf: ByteBuffer, ifindex: Int): Unit = {
        val flags = NLFlag.REQUEST | NLFlag.ACK
        tcmsgIngressQdisc(buf, ifindex, Rtnetlink.Type.DELQDISC, flags.toShort)
    }

    def prepareAddIngressQdisc(buf: ByteBuffer, ifindex: Int): Unit = {
        val flags = NLFlag.REQUEST |
                    NLFlag.New.CREATE |
                    NLFlag.New.EXCL |
                    NLFlag.ACK
        tcmsgIngressQdisc(buf, ifindex, Rtnetlink.Type.NEWQDISC, flags.toShort)
    }

    def prepareAddPoliceFilter(buf: ByteBuffer, ifindex: Int,
                               rate: Int, burst: Int, mtu: Int,
                               tickInUsec: Double): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.NEWTFILTER)
            .withFlags((NLFlag.REQUEST |
                        NLFlag.New.CREATE |
                        NLFlag.New.EXCL | NLFlag.ACK).toShort)
        Tcmsg.addIngressFilter(buf, ifindex)
        NetlinkMessage.writeStringAttr(buf, TcmsgType.TCA_KIND, "basic")
        val kind_beg = buf.position()

        NetlinkMessage.writeAttrEmpty(buf, TcmsgType.TCA_OPTIONS)
        val bp_pos =  buf.position()
        NetlinkMessage.writeAttrEmpty(buf, TcmsgBasicType.TCA_BASIC_POLICE)
        NetlinkMessage.writeAttr(buf, TcmsgPoliceType.TCA_POLICE_TBF,
                                 new TcPolice(rate, burst, mtu, tickInUsec))
        NetlinkMessage.writeAttr(buf, TcmsgPoliceType.TCA_POLICE_RATE,
                                 new TcRtab(mtu, rate, tickInUsec))
        buf.putShort(bp_pos, (buf.position() - bp_pos).toShort)
        buf.putShort(kind_beg, (buf.position() - kind_beg).toShort)
        message.finalize(pid)
    }

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

    def prepareLinkGet(buf: ByteBuffer, name: String): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.GETLINK)
            .withFlags(NLFlag.REQUEST)
        Link.describeGetRequest(buf, name)
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
        Addr.describeGetRequest(buf)
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

    def prepareNeighAdd(buf: ByteBuffer, neigh: Neigh): Unit = {
        val message = messageFor(buf, Rtnetlink.Type.NEWNEIGH)
            .withFlags((NLFlag.REQUEST | NLFlag.New.CREATE |
                        NLFlag.New.REPLACE).toShort)
        neigh.describeNewRequest(buf)
        message.finalize(pid)
    }
}
