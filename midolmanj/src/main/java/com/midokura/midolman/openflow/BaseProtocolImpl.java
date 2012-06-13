/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openflow;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.openflow.protocol.OFEchoReply;
import org.openflow.protocol.OFEchoRequest;
import org.openflow.protocol.OFError;
import org.openflow.protocol.OFError.OFBadActionCode;
import org.openflow.protocol.OFError.OFBadRequestCode;
import org.openflow.protocol.OFError.OFErrorType;
import org.openflow.protocol.OFError.OFFlowModFailedCode;
import org.openflow.protocol.OFError.OFHelloFailedCode;
import org.openflow.protocol.OFError.OFPortModFailedCode;
import org.openflow.protocol.OFError.OFQueueOpFailedCode;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.openflow.protocol.factory.BasicFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.openflow.nxm.NxError;

/**
 * An implementation of the OpenFlow 1.0 protocol to abstract operations.
 *
 * This class keeps track of pending operations (request / reply pairs) and to
 * periodically initiate echo operations.
 *
 * Some settable attributes include:
 *
 * defaultOperationTimeoutMillis: The default period, in seconds, before an
 * operation times out. This is also used as the timeout for echo operations.
 * Defaults to 3000.
 *
 * echoPeriodMillis: The period, in seconds, between two echo operations.
 * Defaults to 5000.
 *
 * @author ddumitriu
 *
 */
public abstract class BaseProtocolImpl implements SelectListener {

    private final static Logger log = LoggerFactory.getLogger(BaseProtocolImpl.class);

    private SelectionKey key;

    protected BasicFactory factory;
    private OFMessageAsyncStream stream;

    protected class PendingOperation {
        SuccessHandler successHandler;
        Object cookie;
        ScheduledFuture future;

        PendingOperation(SuccessHandler successHandler, Object cookie, ScheduledFuture future) {
            this.successHandler = successHandler;
            this.cookie = cookie;
            this.future = future;
        }
    }

    protected Map<Integer, PendingOperation> pendingOperations = new ConcurrentHashMap<Integer, PendingOperation>();
    protected AtomicInteger xidSeq = new AtomicInteger();

    protected Date connectedSince;
    protected SocketChannel socketChannel;
    protected AtomicInteger transactionIdSource;
    
    protected boolean connected;

    protected long defaultOperationTimeoutMillis = 3000;
    protected long echoPeriodMillis = 5000;

    protected Random rand = new Random();

    protected Reactor reactor;

    public BaseProtocolImpl(SocketChannel sock, Reactor reactor)
            throws IOException {
        this.socketChannel = sock;
        this.reactor = reactor;

        this.connectedSince = new Date();
        this.connected = true;
        
        this.transactionIdSource = new AtomicInteger();

        factory = new BasicFactory();
        stream = new OFMessageAsyncStream(sock, factory);
    }
    
    protected void write(OFMessage msg) throws IOException {
    	if (!connected) {
    		log.warn("write: tried to write to disconnected socket");
    		return;
    	}
    	
    	try {
			stream.write(msg);
		} catch (IOException e) {
			log.warn("write", e);
			disconnectSwitch();
		}
    }

    protected int initiateOperation(SuccessHandler successHandler,
            final TimeoutHandler timeoutHandler, Long timeoutMillis, Object cookie) {
        log.debug("initiateOperation");

        int xid = transactionIdSource.getAndIncrement();
        while (pendingOperations.containsKey(xid))
            xid = transactionIdSource.getAndIncrement();

        final int nextXid = xid;

        if (timeoutMillis != null) {
            ScheduledFuture future = reactor.schedule(new Runnable() {

                @Override
                public void run() {
                    if (pendingOperations.remove(nextXid) != null) {
                        log.debug("pending operation {} timed out", nextXid);
                        timeoutHandler.onTimeout();
                    } else {
                        log.debug("pending operation {} not found", nextXid);
                    }
                }

            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        pendingOperations.put(nextXid, new PendingOperation(successHandler, cookie, null));

        return nextXid;
    }

    protected SuccessHandler terminateOperation(int xid, Object cookie) {
        PendingOperation po = pendingOperations.remove(xid);
        if (po != null) {
            if (cookie != null && !cookie.equals(po.cookie)) {
                throw new OpenFlowError("mismatched cookie on pending operation");
            }

            if (po.future != null && !po.future.isCancelled()) {
                po.future.cancel(false);
            }

            return po.successHandler;
        }

        return null;
    }

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.debug("handleEvent: " + key);

        this.key = key;

        try {
            /**
             * A key may not be valid here if it has been disconnected while it
             * was in a select operation.
             */
            if (!key.isValid()) {
                log.warn("invalid key {}", key);
                return;
            }

            if (key.isReadable()) {
                List<OFMessage> msgs = stream.read();
                if (msgs == null) {
                    // if the other end closed its end of the connection, flush
                    // any remaining written data before closing our end
                    // (otherwise the socket hangs around forever in CLOSE_WAIT
                    // state, and the associated stream and buffer objects are
                    // never freed)
                    if (!stream.needsFlush())
                        throw new EOFException();
                } else {
                    for (OFMessage m : msgs)
                        handleMessage(m);
                }
            }

            if (key.isWritable()) {
                log.debug("handleEvent {} isWritable so flushing stream ", key);
                stream.flush();
            }

            /**
             * Only register for interest in R OR W, not both, causes stream
             * deadlock after some period of time
             */
            if (stream.needsFlush()) {
                log.debug("handleEvent setting interest in OP_WRITE");
                key.interestOps(SelectionKey.OP_WRITE);
            } else {
                log.debug("handleEvent setting interest in OP_READ");
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            // if we have an exception, disconnect the switch
            log.warn("handleEvent", e);
            disconnectSwitch();
        } catch (CancelledKeyException e) {
            // can occur if we alraedy disconnected and this handler is called
            log.warn("handleEvent, must have already disconnected", e);
        }
    }

    protected abstract void onConnectionLost();

    protected void disconnectSwitch() {
    	this.connected = false;
    	
        key.cancel();
        onConnectionLost();
        try {
            socketChannel.socket().close();
        } catch (IOException e) {
            log.warn("disconnectSwitch", e);
        }
        log.info("Switch disconnected");
    }

    /**
     *
     * @param m the messages
     * @return true if the message was handled, and false otherwise
     */
    protected synchronized boolean handleMessage(OFMessage m)
            throws  IOException {
        log.debug("handleMessage: xid = " + m.getXid());

        switch (m.getType()) {
        case ECHO_REQUEST:
            log.debug("handleMessage: ECHO_REQUEST");
            OFEchoReply reply = (OFEchoReply) stream.getMessageFactory().getMessage(
                    OFType.ECHO_REPLY);
            reply.setXid(m.getXid());
            write(reply);
            return true;
        case ECHO_REPLY:
            log.debug("handleMessage: ECHO_REPLY");
            OFEchoReply r = (OFEchoReply) m;
            SuccessHandler successHandler = terminateOperation(m.getXid(), OFType.ECHO_REQUEST);
            if (successHandler != null) {
                successHandler.onSuccess(r.getPayload());
            }
            return true;
        case ERROR:
            log.debug("handleMessage: ERROR for xid: {}", m.getXid());
            OFError error = (OFError) m;
            logError(error);
            return true;
        default:
            log.debug("handleMessage: default: " + m.getType());
            return false;
        }
    }

    protected void logError(OFError error) {
        if (NxError.isNxErrorExtension(error)) {
            NxError nxErr = NxError.fromOFError(error);
            OFMessage failedReq = new OFMessage();
            failedReq.readFrom(ByteBuffer.wrap(nxErr.getFailedReq()));
            log.error("Nicira error {} caused by message {}", nxErr, failedReq);
            return;
        }
        // TODO Move this to OFJ with *much* better printing
        OFErrorType et = OFErrorType.values()[0xffff & error.getErrorType()];
        switch (et) {
        case OFPET_HELLO_FAILED:
            OFHelloFailedCode hfc = OFHelloFailedCode.values()[0xffff & error.getErrorCode()];
            log.error("Error {} {} from {}", new Object[] { et, hfc, this });
            break;
        case OFPET_BAD_REQUEST:
            OFBadRequestCode brc = OFBadRequestCode.values()[0xffff & error.getErrorCode()];
            log.error("Error {} {} from {}", new Object[] { et, brc, this });
            break;
        case OFPET_BAD_ACTION:
            OFBadActionCode bac = OFBadActionCode.values()[0xffff & error.getErrorCode()];
            log.error("Error {} {} from {}", new Object[] { et, bac, this });
            break;
        case OFPET_FLOW_MOD_FAILED:
            OFFlowModFailedCode fmfc = OFFlowModFailedCode.values()[0xffff & error.getErrorCode()];
            log.error("Error {} {} from {}", new Object[] { et, fmfc, this });
            break;
        case OFPET_PORT_MOD_FAILED:
            OFPortModFailedCode pmfc = OFPortModFailedCode.values()[0xffff & error.getErrorCode()];
            log.error("Error {} {} from {}", new Object[] { et, pmfc, this });
            break;
        case OFPET_QUEUE_OP_FAILED:
            OFQueueOpFailedCode qofc = OFQueueOpFailedCode.values()[0xffff & error.getErrorCode()];
            log.error("Error {} {} from {}", new Object[] { et, qofc, this });
            break;
        default:
            break;
        }
    }

    protected void sendEchoRequest() {
        log.debug("sendEchoRequest");

        final byte[] randPayload = new byte[4];
        rand.nextBytes(randPayload);

        OFEchoRequest m = (OFEchoRequest) factory.getMessage(OFType.ECHO_REQUEST);
        m.setPayload(randPayload);
        m.setLengthU(OFEchoRequest.MINIMUM_LENGTH + randPayload.length);
        m.setXid(initiateOperation(new SuccessHandler() {
            @Override
            public void onSuccess(Object data) {
//                if (!data.equals(randPayload)) {
                if (false) { //TODO: temporarily turn off this check, as OVS doesn't seem to echo back the payload
                    log.error("echo reply with invalid data");
                    disconnectSwitch();
                } else {
                    reactor.schedule(new Runnable() {
                        @Override
                        public void run() {
                            sendEchoRequest();
                        }
                    }, echoPeriodMillis, TimeUnit.MILLISECONDS);
                }
            }
        },

        new TimeoutHandler() {
            @Override
            public void onTimeout() {
                log.error("echo timeout");
                disconnectSwitch();
            }
        }, null, OFType.ECHO_REQUEST));
        
        try {
			write(m);
		} catch (IOException e) {
			log.warn("sendEchoRequest", e);
		}
    }

    @Override
    public String toString() {
        return "ControllerStubImpl [" + socketChannel.socket() + "]";
    }

}
