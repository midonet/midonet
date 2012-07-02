/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink;


import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;

import com.sun.istack.internal.Nullable;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.IOStatus;
import sun.nio.ch.SelChImpl;
import sun.nio.ch.SelectionKeyImpl;

import com.midokura.util.netlink.clib.cLibrary;

import com.midokura.util.netlink.hacks.IOUtil;
import com.midokura.util.netlink.hacks.NativeDispatcher;
import com.midokura.util.netlink.hacks.NativeThread;
import com.midokura.util.netlink.hacks.PollArrayWrapper;
import com.midokura.util.netlink.hacks.SelectionKeyImplCaller;
import com.midokura.util.netlink.hacks.SelectorCaller;

/**
 * Implementation of a NetlinkChannel.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/27/12
 */
public class NetlinkChannelImpl extends NetlinkChannel implements SelChImpl {

    private static final Logger log = LoggerFactory
        .getLogger(NetlinkChannelImpl.class);

    private Netlink.Protocol protocol;
    private Netlink.Address remoteAddress, localAddress;

    // Lock held by current reading or connecting thread
    private final Object readLock = new Object();

    // Lock held by current writing or connecting thread
    private final Object writeLock = new Object();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    private final Object stateLock = new Object();

    static cLibrary cLib = (cLibrary) Native.loadLibrary("c", cLibrary.class);

    // fd value needed for dev/poll. This value will remain valid
    // even after the value in the file descriptor object has been set to -1
    private int fdVal;

    // Our file descriptor
    private final FileDescriptor fd;

    // Used to make native read and write calls
    private static NativeDispatcher nd = new NativeDispatcher();
    // normally this should be replaced by
    // private static NativeDispatcher nd = new sun.nio.ch.DatagramDispatcher();

    // IDs of native threads doing reads and writes, for signalling
    private volatile long readerThread = 0;
    private volatile long writerThread = 0;

    private static final int ST_UNINITIALIZED = -1;
    private static final int ST_UNCONNECTED = 0;
    private static final int ST_CONNECTED = 1;
    private static final int ST_KILLED = 2;

    private int state = ST_UNINITIALIZED;

    public NetlinkChannelImpl(SelectorProvider provider,
                              Netlink.Protocol protocol) {
        super(provider);
        this.protocol = protocol;
        this.state = ST_UNCONNECTED;

        int socket = cLib.socket(cLibrary.AF_NETLINK, cLibrary.SOCK_RAW,
                                 protocol.getValue());

        if (socket == -1) {
            log.error("Could not create netlink socket: {}",
                      cLib.strerror(Native.getLastError()));
        }

        fd = IOUtil.newFD(socket);
        fdVal = IOUtil.fdVal(fd);

    }

    public boolean connect(Netlink.Address address) throws IOException {
        this.remoteAddress = address;

        synchronized (readLock) {
            synchronized (writeLock) {
                synchronized (stateLock) {
                    ensureOpenAndUnconnected();

                    cLibrary.NetlinkSockAddress remote = new cLibrary.NetlinkSockAddress();
                    remote.nl_family = cLibrary.AF_NETLINK;
                    remote.nl_pid = address.getPid();

                    if (cLib.connect(fdVal, remote, remote.size()) < 0) {
                        throw
                            new IOException("failed to connect to socket: " +
                                                cLib.strerror(
                                                    Native.getLastError()));
                    }

                    cLibrary.NetlinkSockAddress local = new cLibrary.NetlinkSockAddress();
                    IntByReference localSize = new IntByReference(local.size());

                    if (cLib.getsockname(fdVal, local, localSize) < 0) {
                        throw
                            new IOException("failed to connect to socket: " +
                                                cLib.strerror(
                                                    Native.getLastError()));
                    }

                    log.debug("Netlink connection returned pid: {}.", local.nl_pid);
                    localAddress = new Netlink.Address(local.nl_pid);

                    if (cLib.bind(fdVal, local, local.size()) < 0) {
                        throw
                            new IOException("failed to connect to socket: " +
                                                cLib.strerror(
                                                    Native.getLastError()));
                    }

                    state = ST_CONNECTED;
                }
            }
        }

        return true;
    }

    public boolean isConnected() {
        synchronized(stateLock) {
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

        ByteBuffer []wBuffers = buffers;
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

        ByteBuffer []rBuffers = buffers;
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

    @Override
    public FileDescriptor getFD() {
        return fd;
    }

    @Override
    public int getFDVal() {
        return fdVal;
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

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, SelectionKeyImplCaller.nioReadyOps(sk), sk);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, 0, sk);
    }

    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        int newOps = 0;

        if ((ops & SelectionKey.OP_READ) != 0)
            newOps |= PollArrayWrapper.POLLIN;
        if ((ops & SelectionKey.OP_WRITE) != 0)
            newOps |= PollArrayWrapper.POLLOUT;
        if ((ops & SelectionKey.OP_CONNECT) != 0)
            newOps |= PollArrayWrapper.POLLIN;

        SelectorCaller.putEventOps(sk.selector(), sk, newOps);
    }

    @Override
    public void kill() throws IOException {
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

    @Override
    public int validOps() {
        return (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
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
}
