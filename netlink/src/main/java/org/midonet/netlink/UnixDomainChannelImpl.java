/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.channels.spi.SelectorProvider;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.SelChImpl;
import sun.nio.ch.SelectionKeyImpl;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.hacks.IOUtil;
import org.midonet.netlink.hacks.SelectionKeyImplCaller;

/**
 * Implementation of a UnixDomainChannel.
 */
public class UnixDomainChannelImpl extends UnixDomainChannel implements SelChImpl {

    private static final Logger log = LoggerFactory
        .getLogger(UnixDomainChannelImpl.class);

    public UnixDomainChannelImpl(SelectorProvider provider, AfUnix.Type sockType) {
        super(provider);
        this.state = ST_UNCONNECTED;

        int socket = cLibrary.lib.socket(cLibrary.AF_UNIX, sockType.getValue(), 0);

        if (socket == -1) {
            log.error("Could not create unix domain socket: {}",
                      cLibrary.lib.strerror(Native.getLastError()));
        }

        fd = IOUtil.newFD(socket);
        fdVal = IOUtil.fdVal(fd);
    }

    protected UnixDomainChannelImpl(SelectorProvider provider,
                                    UnixDomainChannelImpl parent,
                                    AfUnix.Address remoteAddress,
                                    int childSocket) {
        super(provider);
        fd = IOUtil.newFD(childSocket);
        fdVal = IOUtil.fdVal(fd);
        this.state = ST_CONNECTED;
        this.localAddress = parent.localAddress;
        this.remoteAddress = remoteAddress;
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

    @Override
    protected void _executeBind(AfUnix.Address address) throws IOException {
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

    @Override
    protected UnixDomainChannel _executeAccept() throws IOException {

        cLibrary.UnixDomainSockAddress remote = new cLibrary.UnixDomainSockAddress();
        IntByReference remoteSize = new IntByReference(remote.size());

        int childFd = cLibrary.lib.accept(fdVal, remote, remoteSize);
        if (childFd < 0) {
            throw new IOException("failed to accept() on socket: " +
                    cLibrary.lib.strerror(Native.getLastError()));
        }

        return new UnixDomainChannelImpl(
            provider(), this, new AfUnix.Address(remote), childFd);
    }

    @Override
    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, SelectionKeyImplCaller.nioReadyOps(sk), sk);
    }

    @Override
    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, 0, sk);
    }

    @Override
    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        _translateAndSetInterestOps(ops, sk);
    }

    @Override
    public void kill() throws IOException {
        _kill();
    }

    @Override
    public FileDescriptor getFD() {
        return fd;
    }

    @Override
    public int getFDVal() {
        return fdVal;
    }

}
