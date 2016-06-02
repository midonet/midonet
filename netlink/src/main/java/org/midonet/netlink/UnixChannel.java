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

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.SelectorProvider;
import javax.annotation.Nullable;

import com.sun.jna.Native;
import sun.nio.ch.IOStatus;
import sun.nio.ch.NativeThread;
import sun.nio.ch.SelectionKeyImpl;
import sun.nio.ch.Net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.hacks.IOUtil;
import org.midonet.netlink.hacks.NativeDispatcher;
import org.midonet.util.cLibrary;

/**
 * Abstracts a netlink channel. The implementation will make a native netlink
 * socket connection to the local machine.
 */
public abstract class UnixChannel<Address> extends AbstractSelectableChannel
                                           implements ByteChannel,
                                                      InterruptibleChannel,
                                                      GatheringByteChannel,
                                                      ScatteringByteChannel {

    private static final Logger log =
        LoggerFactory.getLogger(UnixChannel.class);

    // Used to make native read and write calls
    protected static NativeDispatcher nd = new NativeDispatcher();

    protected static final int ST_UNINITIALIZED = -1;
    protected static final int ST_UNCONNECTED = 0;
    protected static final int ST_CONNECTED = 1;
    protected static final int ST_KILLED = 2;
    protected static final int ST_LISTENING = 3;

    // fd value needed for dev/poll. This value will remain valid
    // even after the value in the file descriptor object has been set to -1
    protected int fdVal;
    // Our file descriptor
    protected FileDescriptor fd;

    // Lock held by current reading, writing or connecting thread
    protected final Object recvLock = new Object();
    protected final Object sendLock = new Object();
    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    protected final Object stateLock = new Object();

    protected Address remoteAddress;
    protected Address localAddress;

    protected int state = ST_UNINITIALIZED;

    // IDs of native threads doing reads and writes, for signalling
    protected volatile long readerThread = 0;
    protected volatile long writerThread = 0;

    private long rxBytes = 0;
    private long txBytes = 0;

    protected UnixChannel(SelectorProvider provider) {
        super(provider);
    }

    @Override
    public int validOps() {
        return (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    public boolean connect(Address address) throws IOException {
        this.remoteAddress = address;

        synchronized (recvLock) {
            synchronized (sendLock) {
                synchronized (stateLock) {
                    ensureOpenAndUnconnected();
                    _executeConnect(address);
                }
            }
        }

        return true;
    }

    protected abstract void _executeConnect(Address address) throws IOException;

    public boolean isConnected() {
        synchronized (stateLock) {
            return (state == ST_CONNECTED);
        }
    }

    public boolean isListening() {
        synchronized (stateLock) {
            return (state == ST_LISTENING);
        }
    }

    @Override
    public long write(ByteBuffer[] buffers, int offset, int length)
            throws IOException {
        return write(shiftBuffers(buffers, offset, length));
    }

    @Override
    public long write(ByteBuffer[] buffers) throws IOException {
        synchronized (sendLock) {
            ensureConnected();
            int n = 0;
            try {
                if (!prepareWrite())
                    return 0;
                do {
                    n = (int)IOUtil.write(fd, buffers, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return normalizeAndCountTxBytes(n);
            } finally {
                finishWrite(n);
            }
        }
    }

    @Override
    public int write(ByteBuffer buffer) throws IOException {
        if (buffer == null)
            throw new NullPointerException();

        synchronized (sendLock) {
            ensureConnected();
            int n = 0;
            try {
                if (!prepareWrite())
                    return 0;
                do {
                    n = IOUtil.write(fd, buffer, -1, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return normalizeAndCountTxBytes(n);
            } finally {
                finishWrite(n);
            }
        }
    }

    @Override
    public long read(ByteBuffer[] buffers, int offset, int length)
            throws IOException {
        return read(shiftBuffers(buffers, offset, length));
    }

    @Override
    public long read(ByteBuffer[] buffers) throws IOException {
        synchronized (recvLock) {
            ensureConnected();
            int n = 0;
            try {
                if (!prepareRead())
                    return n;
                do {
                    n = (int)IOUtil.read(fd, buffers, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return normalizeAndCountRxBytes(n);
            } finally {
                finishRead(n);
            }
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (dst == null)
            throw new NullPointerException();

        synchronized (recvLock) {
            ensureConnected();
            int n = 0;
            try {
                if (!prepareRead())
                    return n;
                do {
                    n = IOUtil.read(fd, dst, -1, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return normalizeAndCountRxBytes(n);
            } finally {
                finishRead(n);
            }
        }
    }

    private boolean prepareWrite() {
        begin();
        if (isOpen()) {
            writerThread = NativeThread.current();
            return true;
        }
        return false;
    }

    private boolean prepareRead() {
        begin();
        if (isOpen()) {
            readerThread = NativeThread.current();
            return true;
        }
        return false;
    }

    private void finishWrite(int txBytes) throws AsynchronousCloseException {
        writerThread = 0;
        end((txBytes > 0) || (txBytes == IOStatus.UNAVAILABLE));
        assert IOStatus.check(txBytes);
    }

    private void finishRead(int rxBytes) throws AsynchronousCloseException {
        readerThread = 0;
        end((rxBytes > 0) || (rxBytes == IOStatus.UNAVAILABLE));
        assert IOStatus.check(rxBytes);
    }

    private int normalizeAndCountRxBytes(int readBytes) {
        readBytes = IOStatus.normalize(readBytes);
        rxBytes += readBytes;
        return readBytes;
    }

    private int normalizeAndCountTxBytes(int writtenBytes) {
        writtenBytes = IOStatus.normalize(writtenBytes);
        txBytes += writtenBytes;
        return writtenBytes;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen())
            throw new ClosedChannelException();
    }

    private void ensureConnected() throws ClosedChannelException {
        synchronized (stateLock) {
            ensureOpen();
            if (!isConnected())
                throw new NotYetConnectedException();
        }
    }

    void ensureOpenAndUnconnected() throws IOException { // package-private
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (state != ST_UNCONNECTED)
                throw new IllegalStateException("Connect already invoked");
        }
    }

    void ensureOpenAndListening() throws IOException { // package-private
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (state != ST_LISTENING)
                throw new IllegalStateException("Channel not listening");
        }
    }

    @Override
    protected void implCloseSelectableChannel() throws IOException {
        synchronized (stateLock) {
            nd.preClose(fd);
            long th;
            if ((th = readerThread) != 0)
                NativeThread.signal(th);
            if ((th = writerThread) != 0)
                NativeThread.signal(th);
            if (!isRegistered())
                kill();
        }
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        IOUtil.configureBlocking(fd, block);
    }

    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        int newOps = 0;

        if ((ops & SelectionKey.OP_ACCEPT) != 0)
            newOps |= Net.POLLIN;
        if ((ops & SelectionKey.OP_READ) != 0)
            newOps |= Net.POLLIN;
        if ((ops & SelectionKey.OP_WRITE) != 0)
            newOps |= Net.POLLOUT;
        if ((ops & SelectionKey.OP_CONNECT) != 0)
            newOps |= Net.POLLIN;

        sk.selector.putEventOps(sk, newOps);
    }

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        int initialOps = sk.nioReadyOps();
        return translateReadyOps(ops, initialOps, sk);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, 0, sk);
    }

    /**
     * Translates native poll event set into a ready operation set
     */
    private boolean translateReadyOps(int ops, int initialOps,
                                     SelectionKeyImpl sk) {
        int intOps = sk.nioInterestOps();
        int oldOps = sk.nioReadyOps();
        int newOps = initialOps;

        if ((ops & Net.POLLNVAL) != 0) {
            // This should only happen if this channel is pre-closed while a
            // selection operation is in progress
            // ## Throw an error if this channel has not been pre-closed
            return false;
        }

        if ((ops & (Net.POLLERR | Net.POLLHUP)) != 0) {
            newOps = intOps;
            sk.nioReadyOps(newOps);
            return (newOps & ~oldOps) != 0;
        }

        if (((ops & Net.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_READ) != 0))
            newOps |= SelectionKey.OP_READ;

        if (((ops & Net.POLLIN) != 0) &&
                ((intOps & SelectionKey.OP_ACCEPT) != 0))
            newOps |= SelectionKey.OP_ACCEPT;

        if (((ops & Net.POLLOUT) != 0) &&
            ((intOps & SelectionKey.OP_WRITE) != 0))
            newOps |= SelectionKey.OP_WRITE;

        if (((ops & Net.POLLHUP) != 0) &&
                ((intOps & SelectionKey.OP_WRITE) != 0))
            newOps |= SelectionKey.OP_WRITE;

        sk.nioReadyOps(newOps);
        return (newOps & ~oldOps) != 0;
    }

    public FileDescriptor getFD() {
        return fd;
    }

    public int getFDVal() {
        return fdVal;
    }

    public Address getRemoteAddress() {
        return remoteAddress;
    }

    @Nullable
    public Address getLocalAddress() {
        return localAddress;
    }

    public long rxBytes() {
        return rxBytes;
    }

    public long txBytes() {
        return txBytes;
    }

    public void kill() throws IOException {
        synchronized (stateLock) {
            if (state == ST_KILLED)
                return;
            if (state == ST_UNINITIALIZED) {
                state = ST_KILLED;
                return;
            }
            assert !isOpen() && !isRegistered();
            closeFileDescriptor();
            state = ST_KILLED;
        }
    }

    protected void closeFileDescriptor() throws IOException {
        if (cLibrary.lib.close(getFDVal()) < 0) {
            throw new IOException("failed to close the socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }
    }

    private static ByteBuffer[] shiftBuffers(ByteBuffer[] buffers,
                                             int offset, int length) {
        if ((offset < 0) || (length < 0) || (offset > buffers.length - length))
            throw new IndexOutOfBoundsException(
                "cannot take a slice of length " + length + " at offset "
                + offset + " of an array of length " + buffers.length);

        ByteBuffer[] shiftedBuffers = buffers;
        if ((offset != 0) || (length != shiftedBuffers.length)) {
            shiftedBuffers = new ByteBuffer[length];
            System.arraycopy(buffers, offset, shiftedBuffers, 0, length);
        }
        return shiftedBuffers;
    }
}
