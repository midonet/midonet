/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ValueFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.netlink.family.CtrlFamily;
import static com.midokura.util.netlink.Netlink.Flag;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/27/12
 */
public class NetlinkConnection {

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkConnection.class);

    private AtomicInteger sequence = new AtomicInteger(1);

    protected Map<Integer, ValueFuture<List<ByteBuffer>>>
        outstandingRequests = new ConcurrentHashMap<Integer, ValueFuture<List<ByteBuffer>>>();

    protected Map<Integer, List<ByteBuffer>>
        outstandingRequestBuffers = new ConcurrentHashMap<Integer, List<ByteBuffer>>();

    final static CtrlFamily ctrlNetlinkFamily = new CtrlFamily();

    private NetlinkChannel channel;

    public NetlinkConnection(NetlinkChannel channel) {
        this.channel = channel;
    }

    protected NetlinkChannel getChannel() {
        return channel;
    }

    public Future<Short> getFamilyId(String familyName) throws Exception {

        NetlinkMessage req = new NetlinkMessage(64);
        req.putStringAttr(CtrlFamily.Attr.FAMILY_NAME, familyName);

        Future<List<ByteBuffer>> or = makeRequest(
            ctrlNetlinkFamily,
            Flag.or(Flag.NLM_F_REQUEST),
            CtrlFamily.Cmd.GETFAMILY,
            req.getBuffer());

        return Futures.compose(or, new Function<List<ByteBuffer>, Short>() {
            @Override
            public Short apply(@Nullable List<ByteBuffer> input) {
                if (input == null)
                    return 0;

                NetlinkMessage res = new NetlinkMessage(input.get(0));
                // read result from res
                return res.findShortAttr(CtrlFamily.Attr.FAMILY_ID);
            }
        });
    }

    public Future<Integer> getMulticastGroup(String familyName, final String groupName)
        throws Exception {
        NetlinkMessage req = new NetlinkMessage(64);
        req.putStringAttr(CtrlFamily.Attr.FAMILY_NAME, familyName);

        final Future<List<ByteBuffer>> or = makeRequest(
            ctrlNetlinkFamily,
            Flag.or(Flag.NLM_F_REQUEST),
            CtrlFamily.Cmd.GETFAMILY,
            req.getBuffer());

        return Futures.compose(or, new Function<List<ByteBuffer>, Integer>() {
            @Override
            public Integer apply(@Nullable List<ByteBuffer> input) {
                if (input == null)
                    return 0;

                NetlinkMessage res = new NetlinkMessage(input.get(0));

                NetlinkMessage sub = res.findNested(
                    CtrlFamily.Attr.MCAST_GROUPS);

                while (sub.hasRemaining()) {
                    sub.buf.getShort();
                    sub.buf.getShort();

                    Integer id =
                        sub.getIntAttr(CtrlFamily.Attr.MCAST_GRP_ID);
                    String name =
                        sub.getStringAttr(CtrlFamily.Attr.MCAST_GRP_NAME);

                    if (name.equals(groupName)) {
                        return id;
                    }
                }

                return 0;
            }
        });
    }

    protected <Cmd extends Enum<Cmd> & Netlink.ByteConstant>
    Future<List<ByteBuffer>> makeRequest(
        Netlink.CommandFamily<Cmd, ?> commandFamily, short flags,
        Cmd cmd, ByteBuffer req) throws IOException {
        ValueFuture<List<ByteBuffer>> settableFuture =
            ValueFuture.create();

        int seq = sequence.getAndIncrement();
        outstandingRequests.put(seq, settableFuture);
        outstandingRequestBuffers.put(seq, new ArrayList<ByteBuffer>(5));

        ByteBuffer request = ByteBuffer.allocateDirect(1024);
        request.order(ByteOrder.nativeOrder());

        int totalSize = 20 + req.remaining();

        putNetlinkHeader(request,
                         commandFamily.getFamilyId(),
                         flags, seq,
                         cmd.getValue(),
                         commandFamily.getVersion());

        request.put(req);

        // set the header length
        request.putInt(0, totalSize);
        request.flip();

        // send the request
        channel.write(request);

        return settableFuture;
    }

    public void handleEvent(SelectionKey key) throws IOException {

        // allocate buffer for the reply
        ByteBuffer reply = ByteBuffer.allocateDirect(1024);
        reply.order(ByteOrder.nativeOrder());

        // read the reply
        channel.read(reply);

        reply.flip();
        reply.mark();

        // read the nlmsghdr and check for error
        int len = reply.getInt();           // length
        short type = reply.getShort();      // type
        short flags = reply.getShort();     // flags
        int seq = reply.getInt();           // sequence no.
        int pid = reply.getInt();           // pid

        ValueFuture<List<ByteBuffer>> request = outstandingRequests.get(seq);
        if (request == null) {
            log.warn("couldn't find outstanding for seq = " + seq);
            return;
        }

        Netlink.MessageType messageType = Netlink.MessageType.findById(type);

        if ( messageType == null ) {
            log.error("Got unknown message with type: {}", type);
            return;
        }

        switch (messageType) {
            case NLMSG_ERROR:
                int error = reply.getInt();

                // read header which caused the error
                int errLen = reply.getInt();        // length
                short errType = reply.getShort();   // type of error
                short errFlags = reply.getShort();  // flags of the error
                int errSeq = reply.getInt();        // sequence of the error
                int errPid = reply.getInt();        // pid of the error

                outstandingRequests.remove(seq);

                // TODO: enhance this
                request.setException(new Exception("error = " + error));
                break;
            default:
                // read genl header
                byte genlCmd = reply.get(); // command
                byte genlVer = reply.get(); // version
                reply.getShort();           // reserved

                List<ByteBuffer> buffers = outstandingRequestBuffers.get(seq);

                if (type != Netlink.MessageType.NLMSG_DONE.value) {
                    ByteBuffer payload = reply.slice();
                    payload.order(ByteOrder.nativeOrder());

                    if (buffers != null) {
                        buffers.add(payload);
                    }
                }

                if (type == Netlink.MessageType.NLMSG_DONE.value ||
                    (flags & Flag.NLM_F_MULTI.getValue()) == 0) {
                    outstandingRequests.remove(seq);
                    outstandingRequestBuffers.remove(seq);

                    request.set(buffers);
                }
        }
    }

    private int putNetlinkHeader(ByteBuffer request, short family, short flags,
                                 int seq, byte cmd, byte version) {

        int startPos = request.position();
        int pid = channel.getRemoteAddress().getPid();

        // put netlink header
        request.putInt(0);              // nlmsg_len
        request.putShort(family);       // nlmsg_type
        request.putShort(flags);        // nlmsg_flags
        request.putInt(seq);            // nlmsg_seq
        request.putInt(pid);            // nlmsg_pid

        // put genl header
        request.put(cmd);               // cmd
        request.put(version);           // version
        request.putShort((short) 0);    // reserved

        return request.position() - startPos;
    }
}
