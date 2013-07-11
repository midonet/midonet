/*
* Copyright 2012 Midokura Europe SARL
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

import org.midonet.netlink.clib.cLibrary.UnixDomainSockAddress;

import javax.annotation.Nullable;

/**
 * Abstracts a unix domain socket channel. The implementation will use a
 * native unix domain socket.
 */
public abstract class UnixDomainChannel extends UnixChannel<AfUnix.Address>
                                        implements NetworkChannel {

    protected UnixDomainChannel(SelectorProvider provider) {
        super(provider);
    }

    @Override
    public int validOps() {
        return (SelectionKey.OP_READ | SelectionKey.OP_WRITE | SelectionKey.OP_ACCEPT);
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
                    _executeBind(localAddress);
                }
            }
        }

        return this;
    }

    public boolean connect(AfUnix.Address address) throws IOException {
        return _connect(address);
    }

    public AfUnix.Address getRemoteAddress() {
        return remoteAddress;
    }

    @Nullable
    public AfUnix.Address getLocalAddress() {
        return localAddress;
    }

    public UnixDomainChannel accept() throws IOException {
        UnixDomainChannel newConn = null;

        synchronized (recvLock) {
            synchronized (sendLock) {
                synchronized (stateLock) {
                    ensureOpenAndListening();
                    newConn = _executeAccept();
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

    protected abstract void _executeBind(AfUnix.Address address) throws IOException;
    protected abstract UnixDomainChannel _executeAccept() throws IOException;
}
