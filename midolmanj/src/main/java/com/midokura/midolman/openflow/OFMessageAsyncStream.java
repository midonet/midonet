/*
Copyright (c) 2008 The Board of Trustees of The Leland Stanford Junior
University

We are making the OpenFlow specification and associated documentation
(Software) available for public use and benefit with the expectation that
others will use, modify and enhance the Software and contribute those
enhancements back to the community. However, since we would like to make the
Software available for broadest use, with as few restrictions as possible
permission is hereby granted, free of charge, to any person obtaining a copy of
this Software to deal in the Software under the copyrights without restriction,
including without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The name and trademarks of copyright holder(s) may NOT be used in advertising
or publicity pertaining to the Software or any derivatives without specific,
written prior permission.
*/
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
