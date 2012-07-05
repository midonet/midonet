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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ValueFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.netlink.clib.cLibrary;
import com.midokura.util.reactor.Reactor;
import static com.midokura.util.netlink.Netlink.Flag;

/**
 * // TODO: Explain yourself.
 */
public abstract class AbstractNetlinkConnection {

    private static final Logger log = LoggerFactory
        .getLogger(AbstractNetlinkConnection.class);

    private AtomicInteger sequenceGenerator = new AtomicInteger(1);

    protected Map<Integer, NetlinkRequest>
        pendingRequests = new ConcurrentHashMap<Integer, NetlinkRequest>();

    private NetlinkChannel channel;
    private Reactor reactor;

    public AbstractNetlinkConnection(NetlinkChannel channel, Reactor reactor) {
        this.channel = channel;
        this.reactor = reactor;
    }

    protected NetlinkChannel getChannel() {
        return channel;
    }

    protected Reactor getReactor() {
        return reactor;
    }

    public class RequestBuilder<
        Cmd extends Enum<Cmd> & Netlink.ByteConstant,
        Family extends Netlink.CommandFamily<Cmd, ?>
        > {

        private Family cmdFamily;
        private Cmd cmd;
        private Flag[] flags;
        private ByteBuffer payload;
        private Callback<List<ByteBuffer>> callback;
        private long timeoutMillis;

        private RequestBuilder(Family cmdFamily, Cmd cmd) {
            this.cmdFamily = cmdFamily;
            this.cmd = cmd;
        }

        public RequestBuilder<Cmd, Family> withFlags(Flag... flags) {
            this.flags = flags;

            return this;
        }

        public RequestBuilder<Cmd, Family> withPayload(ByteBuffer payload) {
            this.payload = payload;
            return this;
        }

        public <T> RequestBuilder<Cmd, Family> withCallback(final Callback<T> callback,
                                                            final Function<List<ByteBuffer>, T> translationFunction) {
            this.callback = new Callback<List<ByteBuffer>>() {
                @Override
                public void onSuccess(List<ByteBuffer> data) {
                    callback.onSuccess(translationFunction.apply(data));
                }

                @Override
                public void onTimeout() {
                    callback.onTimeout();
                }

                @Override
                public void onError(int error, String errorMessage) {
                    callback.onError(error, errorMessage);
                }
            };

            return this;
        }

        public RequestBuilder<Cmd, Family> withTimeout(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public void send() {
            sendNetLinkMessage(cmdFamily.getFamilyId(), cmd.getValue(),
                               Flag.or(flags), cmdFamily.getVersion(),
                               payload, callback, timeoutMillis);
        }
    }

    private void sendNetLinkMessage(short cmdFamily, byte cmd, short flags,
                                    byte version, ByteBuffer payload,
                                    Callback<List<ByteBuffer>> callback,
                                    long timeoutMillis) {
        final int seq = sequenceGenerator.getAndIncrement();

        int totalSize = 20 + payload.remaining();
        ByteBuffer request = ByteBuffer.allocate(totalSize + 4);
        request.order(ByteOrder.nativeOrder());

        serializeNetlinkHeader(request, seq, cmdFamily, version, cmd, flags);

        // set the payload
        request.put(payload);

        // set the header length
        request.putInt(0, totalSize);
        request.flip();

        NetlinkRequest netlinkRequest = new NetlinkRequest();
        netlinkRequest.callback = callback;

        pendingRequests.put(seq, netlinkRequest);

        // send the request
        try {
            channel.write(request);
            log.debug("Sending message for id {}", seq);
            getReactor().schedule(
                timeoutMillis, TimeUnit.MILLISECONDS,
                new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        log.info("Timeout passed for request with id: {}", seq);
                        NetlinkRequest timedOutRequest =
                            pendingRequests.remove(seq);

                        if (timedOutRequest != null) {
                            timedOutRequest.callback.onTimeout();
                        }

                        return null;
                    }
                });

        } catch (IOException e) {
            netlinkRequest.callback.onError(-1, e.getMessage());
        }
    }

    protected <
        Cmd extends Enum<Cmd> & Netlink.ByteConstant,
        Family extends Netlink.CommandFamily<Cmd, ?>
        >
    RequestBuilder<Cmd, Family> newRequest(Family commandFamily, Cmd cmd) {
        return new RequestBuilder<Cmd, Family>(commandFamily, cmd);
    }

    public void handleEvent(SelectionKey key) throws IOException {

        log.debug("Handling event {}", key);

        // allocate buffer for the reply
        ByteBuffer reply = ByteBuffer.allocate(cLibrary.PAGE_SIZE);
        reply.order(ByteOrder.nativeOrder());

        // read the reply
        channel.read(reply);

        reply.flip();
        reply.mark();

        int finalLimit = reply.limit();
        while ( reply.remaining() >= 20 ) {
            // read the nlmsghdr and check for error
            int position = reply.position();

            int len = reply.getInt();           // length
            reply.limit(position + len);

            short type = reply.getShort();      // type
            short flags = reply.getShort();     // flags
            int seq = reply.getInt();           // sequence no.
            int pid = reply.getInt();           // pid

            NetlinkRequest request = pendingRequests.get(seq);
            if (request == null) {
                log.warn("Reply handlers for netlink request with id {} not found.",
                         seq);
                continue;
            }

            List<ByteBuffer> buffers = request.buffers;

            Netlink.MessageType messageType = Netlink.MessageType.findById(type);

            if (messageType == null) {
                log.error("Got unknown message with type: {}", type);
                return;
            }

            switch (messageType) {
                case NLMSG_NOOP:
                    // skip to the next message
                    break;

                case NLMSG_ERROR:
                    int error = reply.getInt();

                    // read header which caused the error
                    int errLen = reply.getInt();        // length
                    short errType = reply.getShort();   // type of error
                    short errFlags = reply.getShort();  // flags of the error
                    int errSeq = reply.getInt();        // sequence of the error
                    int errPid = reply.getInt();        // pid of the error

                    String errorMessage = cLibrary.lib.strerror(-error);

                    NetlinkRequest errRequest = pendingRequests.remove(seq);

                    if (errRequest != null) {
                        errRequest.callback.onError(error, errorMessage);
                    }

                    break;

                case NLMSG_DONE:
                    pendingRequests.remove(seq);
                    request.callback.onSuccess(request.buffers);
                    break;

                default:
                    // read genl header
                    byte  genlCmd = reply.get();      // command
                    byte  genlVer = reply.get();      // version
                    short reserved = reply.getShort(); // reserved

                    ByteBuffer payload = reply.slice();
                    payload.order(ByteOrder.nativeOrder());

                    if (buffers != null) {
                        request.buffers.add(payload);
                    }

                    if ((flags & Flag.NLM_F_MULTI.getValue()) == 0) {
                        pendingRequests.remove(seq);
                        request.callback.onSuccess(request.buffers);
                    }
            }

            reply.limit(finalLimit);
            reply.position(position + len);
        }
    }

    private int serializeNetlinkHeader(ByteBuffer request, int seq,
                                       short family, byte version, byte cmd,
                                       short flags) {

        int startPos = request.position();
        int pid = channel.getLocalAddress().getPid();

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

    class NetlinkRequest {
        List<ByteBuffer> buffers = new ArrayList<ByteBuffer>();
        Callback<List<ByteBuffer>> callback;
    }

    public static abstract class Callback<T> {
        public void onSuccess(T data) {
        }

        public void onTimeout() {
        }

        public void onError(int error, String errorMessage) {

        }
    }

    protected <T> Callback<T> wrapFuture(final ValueFuture<T> future) {
        return new Callback<T>() {
            @Override
            public void onSuccess(T data) {
                future.set(data);
            }

            @Override
            public void onTimeout() {
                future.cancel(true);
            }

            @Override
            public void onError(int error, String errorMessage) {
//                future.cancel(true);
                future.setException(new IOException("Error: " + errorMessage));
            }
        };
    }

    protected NetlinkMessage.Builder newMessage(int size, ByteOrder order) {
        return NetlinkMessage.newMessageBuilder(size, order);
    }

    protected NetlinkMessage.Builder newMessage(int size) {
        return NetlinkMessage.newMessageBuilder(size);
    }

    protected NetlinkMessage.Builder newMessage() {
        return NetlinkMessage.newMessageBuilder();
    }
}
