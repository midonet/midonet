/*
 * Copyright (c) 2001, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

public class NetlinkChannelImpl extends AbstractSelectableChannel implements ByteChannel, ScatteringByteChannel, GatheringByteChannel, SelChImpl {

	public static class SockAddrNetlink extends Structure {
		public short nl_family;
	    public short nl_pad;
	    public int nl_pid;
	    public int nl_groups;
	}
	
	interface Libc extends Library {
	    public static final int AF_NETLINK = 16;
	    public static final int SOCK_RAW = 3;
	    
	    static final int SOL_NETLINK = 270;
	    
	    static final int NETLINK_ADD_MEMBERSHIP = 1;
	    static final int NETLINK_DROP_MEMBERSHIP = 2;
		
		int socket(int domain, int type, int protocol);
		int connect(int fd, SockAddrNetlink addr, int size);
		int bind(int fd, SockAddrNetlink addr, int size);
		int getsockname(int fd, SockAddrNetlink addr, IntByReference size);
		int setsockopt(int fd, int level, int optname, ByteBuffer buf, IntByReference optlen);
		
		int send(int fd, ByteBuffer buf, int len, int flags);
		int recv(int fd, ByteBuffer buf, int len, int flags);
	}
	
	static Libc libc = (Libc) Native.loadLibrary("c", Libc.class);
	
	static AtomicInteger pidSeq = new AtomicInteger(1);
	
    // Used to make native read and write calls
    private static NativeDispatcher nd = new DatagramDispatcher();

    // Our file descriptor
    private final FileDescriptor fd;

    // fd value needed for dev/poll. This value will remain valid
    // even after the value in the file descriptor object has been set to -1
    private final int fdVal;

    // The protocol of the socket
    private int protocol;

    // IDs of native threads doing reads and writes, for signalling
    private volatile long readerThread = 0;
    private volatile long writerThread = 0;

    // Lock held by current reading or connecting thread
    private final Object readLock = new Object();

    // Lock held by current writing or connecting thread
    private final Object writeLock = new Object();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    private final Object stateLock = new Object();

    // -- The following fields are protected by stateLock

    // State (does not necessarily increase monotonically)
    private static final int ST_UNINITIALIZED = -1;
    private static final int ST_UNCONNECTED = 0;
    private static final int ST_CONNECTED = 1;
    private static final int ST_KILLED = 2;
    
    private int state = ST_UNINITIALIZED;
    
    private int localPid;
	
	public NetlinkChannelImpl(SelectorProvider sp, int protocol) throws IOException {
		super(sp);
		
		this.protocol = protocol;
		
		fd = IOUtil.newFD(libc.socket(Libc.AF_NETLINK, Libc.SOCK_RAW, protocol));
		fdVal = IOUtil.fdVal(fd);
		
		this.state = ST_UNCONNECTED;
	}

	public NetlinkChannelImpl connect(int pid) throws IOException {
		
		synchronized(readLock) {
            synchronized(writeLock) {
                synchronized (stateLock) {
                    ensureOpenAndUnconnected();
                    
                    SockAddrNetlink remote = new SockAddrNetlink();
            		remote.nl_family = Libc.AF_NETLINK;
            		remote.nl_pid = pid;
            		
            		if (libc.connect(fdVal, remote, remote.size()) < 0) {
            			throw new IOException();
            		}

            		SockAddrNetlink local = new SockAddrNetlink();
            		IntByReference localSize = new IntByReference(local.size());
            		
            		if (libc.getsockname(fdVal, local, localSize) < 0) {
            			throw new IOException();
            		}

            		localPid = local.nl_pid;
            		
            		if (libc.bind(fdVal, local, local.size()) < 0) {
            			throw new IOException();
            		}
            		
            		state = ST_CONNECTED;
                }
            }
        }
		
        return this;
	}

	public int getLocalPid() {
		return localPid;
	}

	public FileDescriptor getFD() {
		return fd;
	}

	public int getFDVal() {
		return fdVal;
	}

	/**
     * Translates native poll revent set into a ready operation set
     */
    public boolean translateReadyOps(int ops, int initialOps,
                                     SelectionKeyImpl sk) {
        int intOps = sk.nioInterestOps(); // Do this just once, it synchronizes
        int oldOps = sk.nioReadyOps();
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
            sk.nioReadyOps(newOps);
            return (newOps & ~oldOps) != 0;
        }

        if (((ops & PollArrayWrapper.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_READ) != 0))
            newOps |= SelectionKey.OP_READ;

        if (((ops & PollArrayWrapper.POLLOUT) != 0) &&
            ((intOps & SelectionKey.OP_WRITE) != 0))
            newOps |= SelectionKey.OP_WRITE;

        sk.nioReadyOps(newOps);
        return (newOps & ~oldOps) != 0;
    }
	
	public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
		return translateReadyOps(ops, sk.nioReadyOps(), sk);
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
        sk.selector.putEventOps(sk, newOps);
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
            nd.close(fd);
            state = ST_KILLED;
        }
	}

	public boolean isConnected() {
		synchronized(stateLock) {
			return (state == ST_CONNECTED);
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

	public int read(ByteBuffer buf) throws IOException {
		
//		return libc.recv(fdVal, buf, buf.remaining(), 0);

		if (buf == null)
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
                    n = IOUtil.read(fd, buf, -1, nd, readLock);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                readerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }
	}

	public long read(ByteBuffer[] dsts, int offset, int length)
			throws IOException {
		if ((offset < 0) || (length < 0) || (offset > dsts.length - length))
            throw new IndexOutOfBoundsException();
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
                    n = IOUtil.read(fd, dsts, offset, length, nd);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                readerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }
	}

	public int write(ByteBuffer buf) throws IOException {
		
//		return libc.send(fdVal, buf, buf.remaining(), 0);

		if (buf == null)
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
                    n = IOUtil.write(fd, buf, -1, nd, writeLock);
                } while ((n == IOStatus.INTERRUPTED) && isOpen());
                return IOStatus.normalize(n);
            } finally {
                writerThread = 0;
                end((n > 0) || (n == IOStatus.UNAVAILABLE));
                assert IOStatus.check(n);
            }
        }
	}

	public long write(ByteBuffer[] srcs, int offset, int length)
			throws IOException {
		if ((offset < 0) || (length < 0) || (offset > srcs.length - length))
            throw new IndexOutOfBoundsException();
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
                    n = IOUtil.write(fd, srcs, offset, length, nd);
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

	private void ensureOpen() throws ClosedChannelException {
        if (!isOpen())
            throw new ClosedChannelException();
    }

	public long write(ByteBuffer[] srcs) throws IOException {
		return write(srcs, 0, srcs.length);
	}

	public long read(ByteBuffer[] dsts) throws IOException {
		return read(dsts, 0, dsts.length);
	}

	@Override
	public int validOps() {
		return (SelectionKey.OP_READ | SelectionKey.OP_WRITE);
	}

	public void setSendBufferSize(int size) throws IOException {
        // size 0 valid for SocketChannel, invalid for Socket
        if (size <= 0)
            throw new IllegalArgumentException("Invalid send size");
        Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_SNDBUF, size);
    }

    public int getSendBufferSize() throws IOException {
    	return (Integer) Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_SNDBUF);
    }

    public void setReceiveBufferSize(int size) throws IOException {
        // size 0 valid for SocketChannel, invalid for Socket
        if (size <= 0)
            throw new IllegalArgumentException("Invalid receive size");
        Net.setSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_RCVBUF, size);
    }

    public int getReceiveBufferSize() throws IOException {
    	return (Integer) Net.getSocketOption(fd, Net.UNSPEC, StandardSocketOptions.SO_RCVBUF);
    }

    public void addMembership(int group) throws IOException {
    	ByteBuffer buf = ByteBuffer.allocateDirect(4);
    	buf.putInt(group);

    	IntByReference optLen = new IntByReference(4);
    	
    	if (libc.setsockopt(fdVal, Libc.SOL_NETLINK, Libc.NETLINK_ADD_MEMBERSHIP, buf, optLen) < 0) {
    		throw new IOException("could not add membership");
    	}
    }
    
    public void dropMembership(int group) throws IOException {
    	ByteBuffer buf = ByteBuffer.allocateDirect(4);
    	buf.putInt(group);

    	IntByReference optLen = new IntByReference(4);
    	
    	if (libc.setsockopt(fdVal, Libc.SOL_NETLINK, Libc.NETLINK_DROP_MEMBERSHIP, buf, optLen) < 0) {
    		throw new IOException("could not drop membership");
    	}
    }

}
