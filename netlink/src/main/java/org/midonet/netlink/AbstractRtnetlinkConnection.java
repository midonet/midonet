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

package org.midonet.netlink;

import java.nio.ByteBuffer;
import java.util.Set;

import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.rtnetlink.Addr;
import org.midonet.netlink.rtnetlink.Link;
import org.midonet.netlink.rtnetlink.Neigh;
import org.midonet.netlink.rtnetlink.Route;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public abstract class AbstractRtnetlinkConnection extends AbstractNetlinkConnection {

    private static final Logger log = LoggerFactory
            .getLogger(AbstractRtnetlinkConnection.class);

    public AbstractRtnetlinkConnection(NetlinkChannel channel, BufferPool sendPool) {
        super(channel, sendPool);
    }

    @Override
    protected void processMessageType(Bucket bucket, short type, short flags, int seq, int pid, ByteBuffer reply) {

        if (seq == 0) {
            handleNotification(type, (byte) -1, seq, pid, reply);
        } else  {
            // otherwise we are processing an answer to a request.
            processRequestAnswer(seq, flags, reply);
        }
    }

    protected <T> void sendNetlinkMessage(short type,
                                          int flags,
                                          ByteBuffer payload,
                                          Callback<T> callback,
                                          Reader<T> reader,
                                          long timeoutMillis) {
        serializeNetlinkHeader(payload, (short) flags, type);

        enqueueRequest(NetlinkRequest.makeSingle(callback, reader,
                payload, timeoutMillis));
    }

    protected <T> void sendMultiAnswerNetlinkMessage(short type,
                                                     int flags,
                                                     ByteBuffer payload,
                                                     Callback<Set<T>> callback,
                                                     Reader<T> reader,
                                                     long timeoutMillis) {
        serializeNetlinkHeader(payload, (short) flags, type);

        enqueueRequest(NetlinkRequest.makeMulti(callback, reader,
                payload, timeoutMillis));
    }


    private void serializeNetlinkHeader(ByteBuffer request, short flags, short type) {
        int size = request.limit();             // payload + headers size

        request.rewind(); // rewind for writing the header sections

        // netlink header section
        request.putInt(size);                   // nlmsg_len
        request.putShort(type);                 // nlmsg_type
        request.putShort(flags);                // nlmsg_flags
        request.position(seqPosition() + 4);    // skip nlmsg_seq
        request.putInt(pid());                  // nlmsg_pid

        request.rewind(); // rewind for writing to the channel
    }

    @Override
    protected int getHeaderLength() {
        // sizeof(netlink header)
        return 16;
    }

    public void linkGet(Callback<Set<Link>> callback) {
        linkGet(callback, DEF_REPLY_TIMEOUT);
    }

    public void linkGet(Callback<Set<Link>> callback, long timeoutMillis) {

        sendMultiAnswerNetlinkMessage(
                Rtnetlink.Type.GETLINK,
                NLFlag.REQUEST | NLFlag.Get.DUMP,
                Link.describeGetRequest(getBuffer()),
                callback,
                Link.deserializer,
                timeoutMillis);
    }

    public void linkSetAddr(Link link, MAC mac, Callback<Boolean> callback) {
        linkSetAddr(link, mac, callback, DEF_REPLY_TIMEOUT);
    }

    public void linkSetAddr(Link link, MAC mac, Callback<Boolean> callback, long timeoutMillis) {

        sendNetlinkMessage(
                Rtnetlink.Type.NEWLINK,
                NLFlag.REQUEST | NLFlag.ACK,
                Link.describeSetAddrRequest(getBuffer(), link, mac),
                callback,
                new Reader<Boolean>() {
                    @Override
                    public Boolean deserializeFrom(ByteBuffer source) {
                        return Boolean.TRUE;
                    }
                },
                timeoutMillis);
    }

    public void linkSetUp(Link link, Callback<Boolean> callback) {
        linkSetUp(link, callback, DEF_REPLY_TIMEOUT);
    }

    public void linkSetUp(Link link, Callback<Boolean> callback, long timeoutMillis) {
        Link newLink;
        try {
            newLink = (Link)link.clone();
            newLink.ifi.ifi_flags = Link.NetDeviceFlags.IFF_UP;
            newLink.ifi.ifi_change = Link.NetDeviceFlags.IFF_UP;

            linkSet(newLink, callback, timeoutMillis);
        } catch (CloneNotSupportedException e) {
            callback.onError(new NetlinkException(NetlinkException.ErrorCode.EINVAL, e.getMessage()));
        }
    }

    public void linkSetDown(Link link, Callback<Boolean> callback) {
        linkSetDown(link, callback, DEF_REPLY_TIMEOUT);
    }

    public void linkSetDown(Link link, Callback<Boolean> callback, long timeoutMillis) {
        Link newLink;
        try {
            newLink = (Link)link.clone();
            newLink.ifi.ifi_flags &= ~Link.NetDeviceFlags.IFF_UP;
            newLink.ifi.ifi_change = Link.NetDeviceFlags.IFF_UP;

            linkSet(newLink, callback, timeoutMillis);
        } catch (CloneNotSupportedException e) {
            callback.onError(new NetlinkException(NetlinkException.ErrorCode.EINVAL, e.getMessage()));
        }
    }

    public void linkSet(Link link, Callback<Boolean> callback, long timeoutMillis) {

        sendNetlinkMessage(
                Rtnetlink.Type.SETLINK,
                NLFlag.REQUEST | NLFlag.ACK,
                Link.describeSetRequest(getBuffer(), link),
                callback,
                new Reader<Boolean>() {
                    @Override
                    public Boolean deserializeFrom(ByteBuffer source) {
                        return Boolean.TRUE;
                    }
                },
                timeoutMillis);
    }

    public void addrNew(Addr addr, Callback<Addr> callback) {
        addrNew(addr, callback, DEF_REPLY_TIMEOUT);
    }

    public void addrNew(Addr addr, Callback<Addr> callback, long timeoutMillis) {

        sendNetlinkMessage(
                Rtnetlink.Type.NEWADDR,
                NLFlag.REQUEST | NLFlag.ECHO,
                Addr.describeNewRequest(getBuffer(), addr),
                callback,
                Addr.deserializer,
                timeoutMillis);
    }

    public void addrDel(Addr addr, Callback<Addr> callback) {
        addrDel(addr, callback, DEF_REPLY_TIMEOUT);
    }

    public void addrDel(Addr addr, Callback<Addr> callback, long timeoutMillis) {

        sendNetlinkMessage(
                Rtnetlink.Type.DELADDR,
                NLFlag.REQUEST | NLFlag.ECHO,
                Addr.describeNewRequest(getBuffer(), addr),
                callback,
                Addr.deserializer,
                timeoutMillis);
    }

    public void addrGet(Callback<Set<Addr>> callback) {
        addrGet(callback, DEF_REPLY_TIMEOUT);
    }

    public void addrGet(Callback<Set<Addr>> callback, long timeoutMillis) {

        sendMultiAnswerNetlinkMessage(
                Rtnetlink.Type.GETADDR,
                NLFlag.REQUEST | NLFlag.Get.DUMP,
                Addr.describeGetRequest(getBuffer()),
                callback,
                Addr.deserializer,
                timeoutMillis);
    }
    public void routeNew(IPv4Addr dst, int prefix, IPv4Addr gw, Link link, Callback<Route> callback) {
        routeNew(dst, prefix, gw, link, callback, DEF_REPLY_TIMEOUT);
    }

    public void routeNew(IPv4Addr dst, int prefix, IPv4Addr gw, Link link, Callback<Route> callback,
                         long timeoutMillis) {

        sendNetlinkMessage(
                Rtnetlink.Type.NEWROUTE,
                NLFlag.REQUEST | NLFlag.ECHO | NLFlag.New.CREATE,
                Route.describeNewRouteRequest(getBuffer(), dst, prefix, gw, link),
                callback,
                Route.deserializer,
                timeoutMillis);
    }

    public void routeGet(Callback<Set<Route>> callback) {
        routeGet(callback, DEF_REPLY_TIMEOUT);
    }

    public void routeGet(Callback<Set<Route>> callback, long timeoutMillis) {

        sendMultiAnswerNetlinkMessage(
                Rtnetlink.Type.GETROUTE,
                NLFlag.REQUEST | NLFlag.Get.DUMP,
                Route.describeRequest(getBuffer()),
                callback,
                Route.deserializer,
                timeoutMillis);
    }

    public void routeGet(IPv4Addr dst, Callback<Set<Route>> callback) {
        routeGet(dst, callback, DEF_REPLY_TIMEOUT);
    }

    public void routeGet(IPv4Addr dst, Callback<Set<Route>> callback, long timeoutMillis) {

        sendMultiAnswerNetlinkMessage(
                Rtnetlink.Type.GETROUTE,
                NLFlag.REQUEST | NLFlag.ECHO,
                Route.describeGetRequest(getBuffer(), dst),
                callback,
                Route.deserializer,
                timeoutMillis);
    }

    public void neighGet(Callback<Set<Neigh>> callback) {
        neighGet(callback, DEF_REPLY_TIMEOUT);
    }

    public void neighGet(Callback<Set<Neigh>> callback, long timeoutMillis) {

        sendMultiAnswerNetlinkMessage(
                Rtnetlink.Type.GETNEIGH,
                NLFlag.REQUEST | NLFlag.Get.DUMP,
                Neigh.describeGetRequest(getBuffer()),
                callback,
                Neigh.deserializer,
                timeoutMillis);
    }

}
