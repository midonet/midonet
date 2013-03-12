/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

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
import javax.annotation.Nonnull;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ValueFuture;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.messages.Builder;
import org.midonet.util.eventloop.Reactor;
import static org.midonet.netlink.Netlink.Flag;

/**
 * Abstract class to be derived by any netlink protocol implementation.
 */
public abstract class AbstractNetlinkConnection {

    private static final Logger log = LoggerFactory
        .getLogger(AbstractNetlinkConnection.class);

    private static final int DEFAULT_MAX_BATCH_IO_OPS = 20;

    private AtomicInteger sequenceGenerator = new AtomicInteger(1);

    protected Map<Integer, NetlinkRequest>
        pendingRequests = new ConcurrentHashMap<Integer, NetlinkRequest>();

    // Again, mostly for testing purposes. Tweaks the number of IO operations
    // per handle[Write|Read]Event() invokation. Netlink protocol tests will
    // assume one read per call.
    private int maxBatchIoOps = DEFAULT_MAX_BATCH_IO_OPS;

    private NetlinkChannel channel;
    private Reactor reactor;

    public AbstractNetlinkConnection(NetlinkChannel channel, Reactor reactor) {
        this.channel = channel;
        this.reactor = reactor;
    }

    public NetlinkChannel getChannel() {
        return channel;
    }

    protected Reactor getReactor() {
        return reactor;
    }

    public void setMaxBatchIoOps(int max) {
        this.maxBatchIoOps = max;
    }

    public int getMaxBatchIoOps() {
        return this.maxBatchIoOps;
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
                public void onError(NetlinkException e) {
                    callback.onError(e);
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
                                    final long timeoutMillis) {
        final int seq = sequenceGenerator.getAndIncrement();

        int totalSize = 20 + payload.remaining();
        final ByteBuffer request = ByteBuffer.allocate(totalSize + 4);
        request.order(ByteOrder.nativeOrder());

        serializeNetlinkHeader(request, seq, cmdFamily, version, cmd, flags);

        // set the payload
        request.put(payload);

        // set the header length
        request.putInt(0, totalSize);
        request.flip();

        final NetlinkRequest netlinkRequest = new NetlinkRequest();
        netlinkRequest.family = cmdFamily;
        netlinkRequest.cmd = cmd;
        netlinkRequest.callback = callback;

        getReactor().submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                pendingRequests.put(seq, netlinkRequest);
                // send the request
                try {
                    log.debug("Sending message for id {}", seq);
                    netlinkRequest.timeoutHandler = new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            log.trace("Timeout fired for {}", seq);
                            NetlinkRequest timedOutRequest =
                                pendingRequests.remove(seq);

                            if (timedOutRequest != null) {
                                log.debug("Signalling timeout to the callback: {}", seq);
                                timedOutRequest.callback.onTimeout();
                            }

                            return null;
                        }
                    };
                    getReactor().schedule(netlinkRequest.timeoutHandler,
                                          timeoutMillis, TimeUnit.MILLISECONDS);
                    channel.write(request);
                } catch (IOException e) {
                    netlinkRequest.callback
                                  .onError(new NetlinkException(
                                      NetlinkException.ERROR_SENDING_REQUEST,
                                      e));
                }

                return null;
            }
        });
    }

    protected <
        Cmd extends Enum<Cmd> & Netlink.ByteConstant,
        Family extends Netlink.CommandFamily<Cmd, ?>
        >
    RequestBuilder<Cmd, Family> newRequest(Family commandFamily, Cmd cmd) {
        return new RequestBuilder<Cmd, Family>(commandFamily, cmd);
    }

    public void handleEvent(final SelectionKey key) throws IOException {
        try {
            for (int i = 0; i < maxBatchIoOps; i++) {
                final int ret = processReadFromChannel(key);
                if (ret <= 0) {
                    if (ret < 0) {
                        log.info("NETLINK read() error: {}",
                            cLibrary.lib.strerror(Native.getLastError()));
                    }
                    break;
                }
            }
        } catch (IOException e) {
            log.error("NETLINK read() exception: {}", e);
        }
    }

    private synchronized int processReadFromChannel(SelectionKey key) throws IOException {

        if (key != null) {
            log.debug("Handling event with key: {valid:{}, ops:{}}",
                      key.isValid(), key.readyOps());
        }

        // allocate buffer for the reply
        ByteBuffer reply = ByteBuffer.allocate(cLibrary.PAGE_SIZE);
        reply.order(ByteOrder.LITTLE_ENDIAN);

        // channel.read() returns # of bytes read.
        final int nbytes = channel.read(reply);
        if (nbytes == 0)
            return nbytes;

        reply.flip();
        reply.mark();

        int finalLimit = reply.limit();
        while (reply.remaining() >= 20) {
            // read the nlmsghdr and check for error
            int position = reply.position();

            int len = reply.getInt();           // length
            short type = reply.getShort();      // type
            short flags = reply.getShort();     // flags
            int seq = reply.getInt();           // sequence no.
            int pid = reply.getInt();           // pid

            int nextPosition = finalLimit;
            if (Flag.isSet(flags, Flag.NLM_F_MULTI)) {
                reply.limit(position + len);
                nextPosition = position + len;
            }

            final NetlinkRequest request = pendingRequests.get(seq);
            if (request == null && seq != 0) {
                log.warn("Reply handlers for netlink request with id {} " +
                             "not found. {}", seq, pendingRequests);
            }

            List<ByteBuffer> buffers =
                request == null
                    ? new ArrayList<ByteBuffer>()
                    : request.buffers;

            Netlink.MessageType messageType =
                Netlink.MessageType.findById(type);

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
                        if (error == 0) {
                            errRequest.callback.onSuccess(errRequest.buffers);
                        } else {
                            errRequest.callback.onError(
                                new NetlinkException(-error, errorMessage));
                        }
                    }
                    break;

                case NLMSG_DONE:
                    pendingRequests.remove(seq);
                    if (request != null) {
                        request.callback.onSuccess(request.buffers);
                    }
                    break;

                default:
                    // read genl header
                    byte cmd = reply.get();      // command
                    byte ver = reply.get();      // version
                    reply.getShort();            // reserved

                    ByteBuffer payload = reply.slice();
                    payload.order(ByteOrder.nativeOrder());

                    if (buffers != null) {
                        buffers.add(payload);
                    }

                    if (!Flag.isSet(flags, Flag.NLM_F_MULTI)) {
                        pendingRequests.remove(seq);
                        if (request != null) {
                            request.callback.onSuccess(request.buffers);
                        }

                        if (seq == 0) {
                            handleNotification(type, cmd, seq, pid, buffers);
                        }
                    }
            }

            reply.limit(finalLimit);
            reply.position(nextPosition);
        }
        return nbytes;
    }

    protected abstract void handleNotification(short type, byte cmd,
                                               int seq, int pid,
                                               List<ByteBuffer> buffers);

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
        short family;
        byte cmd;
        List<ByteBuffer> buffers = new ArrayList<ByteBuffer>();
        Callback<List<ByteBuffer>> callback;
        Callable<?> timeoutHandler;

        @Override
        public String toString() {
            return "NetlinkRequest{" +
                "family=" + family +
                ", cmd=" + cmd +
                '}';
        }
    }

    @Nonnull
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
            public void onError(NetlinkException e) {
                future.setException(e);
            }
        };
    }

    protected Builder newMessage(int size, ByteOrder order) {
        return NetlinkMessage.newMessageBuilder(size, order);
    }

    protected Builder newMessage(int size) {
        return NetlinkMessage.newMessageBuilder(size);
    }

    protected Builder newMessage() {
        return NetlinkMessage.newMessageBuilder();
    }
}
