/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
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

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.clib.cLibrary.UnixDomainSockAddress;
import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.hacks.IOUtil;

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
                                AfUnix.Type sockType) {
        super(provider);
        this.state = ST_UNCONNECTED;

        int socket =
            cLibrary.lib.socket(cLibrary.AF_UNIX, sockType.getValue(), 0);

        if (socket == -1) {
            log.error("Could not create unix domain socket: {}",
                      cLibrary.lib.strerror(Native.getLastError()));
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
                    newConn = executeAccept();
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
        cLibrary.UnixDomainSockAddress remote = address.toCLibrary();

        if (cLibrary.lib.connect(fdVal, remote, remote.size()) < 0) {
            throw new IOException("failed to connect to socket: " +
                                  cLibrary.lib.strerror(Native.getLastError()));
        }

        state = ST_CONNECTED;
    }

    protected void executeBind(AfUnix.Address address) throws IOException {
        localAddress = address;
        cLibrary.UnixDomainSockAddress local = address.toCLibrary();

        if (cLibrary.lib.bind(fdVal, local, local.size()) != 0) {
            throw new IOException("failed to bind socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }

        if (cLibrary.lib.listen(fdVal, 10) != 0) {
            throw new IOException("failed to listen on socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }

        state = ST_LISTENING;
    }

    protected UnixDomainChannel executeAccept() throws IOException {

        cLibrary.UnixDomainSockAddress remote =
            new cLibrary.UnixDomainSockAddress();
        IntByReference remoteSize = new IntByReference(remote.size());

        int childFd = cLibrary.lib.accept(fdVal, remote, remoteSize);
        if (childFd < 0) {
            throw new IOException("failed to accept() on socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }

        return Netlink.selectorProvider().openUnixDomainSocketChannel(
            localAddress, new AfUnix.Address(remote), childFd);
    }
}
