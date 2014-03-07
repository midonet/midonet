/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
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
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ValueFuture;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.messages.Builder;
import org.midonet.util.BatchCollector;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.io.SelectorInputQueue;
import org.midonet.util.throttling.ThrottlingGuard;
import org.midonet.util.throttling.ThrottlingGuardFactory;

import static org.midonet.netlink.Netlink.Flag;

/**
 * Abstract class to be derived by any netlink protocol implementation.
 */
public abstract class AbstractNetlinkConnection {

    private static final Logger log = LoggerFactory
        .getLogger(AbstractNetlinkConnection.class);

    private static final int DEFAULT_MAX_BATCH_IO_OPS = 200;
    private static final int NETLINK_HEADER_LEN = 20;
    private static final int NETLINK_READ_BUFSIZE = 0x10000;

    private AtomicInteger sequenceGenerator = new AtomicInteger(1);

    protected ThrottlingGuard pendingWritesThrottler;
    protected ThrottlingGuard upcallThrottler;

    private Map<Integer, NetlinkRequest>
        pendingRequests = new ConcurrentHashMap<>();

    // Mostly for testing purposes, this flag tells the netlink connection that
    // writes should be done on the channel directly, instead of waiting
    // for a selector to invoke handleWriteEvent()
    private boolean bypassSendQueue = false;

    // Again, mostly for testing purposes. Tweaks the number of IO operations
    // per handle[Write|Read]Event() invokation. Netlink protocol tests will
    // assume one read per call.
    private int maxBatchIoOps = DEFAULT_MAX_BATCH_IO_OPS;

    private ByteBuffer reply = ByteBuffer.allocateDirect(NETLINK_READ_BUFSIZE);

    private BufferPool requestPool;

    private NetlinkChannel channel;
    private Reactor reactor;
    protected BatchCollector<Runnable> dispatcher;

    private SelectorInputQueue<NetlinkRequest> writeQueue =
            new SelectorInputQueue<NetlinkRequest>();

    public AbstractNetlinkConnection(NetlinkChannel channel, Reactor reactor,
            ThrottlingGuardFactory pendingWritesThrottlerFactory,
            ThrottlingGuard upcallThrottler,
            BufferPool sendPool) {
        this.channel = channel;
        this.reactor = reactor;
        this.pendingWritesThrottler = pendingWritesThrottlerFactory.buildForCollection(
                "NetlinkConnection", pendingRequests.keySet());
        this.upcallThrottler = upcallThrottler;
        this.requestPool = sendPool;

        // default implementation runs callbacks out of the calling thread one by one
        this.dispatcher = new BatchCollector<Runnable>() {
            @Override
            public void submit(Runnable r) {
                r.run();
            }

            @Override
            public void endBatch() {
            }
        };
    }

    public NetlinkChannel getChannel() {
        return channel;
    }

    protected Reactor getReactor() {
        return reactor;
    }

    public void setCallbackDispatcher(BatchCollector<Runnable> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void bypassSendQueue(final boolean bypass) {
        this.bypassSendQueue = bypass;
    }

    public void setMaxBatchIoOps(int max) {
        this.maxBatchIoOps = max;
    }

    public int getMaxBatchIoOps() {
        return this.maxBatchIoOps;
    }

    public SelectorInputQueue<NetlinkRequest> getSendQueue() {
        return writeQueue;
    }

    public static Function<List<ByteBuffer>, Boolean> alwaysTrueTranslator =
        new Function<List<ByteBuffer>, Boolean>() {
            @Override
            public Boolean apply(List<ByteBuffer> input) {
                return Boolean.TRUE;
            }
        };

    protected <T> void sendNetlinkMessage(NetlinkRequestContext ctx,
                                          short flags,
                                          ByteBuffer payload,
                                          Callback<T> callback,
                                          Function<List<ByteBuffer>, T> translator,
                                          final long timeoutMillis) {
        final short cmdFamily = ctx.commandFamily();
        final byte cmd = ctx.command();
        final byte version = ctx.version();

        final int seq = sequenceGenerator.getAndIncrement();

        final int totalSize = payload.limit();

        final ByteBuffer headerBuf = payload.duplicate();
        headerBuf.rewind();
        headerBuf.order(ByteOrder.nativeOrder());
        serializeNetlinkHeader(headerBuf, seq, cmdFamily, version, cmd, flags);

        // set the header length
        headerBuf.putInt(0, totalSize);

        final NetlinkRequest<T> netlinkRequest =
            new NetlinkRequest<>(cmdFamily, cmd, callback, translator, payload);

        // send the request

        if (callback != null) {
            netlinkRequest.timeoutHandler = new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    NetlinkRequest<T> timedOutRequest = pendingRequests.remove(seq);

                    if (timedOutRequest != null) {
                        log.debug("Signalling timeout to the callback: {}", seq);
                        ByteBuffer timedOutBuf = timedOutRequest.outBuffer.getAndSet(null);
                        if (timedOutBuf != null)
                            requestPool.release(timedOutBuf);
                        pendingWritesThrottler.tokenOut();
                        timedOutRequest.expired().run();
                    }

                    return null;
                }
            };

            pendingRequests.put(seq, netlinkRequest);
        }

        log.trace("Sending message for id {}", seq);
        if (writeQueue.offer(netlinkRequest)) {
            if (bypassSendQueue)
                processWriteToChannel();
            pendingWritesThrottler.tokenIn();

            if (callback != null) {
                getReactor().schedule(netlinkRequest.timeoutHandler,
                                      timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } else {
            requestPool.release(payload);
            if (callback != null) {
                pendingRequests.remove(seq);
                netlinkRequest.failed(new NetlinkException(
                        NetlinkException.ERROR_SENDING_REQUEST,
                        "Too many pending netlink requests")).run();
            } else {
                log.info("Too many pending netlink requests");
            }
        }
    }

    public void handleWriteEvent(final SelectionKey key) throws IOException {
        for (int i = 0; i < maxBatchIoOps; i++) {
            final int ret = processWriteToChannel();
            if (ret <= 0) {
                if (ret < 0) {
                    log.warn("NETLINK write() error: {}",
                            cLibrary.lib.strerror(Native.getLastError()));
                }
                break;
            }
        }
    }

    private int processWriteToChannel() {
        final NetlinkRequest<?> request = writeQueue.poll();
        if (request == null)
            return 0;
        ByteBuffer outBuf = request.outBuffer.getAndSet(null);
        if (outBuf == null)
            return 0;

        int bytes = 0;
        try {
            bytes = channel.write(outBuf);
        } catch (IOException e) {
            log.warn("NETLINK write() exception: {}", e);
            request.failed(new NetlinkException(
                        NetlinkException.ERROR_SENDING_REQUEST, e))
                    .run();
        } finally {
            requestPool.release(outBuf);
        }
        return bytes;
    }

    public void handleReadEvent(final SelectionKey key) throws IOException {
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
        } finally {
            endBatch();
            dispatcher.endBatch();
        }
    }

    protected void endBatch() {}

    private synchronized int processReadFromChannel(SelectionKey key) throws IOException {

        if (key != null) {
            log.trace("Handling event with key: {valid:{}, ops:{}}",
                      key.isValid(), key.readyOps());
        }

        reply.clear();
        reply.order(ByteOrder.LITTLE_ENDIAN);

        // channel.read() returns # of bytes read.
        final int nbytes = channel.read(reply);
        if (nbytes == 0)
            return nbytes;

        reply.flip();
        reply.mark();

        int finalLimit = reply.limit();
        while (reply.remaining() >= NETLINK_HEADER_LEN) {
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

            final NetlinkRequest<?> request = (seq != 0) ?
                                            pendingRequests.get(seq) :
                                            null;
            if (request == null && seq != 0) {
                log.warn("Reply handler for netlink request with id {} " +
                         "not found.", seq);
            }

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

                    if (request != null) {
                        pendingRequests.remove(seq);
                        pendingWritesThrottler.tokenOut();
                        // An ACK is a NLMSG_ERROR with 0 as error code
                        if (error == 0) {
                            dispatcher.submit(request.successful(request.inBuffers));
                        } else {
                            String errorMessage = cLibrary.lib.strerror(-error);
                            dispatcher.submit(request.failed(
                                    new NetlinkException(-error, errorMessage)));
                        }
                    }
                    break;

                case NLMSG_DONE:
                    if (request != null) {
                        pendingRequests.remove(seq);
                        pendingWritesThrottler.tokenOut();
                        dispatcher.submit(request.successful(request.inBuffers));
                    }
                    break;

                default:
                    // read genl header
                    byte cmd = reply.get();      // command
                    byte ver = reply.get();      // version
                    reply.getShort();            // reserved

                    ByteBuffer payload = reply.slice();
                    payload.order(ByteOrder.nativeOrder());

                    List<ByteBuffer> buffers = (request == null)
                                                ? new ArrayList<ByteBuffer>(1)
                                                : request.inBuffers;

                    if (buffers != null) {
                        if (Flag.isSet(flags, Flag.NLM_F_MULTI))
                            buffers.add(cloneBuffer(payload));
                        else
                            buffers.add(payload);
                    }

                    if (!Flag.isSet(flags, Flag.NLM_F_MULTI)) {
                        if (request != null) {
                            pendingRequests.remove(seq);
                            pendingWritesThrottler.tokenOut();
                            dispatcher.submit(request.successful(request.inBuffers));
                        }

                        if (seq == 0 && pendingWritesThrottler.allowed()) {
                            if (upcallThrottler.tokenInIfAllowed())
                                handleNotification(type, cmd, seq, pid, buffers);
                        }
                    }
            }

            reply.limit(finalLimit);
            reply.position(nextPosition);
        }
        return nbytes;
    }

    private ByteBuffer cloneBuffer(ByteBuffer from) {
        int origPos = from.position();
        ByteBuffer to = ByteBuffer.allocate(from.remaining());
        to.order(from.order());
        to.put(from);
        to.flip();
        from.position(origPos);
        return to;
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

    protected class DelayedCallbackExecutor<T> implements Runnable {
        private boolean succeeded = false;
        private boolean expired = false;
        private boolean hasRun = false;
        private Callback<T> callback;
        private T result = null;
        private NetlinkException error = null;

        public DelayedCallbackExecutor(Callback<T> cb) {
            this.callback = cb;
        }

        private boolean initialized() {
            return succeeded || expired || (error != null);
        }

        private void ensureNotInitialized() {
            if (initialized()) {
                throw new IllegalStateException(
                        "Tried to provide result for the same callback twice");
            }
        }

        private void ensureInitialized() {
            if (!initialized()) {
                throw new IllegalStateException(
                        "No result was given for this callback");
            }
        }

        public DelayedCallbackExecutor<T> successful(T result) {
            ensureNotInitialized();
            this.succeeded = true;
            this.result = result;
            this.expired = false;
            return this;
        }

        public DelayedCallbackExecutor<T> failed(NetlinkException e) {
            ensureNotInitialized();
            this.succeeded = false;
            this.error = e;
            this.expired = false;
            return this;
        }

        public DelayedCallbackExecutor<T> expired() {
            ensureNotInitialized();
            this.succeeded = false;
            this.error = null;
            this.expired = true;
            return this;
        }

        @Override
        public void run() {
            if (hasRun)
                throw new IllegalStateException("Tried to run callback twice");

            ensureInitialized();
            this.hasRun = true;
            if (this.succeeded)
                callback.onSuccess(this.result);
            else if (this.error != null)
                callback.onError(this.error);
            else if (this.expired)
                callback.onTimeout();

        }
    }

    class NetlinkRequest<T> {
        short family;
        byte cmd;
        List<ByteBuffer> inBuffers = new ArrayList<>();
        AtomicReference<ByteBuffer> outBuffer = new AtomicReference<>();
        private final Callback<T> userCallback;
        private final Function<List<ByteBuffer>, T> translationFunction;
        Callable<?> timeoutHandler;

        public NetlinkRequest(short family, byte cmd,
                              Callback<T> callback,
                              Function<List<ByteBuffer>, T> translationFunc,
                              ByteBuffer data) {
            this.family = family;
            this.cmd = cmd;
            this.userCallback = callback;
            this.translationFunction = translationFunc;
            this.outBuffer.set(data);
        }

        public Runnable successful(List<ByteBuffer> data) {
            return new DelayedCallbackExecutor<T>(userCallback)
                    .successful(translationFunction.apply(data));
        }

        public Runnable failed(NetlinkException e) {
            return new DelayedCallbackExecutor<T>(userCallback).failed(e);
        }

        public Runnable expired() {
            return new DelayedCallbackExecutor<T>(userCallback).expired();
        }

        @Override
        public String toString() {
            return "NetlinkRequest{" +
                "family=" + family +
                ", cmd=" + cmd +
                '}';
        }

        public AtomicReference<ByteBuffer> getOutBuffer() {
            return outBuffer;
        }
    }

    @Nonnull
    protected static <T> Callback<T> wrapFuture(final ValueFuture<T> future) {
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

    protected Builder newMessage() {
        ByteBuffer buf = requestPool.take();
        buf.order(ByteOrder.nativeOrder());
        buf.clear();
        buf.position(NETLINK_HEADER_LEN);
        return NetlinkMessage.newMessageBuilder(buf);
    }
}
