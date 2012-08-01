/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.SelectorProvider;
import javax.annotation.Nullable;

import sun.nio.ch.SelectionKeyImpl;

import com.midokura.netlink.hacks.IOStatus;
import com.midokura.netlink.hacks.IOUtil;
import com.midokura.netlink.hacks.NativeDispatcher;
import com.midokura.netlink.hacks.NativeThread;
import com.midokura.netlink.hacks.PollArrayWrapper;
import com.midokura.netlink.hacks.SelectionKeyImplCaller;

/**
 * Abstracts a netlink channel. The implementation will make a native netlink
 * socket connection to the local machine.
 */
public abstract class NetlinkChannel extends AbstractSelectableChannel
    implements ByteChannel, ScatteringByteChannel,
               GatheringByteChannel, Channel,
               InterruptibleChannel
{
    protected static final int ST_UNINITIALIZED = -1;
    protected static final int ST_UNCONNECTED = 0;
    protected static final int ST_CONNECTED = 1;
    protected static final int ST_KILLED = 2;
    // Used to make native read and write calls
    protected static NativeDispatcher nd = new NativeDispatcher();
    // fd value needed for dev/poll. This value will remain valid
    // even after the value in the file descriptor object has been set to -1
    protected int fdVal;
    // Our file descriptor
    protected FileDescriptor fd;

    // Lock held by current reading or connecting thread
    protected final Object readLock = new Object();
    // Lock held by current writing or connecting thread
    protected final Object writeLock = new Object();
    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    protected final Object stateLock = new Object();

    protected Netlink.Address remoteAddress;
    protected Netlink.Address localAddress;

    protected int state = ST_UNINITIALIZED;

    // IDs of native threads doing reads and writes, for signalling
    protected volatile long readerThread = 0;
    protected volatile long writerThread = 0;

    protected Netlink.Protocol protocol;

    protected NetlinkChannel(SelectorProvider provider, Netlink.Protocol protocol) {
        super(provider);
        this.protocol = protocol;
    }

    @Override
    public int validOps() {
        return (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    public boolean connect(Netlink.Address address) throws IOException {
        this.remoteAddress = address;

        synchronized (readLock) {
            synchronized (writeLock) {
                synchronized (stateLock) {
                    ensureOpenAndUnconnected();
                    _executeConnect(address);
                }
            }
        }

        return true;
    }

    protected abstract void _executeConnect(Netlink.Address address) throws IOException;

    public boolean isConnected() {
        synchronized (stateLock) {
            return (state == ST_CONNECTED);
        }
    }

    public Netlink.Address getRemoteAddress() {
        return remoteAddress;
    }

    @Nullable
    public Netlink.Address getLocalAddress() {
        return localAddress;
    }

    @Override
    public long write(ByteBuffer[] buffers) throws IOException {
        return write(buffers, 0, buffers.length);
    }

    @Override
    public long write(ByteBuffer[] buffers, int offset, int length)
        throws IOException {
        if ((offset < 0) || (length < 0) || (offset > buffers.length - length))
            throw new IndexOutOfBoundsException();

        ByteBuffer[] wBuffers = buffers;
        if ((offset != 0) || (length != buffers.length)) {
            wBuffers = new ByteBuffer[length];
            System.arraycopy(buffers, offset, wBuffers, 0, length);
        }

        synchronized (writeLock) {
            synchronized (stateLock) {
                ensureOpen();
                if (!isConnected())
                    throw new NotYetConnectedException();
            }
            long n = 0;
            try {
                begin();
                if (!isOpen())
                    return 0;
                writerThread = NativeThread.current();
                do {
                    n = IOUtil.write(fd, wBuffers, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                writerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }

    }

    @Override
    public int write(ByteBuffer buffer) throws IOException {
        if (buffer == null)
            throw new NullPointerException();

        synchronized (writeLock) {
            synchronized (stateLock) {
                ensureOpen();
                if (!isConnected())
                    throw new NotYetConnectedException();
            }
            int n = 0;
            try {
                begin();
                if (!isOpen())
                    return 0;
                writerThread = NativeThread.current();
                do {
                    n = IOUtil.write(fd, buffer, -1, nd, writeLock);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                writerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }
    }

    @Override
    public long read(ByteBuffer[] buffers) throws IOException {
        return read(buffers, 0, buffers.length);
    }

    @Override
    public long read(ByteBuffer[] buffers, int offset, int length)
        throws IOException {

        if ((offset < 0) || (length < 0) || (offset > buffers.length - length))
            throw new IndexOutOfBoundsException();

        ByteBuffer[] rBuffers = buffers;
        if ((offset != 0) || (length != rBuffers.length)) {
            rBuffers = new ByteBuffer[length];
            System.arraycopy(buffers, offset, rBuffers, 0, length);
        }

        synchronized (readLock) {
            synchronized (stateLock) {
                ensureOpen();
                if (!isConnected())
                    throw new NotYetConnectedException();
            }
            long n = 0;
            try {
                begin();
                if (!isOpen())
                    return 0;
                readerThread = NativeThread.current();
                do {
                    n = IOUtil.read(fd, buffers, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                readerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (dst == null)
            throw new NullPointerException();

        synchronized (readLock) {
            synchronized (stateLock) {
                ensureOpen();
                if (!isConnected())
                    throw new NotYetConnectedException();
            }
            int n = 0;
            try {
                begin();
                if (!isOpen())
                    return 0;
                readerThread = NativeThread.current();
                do {
                    n = IOUtil.read(fd, dst, -1, nd, readLock);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                readerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }

    }

    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen())
            throw new ClosedChannelException();
    }

    void ensureOpenAndUnconnected() throws IOException { // package-private
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (state != ST_UNCONNECTED)
                throw new IllegalStateException("Connect already invoked");
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
                _kill();
        }
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        IOUtil.configureBlocking(fd, block);
    }

    protected void _kill() {
        synchronized (stateLock) {
            if (state == ST_KILLED)
                return;
            if (state == ST_UNINITIALIZED) {
                state = ST_KILLED;
                return;
            }
            assert !isOpen() && !isRegistered();
            // nd.close(fd);
            state = ST_KILLED;
        }
    }

    /**
     * Translates native poll event set into a ready operation set
     */
    public boolean translateReadyOps(int ops, int initialOps,
                                     SelectionKeyImpl sk) {
        int intOps = SelectionKeyImplCaller.nioInterestOps(sk);
        int oldOps = SelectionKeyImplCaller.nioReadyOps(sk);
        int newOps = initialOps;

        if ((ops & PollArrayWrapper.POLLNVAL) != 0) {
            // This should only happen if this channel is pre-closed while a
            // selection operation is in progress
            // ## Throw an error if this channel has not been pre-closed
            return false;
        }

        if ((ops & (PollArrayWrapper.POLLERR
            | PollArrayWrapper.POLLHUP)) != 0) {
            newOps = intOps;
            SelectionKeyImplCaller.nioReadyOps(sk, newOps);
            return (newOps & ~oldOps) != 0;
        }

        if (((ops & PollArrayWrapper.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_READ) != 0))
            newOps |= SelectionKey.OP_READ;

        if (((ops & PollArrayWrapper.POLLOUT) != 0) &&
            ((intOps & SelectionKey.OP_WRITE) != 0))
            newOps |= SelectionKey.OP_WRITE;

        SelectionKeyImplCaller.nioReadyOps(sk, newOps);
        return (newOps & ~oldOps) != 0;
    }
}
