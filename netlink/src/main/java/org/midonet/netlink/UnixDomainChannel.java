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
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.HashSet;
import java.util.Set;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.nio.ch.IOUtil;
import sun.nio.ch.NativeThread;

import org.midonet.jna.CLibrary;
import org.midonet.jna.Socket;
import org.midonet.util.AfUnix;

/**
 * Abstracts a unix domain socket channel. The implementation will use a
 * native unix domain socket.
 */
public abstract class UnixDomainChannel extends UnixChannel<AfUnix.Address>
                                        implements NetworkChannel {

    private static final Logger log =
        LoggerFactory.getLogger(UnixDomainChannel.class);

    private static final int validOps =
        (SelectionKey.OP_READ | SelectionKey.OP_WRITE | SelectionKey.OP_ACCEPT);

    protected UnixDomainChannel(SelectorProvider provider,
                                AfUnix.Type sockType) throws LastErrorException {
        super(provider);
        this.state = ST_UNCONNECTED;

        int socket;
        try {
            socket = CLibrary.socket(Socket.AF_UNIX, sockType.getValue(), 0);
        } catch (LastErrorException e) {
            log.error("Could not create UNIX domain socket: {}",
                      CLibrary.strerror(e.getErrorCode()));
            throw e;
        }

        fd = IOUtil.newFD(socket);
        fdVal = IOUtil.fdVal(fd);
    }

    protected UnixDomainChannel(SelectorProvider provider,
                                AfUnix.Address parentLocalAddress,
                                AfUnix.Address remoteAddress,
                                int childSocket) {
        super(provider);
        fd = IOUtil.newFD(childSocket);
        fdVal = IOUtil.fdVal(fd);
        this.state = ST_CONNECTED;
        this.localAddress = parentLocalAddress;
        this.remoteAddress = remoteAddress;
    }


    @Override
    public int validOps() {
        return validOps;
    }

    @Override
    public NetworkChannel bind(SocketAddress address) throws IOException {
        if (!(address instanceof AfUnix.Address)) {
            throw new IllegalArgumentException(
                "UnixDomainChannel needs an AfUnix.Address address");
        }
        this.localAddress = (AfUnix.Address) address;

        synchronized (recvLock) {
            synchronized (sendLock) {
                synchronized (stateLock) {
                    ensureOpenAndUnconnected();
                    executeBind(localAddress);
                }
            }
        }

        return this;
    }

    public UnixDomainChannel accept() throws IOException {
        UnixDomainChannel newConn = null;

        synchronized (recvLock) {
            synchronized (sendLock) {
                synchronized (stateLock) {
                    ensureOpenAndListening();
                }
                begin();
                try {
                    accepterThread = NativeThread.current();
                    newConn = executeAccept();
                } finally {
                    accepterThread = 0;
                    end(newConn != null);
                }
            }
        }

        return newConn;
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return new HashSet<SocketOption<?>>();
    }

    @Override
    public <T> UnixDomainChannel setOption(SocketOption<T> name, T value) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        throw new UnsupportedOperationException();
    }

    protected void _executeConnect(AfUnix.Address address) throws IOException {
        remoteAddress = address;
        CLibrary.UnixDomainSockAddress remote = address.toCLibrary();

        if (CLibrary.connect(fdVal, remote, remote.size()) < 0) {
            throw new IOException("failed to connect to socket: " +
                                  CLibrary.strerror(Native.getLastError()));
        }

        state = ST_CONNECTED;
    }

    protected void executeBind(AfUnix.Address address) throws IOException {
        localAddress = address;
        CLibrary.UnixDomainSockAddress local = address.toCLibrary();

        if (CLibrary.bind(fdVal, local, local.size()) != 0) {
            throw new IOException("failed to bind socket: " +
                                  CLibrary.strerror(Native.getLastError()));
        }

        if (CLibrary.listen(fdVal, 10) != 0) {
            throw new IOException("failed to listen on socket: " +
                                  CLibrary.strerror(Native.getLastError()));
        }

        state = ST_LISTENING;
    }

    protected UnixDomainChannel executeAccept() throws IOException {

        CLibrary.UnixDomainSockAddress remote =
            new CLibrary.UnixDomainSockAddress();
        IntByReference remoteSize = new IntByReference(remote.size());

        int childFd = CLibrary.accept(fdVal, remote, remoteSize);
        if (childFd < 0) {
            throw new IOException("failed to accept() on socket: " +
                                  CLibrary.strerror(Native.getLastError()));
        }

        return Netlink.selectorProvider().openUnixDomainSocketChannel(
            localAddress, new AfUnix.Address(remote), childFd);
    }
}
