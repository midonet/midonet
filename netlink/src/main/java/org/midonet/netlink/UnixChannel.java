/*
* Copyright 2013 Midokura Europe SARL
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.SelectionKeyImpl;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.hacks.*;

/**
 * Abstracts a netlink channel. The implementation will make a native netlink
 * socket connection to the local machine.
 */
public abstract class UnixChannel<Address> extends AbstractSelectableChannel
    implements ByteChannel, ScatteringByteChannel,
               GatheringByteChannel, Channel,
               InterruptibleChannel
{
    private static final Logger log = LoggerFactory
            .getLogger(UnixChannel.class);

    protected static final int ST_UNINITIALIZED = -1;
    protected static final int ST_UNCONNECTED = 0;
    protected static final int ST_CONNECTED = 1;
    protected static final int ST_KILLED = 2;
    protected static final int ST_LISTENING = 3;
    // Used to make native read and write calls
    protected static NativeDispatcher nd = new NativeDispatcher();
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

    protected UnixChannel(SelectorProvider provider) {
        super(provider);
    }

    @Override
    public int validOps() {
        return (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    protected boolean _connect(Address address) throws IOException {
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

        synchronized (sendLock) {
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

        synchronized (sendLock) {
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
                    n = IOUtil.write(fd, buffer, -1, nd);
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

        synchronized (recvLock) {
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

        synchronized (recvLock) {
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
                    n = IOUtil.read(fd, dst, -1, nd);
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
            newOps |= PollArrayWrapper.POLLIN;
        if ((ops & SelectionKey.OP_READ) != 0)
            newOps |= PollArrayWrapper.POLLIN;
        if ((ops & SelectionKey.OP_WRITE) != 0)
            newOps |= PollArrayWrapper.POLLOUT;
        if ((ops & SelectionKey.OP_CONNECT) != 0)
            newOps |= PollArrayWrapper.POLLIN;

        SelectorCaller.putEventOps(sk.selector(), sk, newOps);
    }


    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        int initialOps = SelectionKeyImplCaller.nioReadyOps(sk);
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

        if (((ops & PollArrayWrapper.POLLIN) != 0) &&
                ((intOps & SelectionKey.OP_ACCEPT) != 0))
            newOps |= SelectionKey.OP_ACCEPT;

        if (((ops & PollArrayWrapper.POLLOUT) != 0) &&
            ((intOps & SelectionKey.OP_WRITE) != 0))
            newOps |= SelectionKey.OP_WRITE;

        if (((ops & PollArrayWrapper.POLLHUP) != 0) &&
                ((intOps & SelectionKey.OP_WRITE) != 0))
            newOps |= SelectionKey.OP_WRITE;

        SelectionKeyImplCaller.nioReadyOps(sk, newOps);
        return (newOps & ~oldOps) != 0;
    }

    public FileDescriptor getFD() {
        return fd;
    }

    public int getFDVal() {
        return fdVal;
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
}
