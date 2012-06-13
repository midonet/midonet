package com.midokura.midolman.openflow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.factory.OFMessageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous OpenFlow message marshalling and unmarshalling stream wrapped
 * around an NIO SocketChannel
 * 
 * @author Rob Sherwood (rob.sherwood@stanford.edu)
 * @author David Erickson (daviderickson@cs.stanford.edu)
 * 
 */
public class OFMessageAsyncStream {
	private final static Logger log = LoggerFactory.getLogger(OFMessageAsyncStream.class);
	
    static public int defaultBufferSize = 65536;

    protected ByteBuffer inBuf, outBuf;
    protected OFMessageFactory messageFactory;
    protected SocketChannel sock;

    public OFMessageAsyncStream(SocketChannel sock,
            OFMessageFactory messageFactory) throws IOException {
        inBuf = ByteBuffer.allocateDirect(OFMessageAsyncStream.defaultBufferSize);
        outBuf = ByteBuffer.allocateDirect(OFMessageAsyncStream.defaultBufferSize);
        this.sock = sock;
        this.messageFactory = messageFactory;
        this.sock.configureBlocking(false);
    }

    public List<OFMessage> read() throws IOException {
        return this.read(0);
    }

    public List<OFMessage> read(int limit) throws IOException {
        List<OFMessage> l;
        int read = sock.read(inBuf);
        if (read == -1)
            return null;
        inBuf.flip();
        l = messageFactory.parseMessages(inBuf, limit);
        if (inBuf.hasRemaining())
            inBuf.compact();
        else
            inBuf.clear();
        return l;
    }

    protected void appendMessageToOutBuf(OFMessage m) throws IOException {
        int msglen = m.getLengthU();
        if (outBuf.remaining() < msglen) {
        	int newSize = outBuf.capacity() * 2;
        	log.warn("appendMessageToOutBuf: increasing outBuf size to {}", newSize);
            ByteBuffer newOutBuf = ByteBuffer.allocateDirect(newSize);
            outBuf.flip();
            newOutBuf.put(outBuf);
            outBuf = newOutBuf;
        }
        m.writeTo(outBuf);
    }

    /**
     * Sends or Buffers a single outgoing openflow message
     */
    public void write(OFMessage m) throws IOException {
    	boolean alreadyNeededFlush = needsFlush();
    	
        appendMessageToOutBuf(m);
        
        // if we already needed flush, let the eventloop do it later.
        if (!alreadyNeededFlush) {
        	flush();
        }
    }

    /**
     * Flush buffered outgoing data. Keep flushing until needsFlush() returns
     * false. Each flush() corresponds to a SocketChannel.write(), so this is
     * designed for one flush() per select() event
     */
    public void flush() throws IOException {
        outBuf.flip(); // swap pointers; lim = pos; pos = 0;
        sock.write(outBuf); // write data starting at pos up to lim
        outBuf.compact();
    }

    /**
     * Is there outgoing buffered data that needs to be flush()'d?
     */
    public boolean needsFlush() {
        return outBuf.position() > 0;
    }

    /**
     * @return the messageFactory
     */
    public OFMessageFactory getMessageFactory() {
        return messageFactory;
    }

    /**
     * @param messageFactory
     *            the messageFactory to set
     */
    public void setMessageFactory(OFMessageFactory messageFactory) {
        this.messageFactory = messageFactory;
    }

}
