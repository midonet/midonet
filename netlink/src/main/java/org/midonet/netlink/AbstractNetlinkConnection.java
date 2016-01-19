/*
 * Copyright 2014 Midokura SARL
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Native;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.cLibrary;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.util.BatchCollector;
import org.midonet.util.Bucket;
import org.midonet.util.io.SelectorInputQueue;

/**
 * Abstract class to be derived by any netlink protocol implementation.
 */
public abstract class AbstractNetlinkConnection {

    private final Logger log;

    private static final int DEFAULT_MAX_BATCH_IO_OPS = 200;
    private static final int NETLINK_HEADER_LEN = 20;
    private static final int NETLINK_READ_BUFSIZE = 0x10000;

    protected static final long DEF_REPLY_TIMEOUT = TimeUnit.SECONDS.toMillis(1);

    private int sequenceNumber = 1;

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

    private ByteBuffer reply =
        BytesUtil.instance.allocateDirect(NETLINK_READ_BUFSIZE);

    private final BufferPool requestPool;
    private final NetlinkChannel channel;
    protected BatchCollector<Runnable> dispatcher;

    private SelectorInputQueue<NetlinkRequest> writeQueue =
            new SelectorInputQueue<>();

    private PriorityQueue<NetlinkRequest> expirationQueue;

    private Set<NetlinkRequest> ongoingTransaction = new HashSet<>();

    /* When true, this connection will interpret that the notification
     * handler is shared with other channels and thus an upper entity
     * is responsible for marking the ending notification batches. */
    private boolean usingSharedNotificationHandler = false;

    public AbstractNetlinkConnection(NetlinkChannel channel, BufferPool sendPool) {
        this.channel = channel;
        this.requestPool = sendPool;
        expirationQueue = new PriorityQueue<>(512, NetlinkRequest.comparator);

        log = LoggerFactory.getLogger("org.midonet.netlink.netlink-conn-" + pid());

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

    public void setUsingSharedNotificationHandler(boolean v) {
        usingSharedNotificationHandler = true;
    }

    public boolean isUsingSharedNotificationHandler() {
        return usingSharedNotificationHandler;
    }

    public NetlinkChannel getChannel() {
        return channel;
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

    public static Reader<Boolean> alwaysTrueReader =
        new Reader<Boolean>() {
            public Boolean deserializeFrom(ByteBuffer source) {
                return Boolean.TRUE;
            }
        };

    private void expireOldRequests() {
        long now = System.nanoTime();
        NetlinkRequest req;

        while ((req = expirationQueue.peek()) != null &&
                req.expirationTimeNanos <= now) {

            expirationQueue.poll();
            if (pendingRequests.remove(req.seq) == null) {
                // request expired but it has already been
                // handled on the reading side -> ignoring.
                continue;
            }

            log.debug("Signalling timeout to the callback: {}", req.seq);
            requestPool.release(req.releaseRequestPayload());
            dispatcher.submit(req.expired());
        }
    }

    public void getFamilyId(String familyName, Callback<Short> callback) {
        getFamilyId(familyName, callback, DEF_REPLY_TIMEOUT);
    }

    public void getFamilyId(String familyName,
                            Callback<Short> callback, long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        GenlProtocol.familyNameRequest(familyName, NLFlag.REQUEST, pid(),
                                       CtrlFamily.Context.GetFamily, buf);
        sendNetlinkMessage(
            buf,
            callback,
            CtrlFamily.familyIdDeserializer,
            timeoutMillis);
    }

    public void getMulticastGroup(String familyName,
                                  String groupName,
                                  Callback<Integer> callback) {
        getMulticastGroup(familyName, groupName, callback, DEF_REPLY_TIMEOUT);
    }

    public void getMulticastGroup(String familyName,
                                  String groupName,
                                  Callback<Integer> callback,
                                  long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        GenlProtocol.familyNameRequest(familyName, NLFlag.REQUEST, pid(),
                                       CtrlFamily.Context.GetFamily, buf);
        sendNetlinkMessage(
            buf,
            callback,
            CtrlFamily.mcastGrpDeserializer(groupName),
            timeoutMillis);
    }

    /** Finalizes a ByteBuffer containing a msg payload and puts it in the
     *  internal queue of messages to be writen to the nl socket by the
     *  writing thread. It is assumed that this message will be answered by
     *  a unique reply message. */
    protected <T> void sendNetlinkMessage(ByteBuffer payload,
                                          Callback<T> callback,
                                          Reader<T> reader,
                                          long timeoutMillis) {
        enqueueRequest(NetlinkRequest.makeSingle(callback, reader,
                                                 payload, timeoutMillis));
    }

    /** Same as sendNetlinkMessage(), but assumes that the message to be sent
     *  will be answered by multiple answers (enumerate requests). The reply
     *  handler will take care of assembling a set of answers using the given
     *  deserialisation function, and will pass this set to the given callback
     *  once a DONE netlink message is read for the handler seq number. */
    protected <T> void sendMultiAnswerNetlinkMessage(ByteBuffer payload,
                                                     Callback<Set<T>> callback,
                                                     Reader<T> reader,
                                                     long timeoutMillis) {
        enqueueRequest(NetlinkRequest.makeMulti(callback, reader,
                                                payload, timeoutMillis));
    }

    private void enqueueRequest(NetlinkRequest req) {
        if (bypassSendQueue) {
            // If this stops being used only for testing, beware
            // of the un-synchronized write to sequenceNumber.
            processWriteToChannel(req);
        } else if (!writeQueue.offer(req)) {
            abortRequestQueueIsFull(req);
        }
    }

    protected int pid() {
        return getChannel().getLocalAddress().getPid();
    }

    private int nextSequenceNumber() {
        int seq = sequenceNumber++;
        return seq == 0 ? nextSequenceNumber() : seq;
    }

    private void abortRequestQueueIsFull(NetlinkRequest req) {
        requestPool.release(req.releaseRequestPayload());
        String msg = "Too many pending netlink requests";
        if (req.hasCallback()) {
            /* Run the callback directly, because this runs out of the client's
             * thread, not the channel's: it's the client that failed to
             * put a request in the queue. */
            req.failed(new NetlinkException(
                NetlinkException.ERROR_SENDING_REQUEST, msg, 0)).run();
        } else {
            log.info(msg);
        }
    }

    /*
     * Use blocking-write to send a batch of netlink requests and do
     * blocking reads until all their ACKs have been received back.
     *
     * Will throw if the channel is in non-blocking mode.
     *
     * NOT thread-safe.
     */
    public void doTransactionBatch() throws InterruptedException {
        if (! channel.isBlocking()) {
            throw new UnsupportedOperationException(
                "Blocking transactions on non-blocking channels are unsupported");
        }

        NetlinkRequest r;

        /* Wait five seconds for the 1st request to arrive, don't wait at all
         * after that. */
        for (r = writeQueue.poll(5000, TimeUnit.MILLISECONDS);
             r != null && ongoingTransaction.size() < maxBatchIoOps;
             r = writeQueue.poll()) {

            if (processWriteToChannel(r) <= 0)
                break;

            if (r.hasCallback())
                ongoingTransaction.add(r);
        }

        try {
            while (!ongoingTransaction.isEmpty()) {
                if (processReadFromChannel(Bucket.BOTTOMLESS) <= 0)
                    break;
            }
        } catch (IOException e) {
            log.error("Netlink channel read() error", e);
        }

        expireOldRequests();
        dispatcher.endBatch();
        ongoingTransaction.clear();
    }

    public void handleWriteEvent() throws IOException {
        for (int i = 0; i < maxBatchIoOps; i++) {
            final NetlinkRequest request = writeQueue.poll();
            if (request == null)
                break;
            final int ret = processWriteToChannel(request);
            if (ret <= 0) {
                if (ret < 0) {
                    log.warn("NETLINK write() error: {}",
                            cLibrary.lib.strerror(Native.getLastError()));
                }
                break;
            }
        }
        expireOldRequests();
    }

    private int processWriteToChannel(final NetlinkRequest request) {
        if (request == null)
            return 0;

        ByteBuffer outBuf = request.releaseRequestPayload();
        if (outBuf == null)
            return 0;

        int seq = writeSeqToNetlinkRequest(request, outBuf);
        if (request.hasCallback()) {
            pendingRequests.put(seq, request);
        }

        log.trace("Sending message for id {}", seq);

        int bytes = 0;
        try {
            bytes = channel.write(outBuf);
            if (request.hasCallback())
                expirationQueue.add(request);
        } catch (IOException e) {
            log.warn("NETLINK write() exception: {}", e);
            if (request.hasCallback()) {
                pendingRequests.remove(request.seq);
                dispatcher.submit(request.failed(new NetlinkException(
                                  NetlinkException.ERROR_SENDING_REQUEST, e, 0)));
            }
        } finally {
            requestPool.release(outBuf);
        }
        return bytes;
    }

    public void handleReadEvent(final Bucket bucket) throws IOException {
        try {
            bucket.prepare();
            for (int i = 0; i < maxBatchIoOps; i++) {
                final int ret = processReadFromChannel(bucket);
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
            bucket.done();
            if (!usingSharedNotificationHandler)
                endBatch();
            dispatcher.endBatch();
        }
    }

    protected void endBatch() {}

    private synchronized int processReadFromChannel(final Bucket bucket)
            throws IOException {

        reply.clear();
        int nbytes = channel.read(reply);

        reply.flip(); // sets the effective final limit for any number of msgs
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

            int nextPosition = position + len;
            reply.limit(nextPosition);          // "slice" the buffer to avoid
                                                // reads on the next msg.
            switch (type) {
                case NLMessageType.NOOP:
                    // skip to the next message
                    break;

                case NLMessageType.ERROR:
                    int error = reply.getInt();

                    // read header which caused the error
                    int errLen = reply.getInt();        // length
                    short errType = reply.getShort();   // type of error
                    short errFlags = reply.getShort();  // flags of the error
                    int errSeq = reply.getInt();        // sequence of the error
                    int errPid = reply.getInt();        // pid of the error

                    if (seq == 0) break; // should not happen

                    // An ACK is a NLMSG_ERROR with 0 as error code
                    if (error == 0) {
                        processSuccessfulRequest(removeRequest(seq));
                    } else {
                        processFailedRequest(seq, error);
                    }
                    break;

                case NLMessageType.DONE:
                    if (seq == 0) break; // should not happen
                    processSuccessfulRequest(removeRequest(seq));
                    break;

                default:
                    // read genl header
                    byte cmd = reply.get();      // command
                    byte ver = reply.get();      // version
                    reply.getShort();            // reserved

                    if (seq == 0) {
                        // if the seq number is zero we are handling a PacketIn.
                        if (bucket.consumeToken()) {
                            try {
                                handleNotification(type, cmd, seq, pid, reply);
                            } catch (Throwable e) {
                                bucket.giveBack();
                                throw e;
                            }
                        } else {
                            log.debug("Failed to get token; dropping packet");
                        }
                    } else  {
                        // otherwise we are processing an answer to a request.
                        processRequestAnswer(seq, flags, reply);
                    }
            }

            reply.limit(finalLimit);
            reply.position(nextPosition);
        }
        return nbytes;
    }

    private void processSuccessfulRequest(NetlinkRequest request) {
        if (request != null) {
            ongoingTransaction.remove(request);
            dispatcher.submit(request.successful());
        }
    }

    private void processRequestAnswer(int seq, short flag, ByteBuffer payload) {
        NetlinkRequest request = removeRequest(seq);
        if (request != null) {
            if (NLFlag.isMultiFlagSet(flag)) {
                // if the answer is made of multiple msgs, we clone the payload and
                // and we will process the request callback when the multi-msg ends.
                request.addAnswerFragment(payload);
                // We don't bother with placing the request back in the expiration
                // queue because we can't access it from this thread, and because
                // we know the reply is in flight.
                pendingRequests.put(seq, request);
            } else {
                // otherwise we process the callback directly with the payload.
                request.addAnswerFragment(payload);
                processSuccessfulRequest(request);
            }
        }
    }

    private void processFailedRequest(int seq, int error) {
        NetlinkRequest request = removeRequest(seq);
        if (request != null) {
            String errorMessage = cLibrary.lib.strerror(-error);
            NetlinkException err = new NetlinkException(-error, errorMessage, 0);
            dispatcher.submit(request.failed(err));
        } else {
            log.error(cLibrary.lib.strerror(-error));
        }
    }

    private NetlinkRequest removeRequest(int seq) {
        NetlinkRequest request = pendingRequests.remove(seq);
        if (request == null) {
            log.debug("Reply handler for netlink request with id {} " +
                      "not found.", seq);
        }
        return request;
    }

    protected abstract void handleNotification(short type, byte cmd, int seq,
                                               int pid, ByteBuffer buffer);

    private int writeSeqToNetlinkRequest(NetlinkRequest request, ByteBuffer out) {
        int seq = nextSequenceNumber();
        request.seq = seq;
        NetlinkMessage.writeSequenceNumber(out, seq);
        return seq;
    }

    protected ByteBuffer getBuffer() {
        ByteBuffer buf = requestPool.take();
        buf.clear();
        return buf;
    }
}
